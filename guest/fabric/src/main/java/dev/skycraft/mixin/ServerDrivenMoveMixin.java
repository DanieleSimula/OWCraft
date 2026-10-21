package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.skycraft.link.SkyLink;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * OWCraft fork: while the host moves the player, its collision (Outer Wilds' exact ground) and
 * Minecraft's voxel copy of it disagree by a fraction of a block. The server would call that
 * "colliding with something new" and pull the player back, every few ticks. Optional: if a
 * Minecraft version renames the check, this simply doesn't apply.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerDrivenMoveMixin {
	@ModifyReturnValue(method = "isPlayerCollidingWithAnythingNew", at = @At("RETURN"), require = 0)
	private boolean skycraft$hostKnowsBest(boolean original) {
		return original && !SkyLink.hostDrivesPlayer;
	}
}
