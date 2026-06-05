package com.example.goetyfix;

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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixes ServerPlayer memory leaks caused by Goety/GoetyAwaken mods.
 *
 * Uses reflection for all Goety-specific class access to remain compatible
 * with Goety 2.5.3x through 2.5.5x without hard dependencies.
 */
public class PlayerLeakFixer {

    private static final Logger LOGGER = LogManager.getLogger("GoetyFix");
    private static int tickCounter = 0;

    // --- GoetyAwaken reflection fields ---
    private static Field lastMoneyAmountsField;
    private static Field enhancedEntitiesField;
    private static Field entityUpgradeDataField;

    // --- goety_cataclysm reflection fields ---
    private static Field killDataAttackingPlayerField;
    private static Class<?> animationSummonClass;

    // --- Goety Summoned reflection fields/methods (version-safe) ---
    private static Class<?> summonedClass;
    private static Field commandPosEntityField;
    private static Method getPriorityTargetMethod;
    private static Method setPriorityTargetMethod;
    private static boolean summonedReflectionInit = false;

    private static boolean reflectionInitialized = false;

    // Cache for caster/owner/finalTarget field lookups per class
    private static final ConcurrentHashMap<Class<?>, Field[]> casterFieldCache = new ConcurrentHashMap<>();

    /**
     * When a player respawns or returns from the End, the old ServerPlayer is replaced.
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
                        updated += clearSummonedReferences(entity, oldPlayer, newPlayer);
                        updated += clearKillDataAttackingPlayer(entity, oldPlayer);
                    } catch (Exception ignored) {}
                }
            }

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
     * When a player logs out, clear all entity fields that reference them.
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
                        cleared += clearSummonedReferences(entity, player, null);
                        if (entity instanceof Mob mob) {
                            if (mob.getTarget() == player) {
                                mob.setTarget(null);
                                cleared++;
                            }
                        }
                        if (entity instanceof LivingEntity living) {
                            if (living.getLastHurtByMob() == player) {
                                living.setLastHurtByMob(null);
                                cleared++;
                            }
                        }
                        cleared += clearKillDataAttackingPlayer(entity, player);
                        cleared += clearCasterField(entity, player);
                    } catch (Exception ignored) {}
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
     * Periodic cleanup (every 600 ticks = 30 seconds).
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < 600) return;
        tickCounter = 0;

        try { cleanupStaticMaps(); } catch (Exception ignored) {}
        try { cleanupStaleReferences(event.getServer()); } catch (Exception ignored) {}
    }

    // ======================== Summoned entity helpers (reflection) ========================

    /**
     * Clear Goety Summoned entity fields that hold stale player references.
     * Uses reflection so it works with Goety 2.5.3x (which may not have
     * getPriorityTarget/setPriorityTarget) through 2.5.5x.
     *
     * @param stalePlayer the old player to clear, or null to just clear removed refs
     * @param newPlayer   the new player to point to (only for Clone), or null for Logout
     * @return number of references cleared
     */
    private static int clearSummonedReferences(Entity entity, Player stalePlayer, Player newPlayer) {
        initSummonedReflection();
        if (summonedClass == null) return 0;
        if (!summonedClass.isInstance(entity)) return 0;

        int cleared = 0;

        // commandPosEntity field (present in all Goety 2.5.x versions)
        if (commandPosEntityField != null) {
            try {
                Object value = commandPosEntityField.get(entity);
                if (value == stalePlayer) {
                    if (newPlayer != null) {
                        commandPosEntityField.set(entity, newPlayer);
                    } else {
                        commandPosEntityField.set(entity, null);
                    }
                    cleared++;
                }
            } catch (Exception ignored) {}
        }

        // priorityTarget via getter/setter (may not exist in 2.5.3x)
        if (getPriorityTargetMethod != null && setPriorityTargetMethod != null) {
            try {
                Object target = getPriorityTargetMethod.invoke(entity);
                if (target == stalePlayer) {
                    setPriorityTargetMethod.invoke(entity, (Object) null);
                    cleared++;
                }
            } catch (Exception ignored) {}
        }

        return cleared;
    }

