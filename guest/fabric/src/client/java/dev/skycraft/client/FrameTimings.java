package dev.skycraft.client;

import dev.skycraft.SkyCraft;

/**
 * OWCraft diagnostics: where Minecraft's frame time goes while linked, logged every 10 s.
 * work = runTick up to the pacing wait (ticks + render + export), wait = the pacing wait,
 * readback = the overlay copy, tick gap = time between client ticks (50 ms when on time).
 */
public final class FrameTimings {
	private static long frameStart, paceStart, lastTick, lastLog;
	private static int frames, lateTicks;
	private static double workSum, workWorst, waitSum, readbackWorst, tickGapWorst;
	public static final int TICK = 0, MESH = 1, ENTITIES = 2, AVATAR = 3, CAPTURE = 4;
	private static final String[] STAGES = {"ticks", "section meshing", "entities", "avatar", "overlay capture"};
	private static final double[] stageSum = new double[STAGES.length];
	private static final double[] frameStage = new double[STAGES.length];
	private static long tickStart;
	// OWCraft fork: the 3 s after a teleport (a tile change), where the hitch goes.
	private static long watchStart;
	private static double watchWorst, chunkMs;
	private static String watchWorstParts = "";
	private static int watchSlow;

	private FrameTimings() {
	}

	public static void beginFrame() {
		long now = System.nanoTime();
		frameStart = now;
		java.util.Arrays.fill(frameStage, 0);
		if (lastLog == 0) {
			lastLog = now;
		}
		if (now - lastLog > 10_000_000_000L && frames > 0) {
			SkyCraft.LOG.info("SkyCraft: {} frames in 10 s, work {} ms avg / {} ms worst, pacing wait {} ms avg, overlay readback {} ms worst, tick gap {} ms worst ({} ticks over 60 ms)",
				frames, f(workSum / frames), f(workWorst), f(waitSum / frames), f(readbackWorst), f(tickGapWorst), lateTicks);
			StringBuilder parts = new StringBuilder("SkyCraft: per frame,");
			for (int i = 0; i < STAGES.length; i++) {
				parts.append(' ').append(STAGES[i]).append(' ').append(f(stageSum[i] / frames)).append(" ms,");
				stageSum[i] = 0;
			}
			parts.append(" the rest is Minecraft's own frame (render, chunk and network work)");
			SkyCraft.LOG.info(parts.toString());
			frames = lateTicks = 0;
			workSum = workWorst = waitSum = readbackWorst = tickGapWorst = 0;
			lastLog = now;
		}
	}

	public static void beginPace() {
		paceStart = System.nanoTime();
		if (frameStart == 0) {
			return;
		}
		double work = (paceStart - frameStart) / 1e6;
		workSum += work;
		workWorst = Math.max(workWorst, work);
		frames++;
		if (watchStart != 0) {
			watchFrame(work);
		}
	}

	public static void watchTileChange() {
		watchStart = System.nanoTime();
		watchWorst = watchSlow = 0;
		chunkMs = -1;
		watchWorstParts = "";
	}

	private static void watchFrame(double work) {
		double since = (System.nanoTime() - watchStart) / 1e6;
		if (work > 100) {
			watchSlow++;
		}
		if (work > watchWorst) {
			watchWorst = work;
			StringBuilder parts = new StringBuilder();
			for (int i = 0; i < STAGES.length; i++) {
				parts.append(STAGES[i]).append(' ').append(f(frameStage[i])).append(", ");
			}
			watchWorstParts = parts.append("the rest ").append(f(work - java.util.Arrays.stream(frameStage).sum())).toString();
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (chunkMs < 0 && mc.player != null && mc.level != null
			&& mc.level.getChunkSource().hasChunk(mc.player.chunkPosition().x(), mc.player.chunkPosition().z())) {
			chunkMs = since;
		}
		if (since > 3000) {
			watchStart = 0;
			var server = mc.getSingleplayerServer();
			long serverWorst = 0;
			if (server != null) {
				for (long t : server.getTickTimesNanos()) {
					serverWorst = Math.max(serverWorst, t);
				}
			}
			SkyCraft.LOG.info("SkyCraft: after the teleport, the chunk underfoot arrived in {} ms; worst frame {} ms ({}); {} frames over 100 ms; server's worst tick {} ms",
				chunkMs < 0 ? "more than 3000" : f(chunkMs), f(watchWorst), watchWorstParts, watchSlow, f(serverWorst / 1e6));
		}
	}

	public static void endPace() {
		if (paceStart != 0) {
			waitSum += (System.nanoTime() - paceStart) / 1e6;
		}
	}

	public static void tickStart() {
		tickStart = System.nanoTime();
	}

	public static void add(int stage, long startNanos) {
		double ms = (System.nanoTime() - startNanos) / 1e6;
		stageSum[stage] += ms;
		frameStage[stage] += ms;
	}

	public static void tick() {
		if (tickStart != 0) {
			add(TICK, tickStart);
		}
		long now = System.nanoTime();
		if (lastTick != 0) {
			double gap = (now - lastTick) / 1e6;
			tickGapWorst = Math.max(tickGapWorst, gap);
			if (gap > 60) {
				lateTicks++;
			}
		}
		lastTick = now;
	}

	public static void readback(long startNanos) {
		readbackWorst = Math.max(readbackWorst, (System.nanoTime() - startNanos) / 1e6);
	}

	private static String f(double v) {
		return String.format("%.1f", v);
	}
}
