package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import dev.skycraft.client.SkyCollider;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * After vanilla has collided the local player's movement with Minecraft blocks, collide it with
 * Skyrim's exact triangles (smooth slopes instead of voxel stair-steps).
 */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void skycraft$smoothSkyrimCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this instanceof LocalPlayer player && SkyClient.driving()) {
			// OWCraft fork: the host moved the player; this move goes exactly there.
			Vec3 to = SkyClient.drivenTarget();
			cir.setReturnValue(new Vec3(to.x - player.getX(), to.y - player.getY(), to.z - player.getZ()));
			return;
		}
		if ((Object) this instanceof LocalPlayer player && SkyClient.linked() && !player.noPhysics) {
			cir.setReturnValue(SkyCollider.collide(player, cir.getReturnValue()));
		}
	}
}
