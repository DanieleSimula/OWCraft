package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OWCraft fork: while the host moves the player, the host jumps. Minecraft's own jump would set its
 * motion up by 0.42 blocks a tick, and the push detector (EntityDrivenMoveMixin) would pass that to
 * the host as a push on top of the host's jump: jumps of several metres.
 */
@Mixin(LivingEntity.class)
public abstract class LivingDrivenJumpMixin {
	@Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true, require = 0)
	private void skycraft$hostJumps(CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer && SkyClient.driving()) {
			ci.cancel();
		}
	}
}
