package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OWCraft: mobs path over the planet's surface. Pathfinding only looks at blocks, and the planet
 * ground is air to it, so mobs found no floor anywhere and stood still. Air under a planet surface
 * counts as solid here. Not cached: the surface streams in after the mob may have looked.
 */
@Mixin(PathfindingContext.class)
public abstract class PathfindingContextMixin {
	@Shadow
	@Final
	private CollisionGetter level;

	@Inject(method = "getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;", at = @At("HEAD"), cancellable = true)
	private void skycraft$planetGround(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
		if (!SkyCollision.active()) {
			return;
		}
		BlockPos pos = new BlockPos(x, y, z);
		if (level.getBlockState(pos).isAir()) {
			cir.setReturnValue(SkyCollision.supportsFromBelow(pos.above()) ? PathType.BLOCKED : PathType.OPEN);
		}
	}
}
