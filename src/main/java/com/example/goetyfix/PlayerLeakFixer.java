package com.example.goetyfix;

import com.Polarice3.Goety.common.entities.ally.Summoned;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.util.concurrent.ConcurrentHashMap;

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

    // Cache for caster/owner/finalTarget field lookups per class (avoid repeated reflection)
    private static final ConcurrentHashMap<Class<?>, Field[]> casterFieldCache = new ConcurrentHashMap<>();

    /**
     * When a player respawns or returns from the End, the old ServerPlayer is replaced.
     * Update all Summoned entities that reference the old player to point to the new one.
     * Also invalidate the old player's capabilities to break reference chains.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerClone(PlayerEvent.Clone event) {
        try {
            Player newPlayer = event.getEntity();
            Player oldPlayer = event.getOriginal();
            MinecraftServer server = newPlayer.getServer();
            if (server == null) return;

            initReflection();
            int updated = 0;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    try {
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
                    } catch (Exception ignored) {
                        // Skip this entity silently
                    }
                }
            }

            // Critical: Invalidate old player's capabilities to break reference chains.
            // Goety's onPlayerClone calls reviveCaps() but never invalidateCaps(),
            // leaving the old player's capability objects (SEImp, LichImp, etc.) reachable.
            try {
                oldPlayer.invalidateCaps();
            } catch (Exception ignored) {}

            if (updated > 0) {
                LOGGER.debug("PlayerClone: Updated {} stale references for player {}",
                        updated, newPlayer.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.debug("PlayerClone handler error (skipped): {}", e.getMessage());
        }
    }

    /**
     * When a player logs out, clear all Summoned entity fields that reference them.
     * Also clear vanilla Mob target and LivingEntity.lastHurtByMob references.
     * This ensures the ServerPlayer object can be garbage collected.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            Player player = event.getEntity();
            MinecraftServer server = player.getServer();
            if (server == null) return;

            initReflection();
            int cleared = 0;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    try {
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
                        // Clear LivingEntity.lastHurtByMob
                        if (entity instanceof LivingEntity living) {
                            if (living.getLastHurtByMob() == player) {
                                living.setLastHurtByMob(null);
                                cleared++;
                            }
                        }
                        // Clear goety_cataclysm AnimationSummon.killDataAttackingPlayer
                        cleared += clearKillDataAttackingPlayer(entity, player);
                        // Clear caster/owner fields on goety_cataclysm projectiles only
                        cleared += clearCasterField(entity, player);
                    } catch (Exception ignored) {
                        // Skip this entity silently
                    }
                }
            }

            if (cleared > 0) {
                LOGGER.debug("PlayerLogout: Cleared {} stale references for player {}",
                        cleared, player.getName().getString());
            }
        } catch (Exception e) {
            LOGGER.debug("PlayerLogout handler error (skipped): {}", e.getMessage());
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

        try {
            cleanupStaticMaps();
        } catch (Exception ignored) {}

        try {
            cleanupStaleReferences(event.getServer());
        } catch (Exception ignored) {}
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
                try {
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
                } catch (Exception ignored) {
                    // Skip this entity silently
                }
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
     * Clear caster/owner/finalTarget LivingEntity fields on goety_cataclysm projectile entities
     * if they match the given player. Only processes goety_cataclysm entities to avoid
     * interfering with vanilla or other mods.
     */
    private static int clearCasterField(Entity entity, Player player) {
        // Only process goety_cataclysm entities
        String className = entity.getClass().getName();
        if (!className.contains("goety_cataclysm")) return 0;

        int cleared = 0;
        Field[] fields = getCachedCasterFields(entity.getClass());
        for (Field field : fields) {
            try {
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
     * Clear caster/owner fields on goety_cataclysm projectile entities if the referenced entity is removed.
     */
    private static void clearCasterIfRemoved(Entity entity) {
        if (entity instanceof Summoned) return; // Already handled above
        String className = entity.getClass().getName();
        // Only process goety_cataclysm projectile/util entities
        if (!className.contains("goety_cataclysm")) return;

        Field[] fields = getCachedCasterFields(entity.getClass());
        for (Field field : fields) {
            try {
                Object value = field.get(entity);
                if (value instanceof LivingEntity le && le.isRemoved()) {
                    field.set(entity, null);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Get cached caster/owner/finalTarget fields for a given class.
     * Results are cached to avoid repeated reflection lookups.
     */
    private static Field[] getCachedCasterFields(Class<?> clazz) {
        return casterFieldCache.computeIfAbsent(clazz, c -> {
            Field[] result = new Field[3];
            int count = 0;
            for (String fieldName : new String[]{"caster", "owner", "finalTarget"}) {
                Field field = findFieldInHierarchy(c, fieldName);
                if (field != null) {
                    // Only cache fields that hold LivingEntity or Entity types
                    Class<?> type = field.getType();
                    if (LivingEntity.class.isAssignableFrom(type) || Entity.class.isAssignableFrom(type)) {
                        field.setAccessible(true);
                        result[count++] = field;
                    }
                }
            }
            // Return trimmed array
            if (count == 0) return new Field[0];
            Field[] trimmed = new Field[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            return trimmed;
        });
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
            } catch (Exception ignored) {}
        }

        // Clean EnhancedEntityEvents.enhancedEntities (prevent unbounded growth)
        if (enhancedEntitiesField != null) {
            try {
                Set<Integer> set = (Set<Integer>) enhancedEntitiesField.get(null);
                if (set != null && set.size() > 10000) {
                    set.clear();
                    LOGGER.debug("Cleared EnhancedEntityEvents.enhancedEntities (exceeded 10000 entries)");
                }
            } catch (Exception ignored) {}
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
            } catch (Exception ignored) {}
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
            LOGGER.info("GoetyAwaken SpecialServantEvents not accessible (optional): {}", e.getMessage());
            lastMoneyAmountsField = null;
        }

        try {
            Class<?> enhancedEntityEventsClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.events.EnhancedEntityEvents");
            enhancedEntitiesField = enhancedEntityEventsClass.getDeclaredField("enhancedEntities");
            enhancedEntitiesField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.info("GoetyAwaken EnhancedEntityEvents not accessible (optional): {}", e.getMessage());
            enhancedEntitiesField = null;
        }

        try {
            Class<?> upgradeManagerClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.upgrades.ApostleUpgradeManager");
            entityUpgradeDataField = upgradeManagerClass.getDeclaredField("entityUpgradeData");
            entityUpgradeDataField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.info("GoetyAwaken ApostleUpgradeManager not accessible (optional): {}", e.getMessage());
            entityUpgradeDataField = null;
        }

        // goety_cataclysm: AnimationSummon.killDataAttackingPlayer
        try {
            animationSummonClass = Class.forName(
                    "com.Polarice3.goety_cataclysm.common.entities.ally.AnimationSummon");
            killDataAttackingPlayerField = animationSummonClass.getDeclaredField("killDataAttackingPlayer");
            killDataAttackingPlayerField.setAccessible(true);
        } catch (Exception e) {
            LOGGER.info("goety_cataclysm AnimationSummon not accessible (optional): {}", e.getMessage());
            animationSummonClass = null;
            killDataAttackingPlayerField = null;
        }
    }
}
