package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OWCraft fork: while the host moves the player (see SkyClient.driving), Minecraft's own idea of
 * "on the ground" comes from the host too. The move packet carries it, and the server works out
 * fall damage and hunger from it, as it does for any client.
 */
@Mixin(Entity.class)
public abstract class EntityDrivenMoveMixin {
	@Inject(method = "move", at = @At("RETURN"))
	private void skycraft$hostSaysGrounded(MoverType type, Vec3 movement, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player && SkyClient.driving()) {
			player.setOnGround(SkyClient.sky().hostOnGround());
			player.setDeltaMovement(player.getX() - player.xo, player.getY() - player.yo, player.getZ() - player.zo);
			SkyClient.lastDrivenDelta = player.getDeltaMovement();
		}
	}

	// OWCraft fork: no generic push detector here any more. Comparing Minecraft's motion with the
	// host's turned gravity, landings and tile changes into pushes of up to 60 m/s; pushes are to come
	// back one source at a time (knockback, explosions, fireworks), each from its own hook.
}