    /**
     * Clear commandPosEntity/priorityTarget if the referenced entity has been removed.
     */
    private static void clearSummonedIfRemoved(Entity entity) {
        initSummonedReflection();
        if (summonedClass == null) return;
        if (!summonedClass.isInstance(entity)) return;

        if (commandPosEntityField != null) {
            try {
                Object value = commandPosEntityField.get(entity);
                if (value instanceof LivingEntity le && le.isRemoved()) {
                    commandPosEntityField.set(entity, null);
                }
            } catch (Exception ignored) {}
        }

        if (getPriorityTargetMethod != null && setPriorityTargetMethod != null) {
            try {
                Object target = getPriorityTargetMethod.invoke(entity);
                if (target instanceof LivingEntity le && le.isRemoved()) {
                    setPriorityTargetMethod.invoke(entity, (Object) null);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Initialize reflection for Goety Summoned class (version-safe).
     */
    private static void initSummonedReflection() {
        if (summonedReflectionInit) return;
        summonedReflectionInit = true;

        try {
            summonedClass = Class.forName("com.Polarice3.Goety.common.entities.ally.Summoned");
        } catch (Exception e) {
            LOGGER.info("Goety Summoned class not found (optional): {}", e.getMessage());
            summonedClass = null;
            return;
        }

        // commandPosEntity field (public in all known versions)
        commandPosEntityField = findFieldInHierarchy(summonedClass, "commandPosEntity");
        if (commandPosEntityField != null) {
            commandPosEntityField.setAccessible(true);
        }

        // priorityTarget getter/setter (may not exist in 2.5.3x)
        try {
            getPriorityTargetMethod = summonedClass.getDeclaredMethod("getPriorityTarget");
            getPriorityTargetMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            getPriorityTargetMethod = null;
        }

        try {
            setPriorityTargetMethod = summonedClass.getDeclaredMethod("setPriorityTarget", LivingEntity.class);
            setPriorityTargetMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            setPriorityTargetMethod = null;
        }
    }

    // ======================== goety_cataclysm helpers ========================

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

    // ======================== Projectile helpers ========================

    private static int clearCasterField(Entity entity, Player player) {
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

    private static void clearCasterIfRemoved(Entity entity) {
        String className = entity.getClass().getName();
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

    private static Field[] getCachedCasterFields(Class<?> clazz) {
        return casterFieldCache.computeIfAbsent(clazz, c -> {
            Field[] result = new Field[3];
            int count = 0;
            for (String fieldName : new String[]{"caster", "owner", "finalTarget"}) {
                Field field = findFieldInHierarchy(c, fieldName);
                if (field != null) {
                    Class<?> type = field.getType();
                    if (LivingEntity.class.isAssignableFrom(type) || Entity.class.isAssignableFrom(type)) {
                        field.setAccessible(true);
                        result[count++] = field;
                    }
                }
            }
            if (count == 0) return new Field[0];
            Field[] trimmed = new Field[count];
            System.arraycopy(result, 0, trimmed, 0, count);
            return trimmed;
        });
    }

    // ======================== Periodic cleanup ========================

    private static void cleanupStaleReferences(MinecraftServer server) {
        if (server == null) return;

        initReflection();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                try {
                    clearSummonedIfRemoved(entity);
                    clearKillDataIfRemoved(entity);
                    clearCasterIfRemoved(entity);
                } catch (Exception ignored) {}
            }
        }
    }

    // ======================== Static map cleanup ========================

    @SuppressWarnings("unchecked")
    private static void cleanupStaticMaps() {
        initReflection();

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

        if (enhancedEntitiesField != null) {
            try {
                Set<Integer> set = (Set<Integer>) enhancedEntitiesField.get(null);
                if (set != null && set.size() > 10000) {
                    set.clear();
                    LOGGER.debug("Cleared EnhancedEntityEvents.enhancedEntities (exceeded 10000 entries)");
                }
            } catch (Exception ignored) {}
        }

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

    // ======================== Reflection util ========================

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

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            Class<?> specialServantEventsClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.events.SpecialServantEvents");
            lastMoneyAmountsField = specialServantEventsClass.getDeclaredField("lastMoneyAmounts");
            lastMoneyAmountsField.setAccessible(true);
        } catch (Exception e) {
            lastMoneyAmountsField = null;
        }

        try {
            Class<?> enhancedEntityEventsClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.events.EnhancedEntityEvents");
            enhancedEntitiesField = enhancedEntityEventsClass.getDeclaredField("enhancedEntities");
            enhancedEntitiesField.setAccessible(true);
        } catch (Exception e) {
            enhancedEntitiesField = null;
        }

        try {
            Class<?> upgradeManagerClass = Class.forName(
                    "com.k1sak1.goetyawaken.common.upgrades.ApostleUpgradeManager");
            entityUpgradeDataField = upgradeManagerClass.getDeclaredField("entityUpgradeData");
            entityUpgradeDataField.setAccessible(true);
        } catch (Exception e) {
            entityUpgradeDataField = null;
        }

        try {
            animationSummonClass = Class.forName(
                    "com.Polarice3.goety_cataclysm.common.entities.ally.AnimationSummon");
            killDataAttackingPlayerField = animationSummonClass.getDeclaredField("killDataAttackingPlayer");
            killDataAttackingPlayerField.setAccessible(true);
        } catch (Exception e) {
            animationSummonClass = null;
            killDataAttackingPlayerField = null;
        }
    }
}
