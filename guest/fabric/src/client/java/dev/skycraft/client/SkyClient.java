package dev.skycraft.client;

import dev.skycraft.SkyCraft;
import dev.skycraft.client.render.WorldExporter;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLVideo;

/**
 * Per-frame glue between the Minecraft client and Skyrim. Everything here runs on the render
 * thread, called from MinecraftMixin.
 */
public final class SkyClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("skycraft.showWindow");
	// Started by Skyrim (SkyCraft's bundled instance passes -Dskycraft.startHidden=true): no window and
	// no title-screen music from the first frame, even while Skyrim is paused (Alt-Tabbed) and the
	// two haven't linked up yet. Otherwise the window only goes once Skyrim is there.
	private static final boolean START_HIDDEN = Boolean.getBoolean("skycraft.startHidden");
	private static boolean startedHidden;

	private static final SkyLink.SkyState sky = new SkyLink.SkyState();
	private static final SkyLink.McState mc = new SkyLink.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;

	// Teleport / hold state: Skyrim decides where the player is after loads, doors and respawns.
	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	// OWCraft: a mid-air tile change in flight (see shiftPlayer): where it lands, and since when.
	private static @org.jspecify.annotations.Nullable Vec3 shiftTarget;
	private static int shiftSeq;
	private static long shiftSince;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static Vec3 unlinkedHold;
	private static long holdSince;
	private static long qpcFreq;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean skyrimStalled;
	private static int exporterErrors;

	private SkyClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/**
	 * True once Skyrim has connected in this session. From then on Minecraft never touches the
	 * real mouse or keyboard again (even if Skyrim closes), since its window is hidden.
	 */
	public static boolean tookOver() {
		return tookOver;
	}

	public static SkyLink.SkyState sky() {
		return sky;
	}

	/**
	 * OWCraft fork: where the host's body is at this tick. The host writes once a frame, and a tick
	 * lands between frames: taking the last write as it is gave uneven steps per tick (two frames of
	 * walking, then three), and the hand and view bobbing jerked with them. The host's speed carries
	 * it on from when it was written to now.
	 */
	public static Vec3 drivenTarget() {
		double x = sky.x, y = sky.y, z = sky.z;
		if (sky.hostQpc != 0) {
			if (qpcFreq == 0) {
				qpcFreq = SkyLink.qpcFrequency();
			}
			double age = (SkyLink.qpc() - sky.hostQpc) / (double) qpcFreq;
			age = Math.max(0.0, Math.min(0.1, age));
			x += sky.velX * age;
			y += sky.velY * age;
			z += sky.velZ * age;
		}
		return new Vec3(x, y, z);
	}

	/** Start of Minecraft.runTick: pull state and input from Skyrim before anything else runs. */
	public static void beginFrame() {
		FrameTimings.beginFrame();
		SkyLink.hostDrivesPlayer = false;
		SkyLink.poll();
		quitWithSkyrim(Minecraft.getInstance());
		if (START_HIDDEN && !startedHidden) {
			startedHidden = true;
			Minecraft minecraft = Minecraft.getInstance();
			hideWindowOnce(minecraft);
			minecraft.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
			minecraft.getMusicManager().stopPlaying();
		}
		boolean nowLinked = SkyLink.active();
		if (nowLinked) {
			SkyLink.readSkyState(sky); // on a torn read we simply keep last frame's state
			dev.skycraft.world.SkyWater.refresh();
		} else {
			dev.skycraft.world.SkyWater.clear();
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			SkyCraft.LOG.info("SkyCraft: Skyrim link {}", linked ? "up" : "down");
			if (linked) {
				tookOver = true;
				unlinkedHold = null;
				SkyCollision.startConsumer();
				applyLinkedOptions();
			} else {
				InputBridge.releaseAll();
				LocalPlayer player = Minecraft.getInstance().player;
				unlinkedHold = player != null ? player.position() : null;
			}
		}
		if (!linked) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		MirrorWorld.openWhenReady(minecraft);

		if (sky.menuOpen() || sky.loading()) {
			InputBridge.releaseAll();
		}
		InputBridge.drain(minecraft);
		ProxySync.frame(minecraft);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}

		// A new player object means we just joined or respawned: put it where Skyrim's player is.
		if (player != lastPlayer) {
			lastPlayer = player;
			teleportPending = true;
		}
		boolean turned = sky.teleportSeq != lastTeleportSeq;
		if (turned) {
			FrameTimings.watchTileChange();
			lastTeleportSeq = sky.teleportSeq;
			teleportPending = true;
		}
		if (teleportPending && sky.inGame() && !sky.loading()) {
			teleportPending = false;
			if (sky.reanchor() && !sky.hostDrives() && !player.onGround() && holdPos == null && shiftTarget == null
				&& minecraft.getSingleplayerServer() != null) {
				carryAround(minecraft, player.position(), new Vec3(sky.x, sky.y, sky.z));
				shiftPlayer(minecraft, sky.x - player.getX(), sky.y - player.getY(), sky.z - player.getZ());
				shiftTarget = new Vec3(sky.x, sky.y, sky.z);
				shiftSeq = sky.teleportSeq;
				shiftSince = System.currentTimeMillis();
			} else {
				shiftTarget = null;
				if (sky.reanchor()) {
					carryAround(minecraft, player.position(), new Vec3(sky.x, sky.y, sky.z));
				}
				requestTeleport(minecraft, sky.x, sky.y, sky.z, sky.yaw, sky.pitch);
				teleportAck = sky.teleportSeq;
				// OWCraft fork: when the host moves the player, its own physics holds it up; no waiting
				// for Minecraft's collision under the feet.
				holdPos = sky.hostDrives() ? null : new Vec3(sky.x, sky.y, sky.z);
			}
		}
		if (shiftTarget != null) {
			// Tile patches sit 512 blocks apart: within 64 of the target, the shift has landed.
			if (player.position().distanceToSqr(shiftTarget) < 64 * 64) {
				teleportAck = shiftSeq;
				shiftTarget = null;
			} else if (System.currentTimeMillis() - shiftSince > 1000) {
				SkyCraft.LOG.warn("SkyCraft: tile shift didn't arrive; teleporting instead");
				shiftTarget = null;
				teleportPending = true;
			}
		}

		SkyLink.hostDrivesPlayer = driving();
		if (SkyLink.hostDrivesPlayer) {
			hostFalls(minecraft, player);
		} else {
			lastLandSeq = sky.landSeq;
		}

		// Look direction is driven by Skyrim (zero-latency camera); MC uses it for everything else.
		if (minecraft.gui.screen() == null) {
			if (turned) {
				// OWCraft fork: a tile change turns the frame, not the view. The hand's sway follows the
				// view lagging behind, so it swung left and right at every tile while gliding: turn it too.
				float dy = Mth.wrapDegrees(sky.yaw - player.getYRot()), dx = sky.pitch - player.getXRot();
				player.yBob += dy;
				player.yBobO += dy;
				player.xBob += dx;
				player.xBobO += dx;
			}
			// OWCraft fork: the host's yaw is 0..360, Minecraft wraps it to -180..180 in places; the hand's
			// sway chases the raw number, so a 360 jump spun it. Keep the yaw on the nearest turn.
			float yaw = player.getYRot() + Mth.wrapDegrees(sky.yaw - player.getYRot());
			player.setYRot(yaw);
			player.setXRot(sky.pitch);
			player.yRotO = yaw;
			player.xRotO = sky.pitch;
		}
	}

	// Minecraft is started with Skyrim (the SKSE plugin launches it), so it goes when that Skyrim has
	// closed for good: saved and shut down the normal way. -Dskycraft.quitWithSkyrim=false keeps it
	// running instead (development: restarting Skyrim without restarting Minecraft).
	private static final boolean QUIT_WITH_SKYRIM = Boolean.parseBoolean(System.getProperty("skycraft.quitWithSkyrim", "false"));
	private static long skyrimGoneSince;
	private static long nextSkyrimCheck;
	// Started hidden by Skyrim but never connected: nobody can see or use this Minecraft, and it
	// would stop the next Skyrim from starting a fresh one ("already running"). It goes after this.
	private static final long NEVER_CONNECTED_QUIT_MS = 10 * 60 * 1000;
	private static final long STARTED_AT = System.currentTimeMillis();
	private static boolean gaveUpWaiting;

	private static void quitWithSkyrim(Minecraft minecraft) {
		int pid = SkyLink.skyrimPid();
		long now = System.currentTimeMillis();
		if (QUIT_WITH_SKYRIM && START_HIDDEN && pid == 0 && !tookOver && !gaveUpWaiting && now - STARTED_AT > NEVER_CONNECTED_QUIT_MS) {
			gaveUpWaiting = true;
			SkyCraft.LOG.warn("SkyCraft: started hidden but Skyrim never connected in {} minutes; quitting", NEVER_CONNECTED_QUIT_MS / 60000);
			minecraft.stop();
			return;
		}
		if (!QUIT_WITH_SKYRIM || pid == 0 || now < nextSkyrimCheck) {
			return;
		}
		nextSkyrimCheck = now + 1000;
		if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			skyrimGoneSince = 0;
			return;
		}
		if (skyrimGoneSince == 0) {
			skyrimGoneSince = now;
		} else if (now - skyrimGoneSince > 5000) {
			SkyCraft.LOG.info("SkyCraft: Skyrim (pid {}) has closed; saving and quitting", pid);
			minecraft.stop();
		}
	}

	/**
	 * OWCraft fork: the host moves the player with its own physics (Outer Wilds' character, its gravity
	 * and collisions); Minecraft puts its player where the host says, every tick, and keeps the rest
	 * (hunger, fall damage from the moves it sends, breaking and placing).
	 */
	public static boolean driving() {
		return linked && sky.hostDrives() && sky.inGame() && !sky.loading() && holdPos == null && shiftTarget == null
			&& Minecraft.getInstance().player != null;
	}

	private static int lastLandSeq = Integer.MIN_VALUE;
	// OWCraft fork: the motion our last driven move left the player with; anything else that changes it
	// before the next move (knockback, an explosion, a firework) is a push for the host's body.
	public static Vec3 lastDrivenDelta = Vec3.ZERO;
	/** OWCraft fork: the player's tickCount until which a firework rocket pulls it (FireworkDrivenMixin). */
	public static int rocketBoostUntil = Integer.MIN_VALUE / 2;

	/** OWCraft fork: a push Minecraft gave the player, handed to the host (blocks per tick). */
	public static void pushImpulse(Vec3 push) {
		mc.impulseX = (float) push.x;
		mc.impulseY = (float) push.y;
		mc.impulseZ = (float) push.z;
		mc.impulseSeq++;
		SkyCraft.LOG.info("SkyCraft: push {} {} {} for the host", String.format("%.2f", push.x), String.format("%.2f", push.y), String.format("%.2f", push.z));
	}
	private static volatile float serverBehind; // diagnostics: how far the server's player was from the host's
	private static long lastBehindLog;

	/**
	 * OWCraft fork: fall damage by landing speed, not by blocks fallen. Falling slowly in a moon's
	 * weak gravity shouldn't hurt like falling the same height on Earth. The host reports each landing
	 * as the Minecraft fall that hits the ground that fast; Minecraft's own count is kept at zero.
	 */
	private static void hostFalls(Minecraft minecraft, LocalPlayer player) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		float fall = 0.0F;
		if (lastLandSeq == Integer.MIN_VALUE) {
			lastLandSeq = sky.landSeq; // just started following: earlier landings aren't ours to count
		} else if (sky.landSeq != lastLandSeq) {
			lastLandSeq = sky.landSeq;
			fall = sky.landFall;
		}
		final float landed = fall;
		var uuid = player.getUUID();
		final double tx = sky.x, ty = sky.y, tz = sky.z;
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp == null) {
				return;
			}
			// The server checks moves against its own collision (Minecraft's copy of the ground, its water);
			// where that disagrees with the host's it can refuse them and keep the player behind, and then
			// breaking and placing (checked from the server's position) miss what you look at. The host
			// is right: put the server's player there too.
			double behind = sp.position().distanceTo(new Vec3(tx, ty, tz));
			serverBehind = (float) behind;
			if (behind > 0.5) {
				if (behind > 2.0 && System.currentTimeMillis() - lastBehindLog > 5000) {
					lastBehindLog = System.currentTimeMillis();
					SkyCraft.LOG.warn("SkyCraft: the server had the player {} blocks behind the host; moved it there", String.format("%.1f", behind));
				}
				sp.setPos(tx, ty, tz);
			}
			sp.resetFallDistance();
			int damage = (int) Math.ceil(landed - 3.0F);
			if (damage > 0) {
				var level = sp.level();
				sp.hurtServer(level, level.damageSources().fall(), damage);
				SkyCraft.LOG.info("SkyCraft: landed like a {}-block fall: {} damage", String.format("%.1f", landed), damage);
			}
		});
	}

	/**
	 * OWCraft fork: what Minecraft's own movement would use, for the host's physics. Speed: the
	 * movement attribute against its base (sprint, Speed, Slowness) times the block's speed factor
	 * (soul sand, honey). Friction of the block underfoot (ice). Jump: strength plus Jump Boost,
	 * against Minecraft's plain 0.42.
	 */
	private static void movementContext(LocalPlayer player) {
		var speed = net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED;
		double base = player.getAttributeBaseValue(speed);
		double now = player.getAttributeValue(speed);
		var below = player.level().getBlockState(net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 0.5000001, player.getZ()));
		var at = player.level().getBlockState(player.blockPosition());
		float blockSpeed = at.getBlock().getSpeedFactor() != 1.0F ? at.getBlock().getSpeedFactor() : below.getBlock().getSpeedFactor();
		mc.moveFactor = (float) (base > 0 ? now / base : 1.0) * blockSpeed;
		mc.friction = below.isAir() ? 0.6F : below.getBlock().getFriction();
		double jump = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH) + player.getJumpBoostPower();
		mc.jumpFactor = (float) (jump / 0.42);
	}

	/** Called at the end of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		FrameTimings.tick();
		MirrorWorld.tick(minecraft);
		DiscordPresence.tick(minecraft);
		SkyDigClient.tick(minecraft);
		freezeWhileUnlinked(minecraft);
		holdUntilReady(minecraft);
		publishTick(minecraft);
	}

	/**
	 * Skyrim went quiet (a long loading screen, a stall, or it closed). Its collision around the
	 * player may be about to change (interior doors), so keep the player exactly where they were
	 * instead of letting them fall; Skyrim puts them where they belong when it's back.
	 */
	private static void freezeWhileUnlinked(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (linked || !tookOver || player == null) {
			return;
		}
		if (unlinkedHold == null) {
			unlinkedHold = player.position();
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(unlinkedHold.x, unlinkedHold.y, unlinkedHold.z);
		player.xo = unlinkedHold.x;
		player.yo = unlinkedHold.y;
		player.zo = unlinkedHold.z;
		player.resetFallDistance();
	}

	/**
	 * Hands Skyrim the raw physics tick (previous + latest feet, smoothed eye height, walk bob) with a
	 * QueryPerformanceCounter timestamp. Skyrim interpolates between them on its own frame clock,
	 * exactly like Minecraft's renderer does with partial ticks.
	 */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (qpcFreq == 0) {
			qpcFreq = SkyLink.qpcFrequency();
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (DeltaTracker keeps the remainder).
		float remainder = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		mc.tickQpc = SkyLink.qpc() - (long) (remainder * tickMs * qpcFreq / 1000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.eyeHeightO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.eyeHeightT = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
		var avatar = player.avatarState();
		mc.walkDistO = bob ? avatar.getInterpolatedWalkDistance(0.0F) : 0.0F;
		mc.walkDist = bob ? avatar.getInterpolatedWalkDistance(1.0F) : 0.0F;
		mc.bobO = bob ? avatar.getInterpolatedBob(0.0F) : 0.0F;
		mc.bob = bob ? avatar.getInterpolatedBob(1.0F) : 0.0F;
		SkyLink.writeMcState(mc);
	}

	/** Freeze the player until Skyrim's collision around them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!sky.inGame() || sky.loading()) {
			// Skyrim is on its main menu or a loading screen: park the player where they are.
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos != null && sky.hostDrives() && sky.inGame() && !sky.loading()) {
			holdPos = null; // OWCraft fork: the host's physics holds the player up
		}
		if (holdPos == null) {
			holdSince = 0;
			return;
		}
		if (holdSince == 0) {
			holdSince = System.currentTimeMillis();
		}
		int bx = (int) Math.floor(holdPos.x), by = (int) Math.floor(holdPos.y), bz = (int) Math.floor(holdPos.z);
		boolean known = SkyCollision.isKnown(bx, by - 1, bz) && SkyCollision.isKnown(bx, by, bz)
			&& SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
		// Release once there is actual ground below (or after a timeout, e.g. when mid-air on purpose).
		boolean ready = known && (SkyCollision.hasSolidBelow(bx, by, bz, 12) || System.currentTimeMillis() - holdSince > 6000);
		if (ready && sky.inGame() && !sky.loading()) {
			// Skyrim's feet can sit a fraction of a voxel inside our ground layer. Minecraft's
			// collision never pushes you out of a shape, so you'd drop through: lift out first.
			Vec3 safe = liftOutOfGeometry(player, holdPos);
			if (safe.y != holdPos.y) {
				player.setPos(safe.x, safe.y, safe.z);
				player.yo = safe.y;
				SkyCraft.LOG.info("SkyCraft: lifted player {} blocks out of the ground", String.format("%.3f", safe.y - holdPos.y));
			}
			holdPos = null;
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.resetFallDistance();
	}

	private static Vec3 liftOutOfGeometry(LocalPlayer player, Vec3 pos) {
		// Stand on the exact Skyrim ground if it is slightly above the feet (up to 2.5 blocks).
		double ground = SkyCollider.groundAt(pos.x, pos.y, pos.z, 2.5);
		return !Double.isNaN(ground) && ground > pos.y ? new Vec3(pos.x, ground, pos.z) : pos;
	}

	/**
	 * OWCraft: a tile change while airborne (elytra, a jump, a fall). It's the same spot in Outer Wilds,
	 * so the player keeps its speed and flight: a relative teleport moves server and client by the
	 * same offset (no snap back to where the server last saw us) and leaves velocity alone. No hold
	 * either: in the air there's nothing to fall through, and waiting for ground below froze elytra
	 * flights for up to 6 s at every tile.
	 */
	private static void shiftPlayer(Minecraft minecraft, double dx, double dy, double dz) {
		var server = minecraft.getSingleplayerServer();
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) {
				sp.teleportTo(sp.level(), dx, dy, dz, Relative.ALL, 0f, 0f, false);
			}
		});
		SkyCraft.LOG.info("SkyCraft: tile shift by {} {} {}", String.format("%.1f", dx), String.format("%.1f", dy), String.format("%.1f", dz));
	}

	/** OWCraft: a tile change takes the mobs and items around the player along (see TileCarry). */
	private static void carryAround(Minecraft minecraft, Vec3 from, Vec3 to) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		float[] rot = sky.shiftRot.clone();
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) {
				dev.skycraft.world.TileCarry.carry(sp.level(), from, to, rot);
			}
		});
	}

	private static void requestTeleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		player.setPos(x, y, z);
		player.setDeltaMovement(Vec3.ZERO);
		lastDrivenDelta = Vec3.ZERO; // OWCraft fork: a teleport's stop isn't a push
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleportTo(x, y, z);
					sp.setYRot(yaw);
					sp.setXRot(pitch);
					sp.resetFallDistance();
				}
			});
		}
		SkyCraft.LOG.info("SkyCraft: teleported to {} {} {}", x, y, z);
	}

	/** After GameRenderer.render(): report the player to Skyrim and ship the overlay frame. */
	public static void afterRender() {
		if (!linked) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.mainCamera();
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) {
				flags |= Proto.MC_ON_GROUND;
			}
			if (player.isShiftKeyDown()) {
				flags |= Proto.MC_SNEAKING;
			}
			if (player.isSprinting()) {
				flags |= Proto.MC_SPRINTING;
			}
			if (player.isDeadOrDying()) {
				flags |= Proto.MC_DEAD;
			}
			if (player.isSwimming()) {
				flags |= Proto.MC_SWIMMING;
			}
			if (player.getAbilities().flying) {
				flags |= Proto.MC_FLYING;
			}
			// OWCraft: Creative and Spectator can't be hurt; the Outer Wilds body follows suit.
			if (player.getAbilities().invulnerable) {
				flags |= Proto.MC_INVULNERABLE;
			}
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			// The eye, not the camera: in third person Minecraft's camera sits behind or in front.
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.position();
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = camera.getFov();
			// Minecraft's F5 camera: Skyrim puts its camera where Minecraft's would be.
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			mc.cameraDistance = camera.isDetached() ? (float) camera.position().distanceTo(player.getEyePosition(partial)) : 0.0F;
			// Walk bob, exactly what GameRenderer.bobView() uses this frame.
			var entityState = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.entityRenderState;
			boolean bob = minecraft.options.bobView().get() && entityState.isPlayer;
			mc.bobPhase = bob ? entityState.backwardsInterpolatedWalkDistance : 0.0F;
			mc.bobAmount = bob ? entityState.bob : 0.0F;
		}
		if (minecraft.gui.screen() != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		if (driving()) {
			flags |= Proto.MC_HOST_DRIVEN;
			if (player != null && minecraft.level != null) {
				movementContext(player);
				if (player.onClimbable()) {
					flags |= Proto.MC_CLIMBING;
				}
				if (player.isInWater()) {
					flags |= Proto.MC_IN_WATER;
				}
				if (player.isInLava()) {
					flags |= Proto.MC_IN_LAVA;
				}
				if (player.isFallFlying()) {
					flags |= Proto.MC_FALL_FLYING;
				}
				if (player.tickCount <= rocketBoostUntil) {
					flags |= Proto.MC_ROCKET_BOOST;
				}
				if (serverBehind > 2.0F) {
					flags |= Proto.MC_SERVER_BEHIND;
				}
			}
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until we are released
		mc.guiScale = minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		SkyLink.writeMcState(mc);

		if ((flags & Proto.MC_IN_WORLD) != 0) {
			try {
				WorldExporter.frame(minecraft, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
			} catch (RuntimeException e) {
				if (exporterErrors++ < 5) {
					SkyCraft.LOG.error("SkyCraft: world export failed", e);
				}
			}
			long captureStart = System.nanoTime();
			FrameExporter.capture(minecraft);
			FrameTimings.add(FrameTimings.CAPTURE, captureStart);
		}
	}

	/** End of the frame: render at most once per Skyrim frame instead of spinning freely. */
	public static void paceFrame() {
		if (!linked) {
			return;
		}
		if (skyrimStalled && (SkyLink.skyStateSeq() >>> 1) == lastPacedSeq) {
			return; // Skyrim is paused (menu / alt-tab): don't block every frame waiting for it
		}
		skyrimStalled = false;
		FrameTimings.beginPace();
		long deadline = System.nanoTime() + 25_000_000L;
		// SkyState.seq advances by 2 per Skyrim frame (odd while writing).
		while ((SkyLink.skyStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = SkyLink.skyStateSeq() >>> 1;
		skyrimStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
		FrameTimings.endPace();
	}

	private static void applyLinkedOptions() {
		Minecraft minecraft = Minecraft.getInstance();
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		options.vignette().set(false);
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world itself; these only decide how far out placed blocks,
		// arrows and Skyrim NPC stand-ins stay loaded and simulated.
		options.renderDistance().set(8);
		options.simulationDistance().set(8);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		if (options.tutorialStep != net.minecraft.client.tutorial.TutorialSteps.NONE) {
			minecraft.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
		}
		options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
		SkyCraft.LOG.info("SkyCraft: game window hidden (run with -Dskycraft.showWindow=true to keep it)");
	}

	private static void applyViewportSize(Minecraft minecraft) {
		int w = Math.min(sky.viewportW, Proto.MAX_OVERLAY_W);
		int h = Math.min(sky.viewportH, Proto.MAX_OVERLAY_H);
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		// OWCraft fork: no title bar or border, so Windows lets the (hidden) window be as tall as the host's
		// screen. With them it was cut to the screen minus the taskbar (2560x1369 for 2560x1440), and the
		// host stretched Minecraft's screens to fit: the inventory looked blocky.
		long handle = minecraft.getWindow().handle();
		SDLVideo.SDL_SetWindowBordered(handle, false);
		minecraft.getWindow().setWindowed(w, h);
		SDLVideo.SDL_SetWindowBordered(handle, false);
		SkyCraft.LOG.info("SkyCraft: sizing overlay to Skyrim viewport {}x{}", w, h);
	}
}
