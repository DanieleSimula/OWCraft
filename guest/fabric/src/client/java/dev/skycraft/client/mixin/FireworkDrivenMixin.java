package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OWCraft fork: while the host moves the player, a rocket's pull on a gliding player has to reach the
 * host's body, so the state says "pulling now" and the host applies Minecraft's formula itself. It
 * starts from the click, not from the rocket entity: after a tile change the server sends the new
 * chunks a few at a time, and the rocket reached the client up to 3 s late. A rocket lasts
 * 10 x (flight + 1) ticks plus 0..11 at random; the middle of that is close enough.
 */
@Mixin(FireworkRocketItem.class)
public abstract class FireworkDrivenMixin {
	@Inject(method = "use", at = @At("HEAD"))
	private void skycraft$rocketPullsHost(Level level, Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (player instanceof LocalPlayer && SkyClient.driving() && player.isFallFlying()) {
			var fireworks = player.getItemInHand(hand).get(DataComponents.FIREWORKS);
			int flight = fireworks != null ? fireworks.flightDuration() : 0;
			SkyClient.rocketBoostUntil = player.tickCount + 10 * (flight + 1) + 5;
		}
	}
}
