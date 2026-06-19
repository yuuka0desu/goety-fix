package com.example.goetyfix.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents a crash in GoetyAwaken's TrackingFireball.
 *
 * Root cause: TrackingFireball.onHitEntity (m_5790_) unconditionally casts the
 * hit entity to LivingEntity. When the fireball strikes a non-living entity
 * (e.g. another projectile / fireball), a ClassCastException crashes the server
 * during the entity tick:
 *   java.lang.ClassCastException: TrackingFireball cannot be cast to LivingEntity
 *
 * Fix: Inject at the head of onHitEntity and cancel the call when the hit entity
 * is not a LivingEntity, so the unsafe cast is never reached. This is targeted by
 * class name string so this mod does not need GoetyAwaken at compile time, and the
 * mixin is silently ignored if the class is absent.
 */
@Pseudo
@Mixin(targets = "com.k1sak1.goetyawaken.common.entities.projectiles.TrackingFireball", remap = false)
public abstract class MixinTrackingFireball {

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true, remap = true, require = 0, expect = 0)
    private void goetyfix$guardNonLivingHit(EntityHitResult result, CallbackInfo ci) {
        try {
            if (result == null || !(result.getEntity() instanceof LivingEntity)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // Never let the guard itself crash the tick
            ci.cancel();
        }
    }
}
