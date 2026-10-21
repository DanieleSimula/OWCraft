package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void skycraft$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.beginFrame();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER)
	)
	private void skycraft$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.afterRender();
	}

	/**
	 * OWCraft: while linked nobody looks at Minecraft's own (hidden) window; only the overlay copy is
	 * used. Presenting to it made the driver hold each frame to about 40 ms (25 fps). Without a
	 * surface texture Minecraft skips the blit and present, as it does for a minimized window.
	 * -Dskycraft.present=true keeps presenting.
	 */
	@Redirect(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V")
	)
	private void skycraft$skipPresent(com.mojang.renderpearl.api.device.GpuSurface surface) throws Exception {
		if (SkyClient.linked() && !PRESENT) {
			return;
		}
		surface.acquireNextTexture();
	}

	private static final boolean PRESENT = Boolean.getBoolean("skycraft.present");

	@Inject(method = "renderFrame", at = @At("TAIL"))
	private void skycraft$pace(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.paceFrame();
	}
}
