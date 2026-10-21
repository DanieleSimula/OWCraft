package dev.skycraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import net.minecraft.client.Minecraft;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background) back
 * from the GPU and publishes it to Skyrim through the overlay triple buffer.
 *
 * The copy is asynchronous: a frame is captured into one of a few staging buffers and shipped
 * once the GPU says the copy finished, typically a frame later.
 */
public final class FrameExporter {
	private static final int STAGING = 3;
	private static final int FREE = 0;
	private static final int PENDING = 1;
	private static final int READY = 2;

	private static final Staging[] staging = new Staging[STAGING];
	private static long nextFrameId = 1;
	private static long lastShippedId;
	/**
	 * OWCraft: read the frame back right away instead of a couple of frames later. On OpenGL, mapping
	 * the buffer just after glReadPixels waits for the copy, which costs little here (Minecraft only
	 * draws the hand and the 2D layer) and keeps screens such as the inventory in step with the mouse.
	 * -Dskycraft.syncOverlay=false goes back to the asynchronous copy.
	 */
	private static final boolean SYNC = !"false".equals(System.getProperty("skycraft.syncOverlay"));
	private static Staging syncSlot;
	private static boolean loggedFormat;

	private static final class Staging {
		GpuBuffer buffer;
		int width;
		int height;
		volatile int state = FREE;
		long frameId;
	}

	private FrameExporter() {
	}

	public static void capture(Minecraft minecraft) {
		// Waiting for the copy costs Minecraft frame time: only worth it while a screen follows the mouse.
		boolean sync = SYNC && minecraft.gui.screen() != null;
		if (!sync) {
			shipReadyFrames();
		}

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = target.getColorTexture();
		if (color == null) {
			return;
		}
		int width = target.width;
		int height = target.height;
		if (width > Proto.MAX_OVERLAY_W || height > Proto.MAX_OVERLAY_H) {
			return;
		}
		if (!loggedFormat) {
			loggedFormat = true;
			SkyCraft.LOG.info("SkyCraft: overlay capture {}x{} format {}", width, height, color.getFormat());
		}

		if (sync) {
			captureNow(color, width, height);
			return;
		}

		Staging slot = null;
		for (Staging s : staging) {
			if (s != null && s.state == FREE) {
				slot = s;
				break;
			}
		}
		if (slot == null) {
			for (int i = 0; i < STAGING; i++) {
				if (staging[i] == null) {
					staging[i] = slot = new Staging();
					break;
				}
			}
		}
		if (slot == null) {
			return; // all staging buffers still in flight; skip this frame
		}

		long bytes = (long) width * height * 4L;
		if (slot.buffer == null || slot.width != width || slot.height != height) {
			if (slot.buffer != null) {
				slot.buffer.close();
			}
			slot.buffer = RenderSystem.getDevice().createBuffer(() -> "SkyCraft overlay readback", 9, bytes);
			slot.width = width;
			slot.height = height;
		}
		final Staging captured = slot;
		captured.state = PENDING;
		captured.frameId = nextFrameId++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L, () -> captured.state = READY, 0);
	}

	/** Copies this frame and ships it before returning. */
	private static void captureNow(GpuTexture color, int width, int height) {
		long bytes = (long) width * height * 4L;
		if (syncSlot == null) {
			syncSlot = new Staging();
		}
		if (syncSlot.buffer == null || syncSlot.width != width || syncSlot.height != height) {
			if (syncSlot.buffer != null) {
				syncSlot.buffer.close();
			}
			syncSlot.buffer = RenderSystem.getDevice().createBuffer(() -> "SkyCraft overlay readback", 9, bytes);
			syncSlot.width = width;
			syncSlot.height = height;
		}
		syncSlot.frameId = nextFrameId++;
		long start = System.nanoTime();
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, syncSlot.buffer, 0L, () -> {
		}, 0);
		ship(syncSlot);
		FrameTimings.readback(start);
	}

	private static void ship(Staging frame) {
		MemorySegment shm = SkyLink.segment();
		if (shm == null) {
			return;
		}
		long bytes = (long) frame.width * frame.height * 4L;
		try (GpuBufferSlice.MappedView view = frame.buffer.map(true, false)) {
			MemorySegment src = MemorySegment.ofBuffer(view.data());
			MemorySegment.copy(src, 0, shm, SkyLink.overlayBackSlotOffset(), Math.min(bytes, src.byteSize()));
		}
		SkyLink.publishOverlay(frame.width, frame.height, true, frame.frameId);
		lastShippedId = frame.frameId;
	}

	/** Maps the newest finished readback and copies it into shared memory. */
	private static void shipReadyFrames() {
		Staging newest = null;
		for (Staging s : staging) {
			if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) {
				newest = s;
			}
		}
		if (newest == null) {
			return;
		}
		if (newest.frameId > lastShippedId) {
			ship(newest);
		}
		// Anything older than what we just shipped is useless now.
		for (Staging s : staging) {
			if (s != null && s.state == READY && s.frameId <= newest.frameId) {
				s.state = FREE;
			}
		}
	}
}
