package com.example.goetyfix.mixin;

import com.Polarice3.Goety.common.entities.boss.Apostle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a bug where the Apostle could be teleported to another dimension
 * when its tracking target enters a portal during the same tick.
 *
 * Two injection points:
 * 1. teleportTowards(Entity) - cancel if target is in a different dimension
 * 2. teleport() - skip using target's Y coordinate if target is in a different dimension
 */
@Mixin(value = Apostle.class, remap = false)
public abstract class ApostleTeleportMixin {

    /**
     * Inject at the head of teleportTowards to prevent cross-dimension teleport tracking.
     * If the target entity is in a different level (dimension) than the Apostle,
     * cancel the method entirely.
     */
    @Inject(method = "teleportTowards", at = @At("HEAD"), cancellable = true)
    private void goetyfix$checkDimensionBeforeTeleportTowards(Entity target, CallbackInfo ci) {
        Apostle self = (Apostle) (Object) this;
        if (self.level() != target.level()) {
            ci.cancel();
        }
    }

    /**
     * Redirect the getY() call on the target (from getTarget()) inside teleport().
     * If the target is in a different dimension, return the Apostle's own Y instead.
     *
     * Original code in teleport():
     *   double y = this.getY();
     *   if (this.getTarget() != null) {
     *       y = this.getTarget().getY();  // <-- this call is redirected
     *   }
     *
     * The redirect target is the first invocation of LivingEntity.getY() (m_20186_)
     * inside the teleport() method, which corresponds to getTarget().getY().
     */
    @Redirect(
        method = "teleport",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;m_20186_()D",
            ordinal = 0
        )
    )
    private double goetyfix$redirectTargetGetY(LivingEntity target) {
        Apostle self = (Apostle) (Object) this;
        if (target.level() != self.level()) {
            return self.getY();
        }
        return target.getY();
    }
}
