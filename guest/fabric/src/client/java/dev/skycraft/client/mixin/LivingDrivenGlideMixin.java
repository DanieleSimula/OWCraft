package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OWCraft fork: while the host moves the player, the host glides (its own copy of the elytra's
 * steering). Minecraft's would change the motion every tick on top, and the push detector
 * (EntityDrivenMoveMixin) passed those changes to the host as pushes: a jerky glide. Left alone, the
 * motion changes only by real pushes (a firework, a hit).
 */
@Mixin(LivingEntity.class)
public abstract class LivingDrivenGlideMixin {
	@Inject(method = "updateFallFlyingMovement", at = @At("HEAD"), cancellable = true, require = 0)
	private void skycraft$hostGlides(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof LocalPlayer && SkyClient.driving()) {
			cir.setReturnValue(movement);
		}
	}
}
