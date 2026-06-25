package com.example.goetyfix.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ClassCastException crash in GoetyAwaken's TrackingFireball.
 *
 * Previous approaches failed:
 * - v1.6.0/v1.7.0: @Pseudo Mixin on TrackingFireball.m_5790_ never actually injected
 *   (remap/obfuscation mismatch with internal test builds)
 * - v1.8.0: ProjectileImpactEvent handler never fired (likely bypassed by another
 *   mod's Mixin on Projectile or by Goety's custom hitDetection path)
 *
 * This approach: Inject directly into vanilla Projectile.onHit (m_6532_) BEFORE the
 * call to onHitEntity (m_5790_). Since Projectile is a vanilla class, the refmap
 * works correctly and injection is reliable. If the hit entity is not a LivingEntity
 * and the projectile is a TrackingFireball, cancel the method to prevent the crash.
 */
@Mixin(Projectile.class)
public abstract class MixinProjectileHitGuard {

    @Inject(
        method = "m_6532_(Lnet/minecraft/world/phys/HitResult;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/Projectile;m_5790_(Lnet/minecraft/world/phys/EntityHitResult;)V"
        ),
        cancellable = true,
        remap = false
    )
    private void goetyfix$guardTrackingFireballCast(HitResult result, CallbackInfo ci) {
        try {
            if (!(result instanceof EntityHitResult ehr)) return;
            if (ehr.getEntity() instanceof LivingEntity) return;

            // Only cancel for TrackingFireball (avoid affecting other projectiles)
            String className = this.getClass().getName();
            if (className.contains("TrackingFireball")) {
                ci.cancel();
            }
        } catch (Exception ignored) {}
    }
}
