package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * OWCraft: carries the mobs and items around the player across a tile change.
 *
 * Each patch of an Outer Wilds planet has its own area of the Minecraft world, 512 blocks from the
 * next. A tile change moves the player there; whatever was nearby stayed behind, lost its ground
 * (the collision is cleared) and despawned at that distance. So they move too: same offset from
 * the player, turned by the angle between the two tiles, then held still until the new tile's
 * collision under them has arrived.
 *
 * Further away there's no planet ground at all (it only streams in around the player): mobs and
 * items out there stay where they are instead of falling into the void.
 */
public final class TileCarry {
	private static final double RADIUS = 48;
	private static final long HOLD_MS = 4000;
	// The two tiles' 1/8-block collision grids don't line up: start a little above the ground.
	private static final double LIFT = 0.25;

	private record Held(Entity entity, Vec3 pos, long since) {
	}

	private static final List<Held> held = new ArrayList<>();
	private TileCarry() {
	}

	/** At startup. */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(TileCarry::tick);
	}

	/**
	 * Server thread. Moves entities within RADIUS of {@code from} (old tile) to around {@code to}
	 * (new tile); {@code rot} is the row-major rotation between the tiles' axes (all zero: none).
	 */
	public static void carry(ServerLevel level, Vec3 from, Vec3 to, float[] rot) {
		float[] r = rot;
		if (r[0] == 0f && r[4] == 0f && r[8] == 0f) {
			r = new float[] { 1, 0, 0, 0, 1, 0, 0, 0, 1 };
		}
		long now = System.currentTimeMillis();
		int moved = 0;
		var box = new AABB(from, from).inflate(RADIUS);
		for (Entity e : level.getEntities((Entity) null, box, e -> !(e instanceof Player) && !e.isPassenger() && e.isAlive())) {
			Vec3 d = e.position().subtract(from);
			double x = to.x + r[0] * d.x + r[1] * d.y + r[2] * d.z;
			double y = to.y + r[3] * d.x + r[4] * d.y + r[5] * d.z + LIFT;
			double z = to.z + r[6] * d.x + r[7] * d.y + r[8] * d.z;
			// Minecraft's forward for a yaw is (-sin, 0, cos): turn it with the tiles.
			double yaw = Math.toRadians(e.getYRot());
			double fx = -Math.sin(yaw), fz = Math.cos(yaw);
			double gx = r[0] * fx + r[2] * fz, gz = r[6] * fx + r[8] * fz;
			float newYaw = (float) Math.toDegrees(Math.atan2(-gx, gz));
			e.teleportTo(level, x, y, z, Set.of(), newYaw, e.getXRot(), false);
			e.setDeltaMovement(Vec3.ZERO);
			e.setYHeadRot(newYaw);
			held.add(new Held(e, new Vec3(x, y, z), now));
			moved++;
		}
		if (moved > 0) {
			SkyCraft.LOG.info("SkyCraft: carried {} entities to the new tile", moved);
		}
	}

	private static void tick(MinecraftServer server) {
		if (SkyCollision.active()) {
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity e : level.getAllEntities()) {
					if ((e instanceof LivingEntity || e instanceof ItemEntity) && !(e instanceof Player) && !e.isPassenger()
						&& !e.onGround() && !e.isNoGravity() && offTheMap(e)) {
						e.setDeltaMovement(Vec3.ZERO);
						e.setPos(e.xo, e.yo, e.zo);
						e.resetFallDistance();
					}
				}
			}
		}
		if (held.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		held.removeIf(h -> {
			Entity e = h.entity();
			if (e.isRemoved() || now - h.since() > HOLD_MS) {
				return true;
			}
			int bx = (int) Math.floor(h.pos().x), by = (int) Math.floor(h.pos().y), bz = (int) Math.floor(h.pos().z);
			if (SkyCollision.isKnown(bx, by, bz) && SkyCollision.isKnown(bx, by - 1, bz)
				&& SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz)) {
				return true;
			}
			e.setDeltaMovement(Vec3.ZERO);
			e.setPos(h.pos().x, h.pos().y, h.pos().z);
			e.resetFallDistance();
			return false;
		});
	}

	/** No planet collision here or just below: nothing to land on yet. */
	private static boolean offTheMap(Entity e) {
		int bx = (int) Math.floor(e.getX()), by = (int) Math.floor(e.getY()), bz = (int) Math.floor(e.getZ());
		return !SkyCollision.isKnown(bx, by, bz) || !SkyCollision.isKnown(bx, by - SkyCollision.REGION_SIZE, bz);
	}
}
