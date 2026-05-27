package com.example.goetyfix;

import com.Polarice3.Goety.common.entities.ally.Summoned;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * Fixes ServerPlayer memory leaks caused by Goety/GoetyAwaken mods.
 *
 * Root cause: When a player respawns or changes dimension, Minecraft creates a new
 * ServerPlayer instance. The old ServerPlayer should be GC'd, but Summoned entities
 * hold direct references to it via their commandPosEntity and priorityTarget fields.
 * This prevents the old ServerPlayer (and its associated chunks) from being collected.
 *
 * Fix: On PlayerEvent.Clone and PlayerLoggedOut, iterate all Summoned entities and
 * update/clear stale player references. Also periodically clean up static maps in
 * GoetyAwaken that hold Entity references without cleanup.
 */
public class PlayerLeakFixer {

    private static final Logger LOGGER = LogManager.getLogger("GoetyFix");
    private static int tickCounter = 0;

    // Cached reflection fields (resolved once, reused)
    private static Field lastMoneyAmountsField;
    private static Field enhancedEntitiesField;
    private static Field entityUpgradeDataField;
    private static Field killDataAttackingPlayerField;
    private static Class<?> animationSummonClass;
    private static boolean reflectionInitialized = false;

    /**
     * When a player respawns or returns from the End, the old ServerPlayer is replaced.
     * Update all Summoned entities that reference the old player to point to the new one.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerClone(PlayerEvent.Clone event) {
        Player newPlayer = event.getEntity();
        Player oldPlayer = event.getOriginal();
        MinecraftServer server = newPlayer.getServer();
        if (server == null) return;

        initReflection();
        int updated = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Summoned summoned) {
                    if (summoned.commandPosEntity == oldPlayer) {
                        summoned.commandPosEntity = (LivingEntity) newPlayer;
                        updated++;
                    }
                    if (summoned.getPriorityTarget() == oldPlayer) {
                        summoned.setPriorityTarget(null);
                        updated++;
                    }
                }
                // Clear goety_cataclysm AnimationSummon.killDataAttackingPlayer
                updated += clearKillDataAttackingPlayer(entity, oldPlayer);
            }
        }

        if (updated > 0) {
            LOGGER.debug("PlayerClone: Updated {} stale references for player {}",
                    updated, newPlayer.getName().getString());
        }
    }

    /**
     * When a player logs out, clear all Summoned entity fields that reference them.
     * Also clear vanilla Mob target and LivingEntity.lastHurtByPlayer references.
     * This ensures the ServerPlayer object can be garbage collected.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        initReflection();
        int cleared = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Summoned summoned) {
                    if (summoned.commandPosEntity == player) {
                        summoned.commandPosEntity = null;
                        cleared++;
                    }
                    if (summoned.getPriorityTarget() == player) {
                        summoned.setPriorityTarget(null);
                        cleared++;
                    }
                }
                // Clear vanilla Mob.target if it references the logging-out player
                if (entity instanceof Mob mob) {
                    if (mob.getTarget() == player) {
                        mob.setTarget(null);
                        cleared++;
                    }
                }
                // Clear LivingEntity.lastHurtByMob (vanilla field that holds Player reference)
                if (entity instanceof LivingEntity living) {
                    if (living.getLastHurtByMob() == player) {
                        living.setLastHurtByMob(null);
                        cleared++;
                    }
                }
                // Clear goety_cataclysm AnimationSummon.killDataAttackingPlayer
                cleared += clearKillDataAttackingPlayer(entity, player);
                // Clear caster/owner fields on projectiles via reflection
                cleared += clearCasterField(entity, player);
            }
        }

        if (cleared > 0) {
            LOGGER.debug("PlayerLogout: Cleared {} stale references for player {}",
                    cleared, player.getName().getString());
        }
    }

    /**
     * Periodic cleanup (every 600 ticks = 30 seconds):
     * - Remove entries from SpecialServantEvents.lastMoneyAmounts where the Entity key is removed
     * - Clear EnhancedEntityEvents.enhancedEntities if it grows too large
     * - Scan for any Summoned entities with stale commandPosEntity references
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < 600) return;
        tickCounter = 0;

        cleanupStaticMaps();
        cleanupStaleReferences(event.getServer());
    }

    /**
     * Scan all Summoned entities and clear stale references to removed entities.
     * Also handles goety_cataclysm AnimationSummon.killDataAttackingPlayer.
     */
    private static void cleanupStaleReferences(MinecraftServer server) {
        if (server == null) return;

        initReflection();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Summoned summoned) {
                    LivingEntity cmdEntity = summoned.commandPosEntity;
                    if (cmdEntity != null && cmdEntity.isRemoved()) {
                        summoned.commandPosEntity = null;
                    }
                    LivingEntity priorityTarget = summoned.getPriorityTarget();
                    if (priorityTarget != null && priorityTarget.isRemoved()) {
                        summoned.setPriorityTarget(null);
                    }
                }
                // Clear killDataAttackingPlayer if the player is removed
                clearKillDataIfRemoved(entity);
                // Clear caster/owner fields if the referenced entity is removed
                clearCasterIfRemoved(entity);
            }
        }
    }

    /**
     * Clear AnimationSummon.killDataAttackingPlayer if it matches the given player.
     */
    private static int clearKillDataAttackingPlayer(Entity entity, Player player) {
        if (killDataAttackingPlayerField == null || animationSummonClass == null) return 0;
        if (!animationSummonClass.isInstance(entity)) return 0;
        try {
            Object value = killDataAttackingPlayerField.get(entity);
            if (value == player) {
                killDataAttackingPlayerField.set(entity, null);
                return 1;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Clear AnimationSummon.killDataAttackingPlayer if the referenced player is removed.
     */
    private static void clearKillDataIfRemoved(Entity entity) {
        if (killDataAttackingPlayerField == null || animationSummonClass == null) return;
        if (!animationSummonClass.isInstance(entity)) return;
        try {
            Object value = killDataAttackingPlayerField.get(entity);
            if (value instanceof Player p && p.isRemoved()) {
                killDataAttackingPlayerField.set(entity, null);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Clear caster/owner LivingEntity fields on projectile entities if they match the player.
     * Uses reflection to handle various goety_cataclysm projectile classes.
     */
    private static int clearCasterField(Entity entity, Player player) {
        int cleared = 0;
        Class<?> clazz = entity.getClass();
        // Try common field names used by goety_cataclysm projectiles
        for (String fieldName : new String[]{"caster", "owner", "finalTarget"}) {
            try {
                Field field = findFieldInHierarchy(clazz, fieldName);
                if (field == null) continue;
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value == player) {
                    field.set(entity, null);
                    cleared++;
                }
            } catch (Exception ignored) {}
        }
        return cleared;
    }

    /**
     * Clear caster/owner fields on projectile entities if the referenced entity is removed.
     */
    private static void clearCasterIfRemoved(Entity entity) {
        if (entity instanceof Summoned) return; // Already handled above
        Class<?> clazz = entity.getClass();
        String pkg = clazz.getName();
        // Only process goety_cataclysm projectile/util entities
        if (!pkg.contains("goety_cataclysm")) return;
        for (String fieldName : new String[]{"caster", "owner", "finalTarget"}) {
            try {
                Field field = findFieldInHierarchy(clazz, fieldName);
                if (field == null) continue;
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value instanceof LivingEntity le && le.isRemoved()) {
                    field.set(entity, null);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Find a field by name in the class hierarchy.
     */
    private static Field findFieldInHierarchy(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Use reflection to clean up static maps in GoetyAwaken that can leak entity references.
     */
    @SuppressWarnings("unchecked")
    private static void cleanupStaticMaps() {
        initReflection();

        // Clean SpecialServantEvents.lastMoneyAmounts
        if (lastMoneyAmountsField != null) {
            try {
                Map<Entity, Integer> map = (Map<Entity, Integer>) lastMoneyAmountsField.get(null);
                if (map != null && !map.isEmpty()) {
                    int before = map.size();
                    map.entrySet().removeIf(entry ->
                            entry.getKey() == null || entry.getKey().isRemoved()
                    );
                    int removed = before - map.size();
                    if (removed > 0) {
                        LOGGER.debug("Cleaned {} stale entries from SpecialServantEvents.lastMoneyAmounts", removed);
                    }
                }
            } catch (Exception e) {
                // Ignore - field access may fail in some environments
            }
        }

        // Clean EnhancedEntityEvents.enhancedEntities (prevent unbounded growth)
        if (enhancedEntitiesField != null) {
            try {
                Set<Integer> set = (Set<Integer>) enhancedEntitiesField.get(null);
                if (set != null && set.size() > 10000) {
                    set.clear();
                    LOGGER.debug("Cleared EnhancedEntityEvents.enhancedEntities (exceeded 10000 entries)");
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        // Clean ApostleUpgradeManager.entityUpgradeData (ConcurrentHashMap<LivingEntity, ...>)
        if (entityUpgradeDataField != null) {
            try {
                Map<LivingEntity, ?> upgradeMap = (Map<LivingEntity, ?>) entityUpgradeDataField.get(null);
                if (upgradeMap != null && !upgradeMap.isEmpty()) {
                    int before = upgradeMap.size();
                    upgradeMap.entrySet().removeIf(entry ->
                            entry.getKey() == null || entry.getKey().isRemoved()
                    );
                    int removed = before - upgradeMap.size();
                    if (removed > 0) {
                        LOGGER.debug("Cleaned {} stale entries from ApostleUpgradeManager.entityUpgradeData", removed);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            Class<?> specialServantEventsClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.events.SpecialServantEvents");
            lastMoneyAmountsField = specialServantEventsClass.getDeclaredField("lastMoneyAmounts");
            lastMoneyAmountsField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.warn("Could not access SpecialServantEvents.lastMoneyAmounts: {}", e.getMessage());
            lastMoneyAmountsField = null;
        }

        try {
            Class<?> enhancedEntityEventsClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.events.EnhancedEntityEvents");
            enhancedEntitiesField = enhancedEntityEventsClass.getDeclaredField("enhancedEntities");
            enhancedEntitiesField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.warn("Could not access EnhancedEntityEvents.enhancedEntities: {}", e.getMessage());
            enhancedEntitiesField = null;
        }

        try {
            Class<?> upgradeManagerClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.upgrades.ApostleUpgradeManager");
            entityUpgradeDataField = upgradeManagerClass.getDeclaredField("entityUpgradeData");
            entityUpgradeDataField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.warn("Could not access ApostleUpgradeManager.entityUpgradeData: {}", e.getMessage());
            entityUpgradeDataField = null;
        }

        // goety_cataclysm: AnimationSummon.killDataAttackingPlayer
        try {
            animationSummonClass = Class.forName(
                    "com.Polarice3.goety_cataclysm.common.entities.ally.AnimationSummon");
            killDataAttackingPlayerField = animationSummonClass.getDeclaredField("killDataAttackingPlayer");
            killDataAttackingPlayerField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.info("goety_cataclysm not found or AnimationSummon.killDataAttackingPlayer not accessible (optional)");
            animationSummonClass = null;
            killDataAttackingPlayerField = null;
        }
    }
}
