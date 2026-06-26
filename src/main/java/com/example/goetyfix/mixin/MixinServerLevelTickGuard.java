package com.example.goetyfix.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

/**
 * Last-resort fix for GoetyAwaken TrackingFireball ClassCastException crash.
 *
 * All previous injection approaches failed because other mods (majruszlibrary,
 * entityjs, etc.) modify Projectile.onHit's bytecode, removing our injection targets.
 *
 * This approach: Redirect the Consumer.accept() call inside Level.guardEntityTick
 * (m_46653_) to wrap entity tick execution with a ClassCastException catch.
 * If the crashing entity is a TrackingFireball, silently discard it instead of
 * crashing the entire server/client.
 *
 * guardEntityTick is the outermost vanilla try-catch wrapper for ALL entity ticks.
 * Vanilla catches Throwable here and escalates to a CrashReport + ReportedException.
 * By intercepting ClassCastException before vanilla's handler, we prevent the crash.
 */
@Mixin(Level.class)
public abstract class MixinServerLevelTickGuard {

    @SuppressWarnings("unchecked")
    @Redirect(
        method = "m_46653_",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"
        ),
        remap = false
    )
    private void goetyfix$guardEntityTick(Consumer<Entity> consumer, Object entityRaw) {
        try {
            consumer.accept((Entity) entityRaw);
        } catch (ClassCastException e) {
            // Only suppress ClassCastException for known problematic entities
            if (entityRaw instanceof Entity entity
                    && entity.getClass().getName().contains("TrackingFireball")) {
                entity.discard();
            } else {
                throw e;
            }
        }
    }
}
