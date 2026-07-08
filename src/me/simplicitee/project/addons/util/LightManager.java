package me.simplicitee.project.addons.util;

import com.projectkorra.projectkorra.GeneralMethods;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.simplicitee.project.addons.ProjectAddons;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

public class LightManager {

    private static final LightManager INSTANCE = new LightManager();
    private static final List<UUID> AUTO_NEARBY_OBSERVERS = List.of();

    private final boolean modern;
    private final Object[] locks;
    private final ConcurrentHashMap<LightKey, ConcurrentSkipListSet<LightData>> lightMap = new ConcurrentHashMap<>();
    private final Map<Integer, BlockData> lightDataMap = new HashMap<>();
    private final Map<Integer, BlockData> waterloggedLightDataMap = new HashMap<>();

    private volatile boolean acceptingLights = true;

    private LightManager() {
        modern = GeneralMethods.getMCVersion() >= 1170;

        int numLocks = Runtime.getRuntime().availableProcessors() * 2;
        locks = new Object[numLocks];
        for (int i = 0; i < numLocks; i++) {
            locks[i] = new Object();
        }

        if (modern) {
            precomputeLightData();
        }
    }

    private void precomputeLightData() {
        BlockData lightData = Bukkit.createBlockData(Material.valueOf("LIGHT"));

        for (int level = 1; level <= 15; level++) {
            ((Levelled) lightData).setLevel(level);
            lightDataMap.put(level, lightData.clone());

            BlockData waterloggedLightData = lightData.clone();
            ((Waterlogged) waterloggedLightData).setWaterlogged(true);
            waterloggedLightDataMap.put(level, waterloggedLightData);
        }
    }

    private void addLight(Location location, int brightness, long expiryMs, Collection<? extends Player> observers) {
        if (!modern || !acceptingLights) {
            return;
        }

        Location scheduleAt = location.clone();
        boolean autoNearby = observers == null;
        List<UUID> observerIds = autoNearby ? AUTO_NEARBY_OBSERVERS : snapshotObserverIds(observers);

        Bukkit.getRegionScheduler().execute(plugin(), scheduleAt, () ->
                addLightOnRegionThread(scheduleAt, brightness, expiryMs, observerIds, autoNearby));
    }

    private void addLightOnRegionThread(Location location, int brightness, long expiryMs,
            List<UUID> observerIds, boolean autoNearby) {
        if (!acceptingLights) {
            return;
        }

        Location blockLocation = location.getBlock().getLocation();
        long expiryTime = System.currentTimeMillis() + expiryMs;

        if (blockLocation.getBlock().getLightLevel() >= brightness
                || (!blockLocation.getBlock().isEmpty() && blockLocation.getBlock().getType() != Material.WATER)) {
            return;
        }

        LightData newLightData = new LightData(blockLocation.clone(), brightness, observerIds, autoNearby, expiryTime);
        LightKey key = LightKey.from(blockLocation);

        LightData previous;
        Object lock = getLockForKey(key);
        synchronized (lock) {
            ConcurrentSkipListSet<LightData> existingSet =
                    lightMap.computeIfAbsent(key, ignored -> new ConcurrentSkipListSet<>());
            previous = findMatchingLight(existingSet, newLightData);
            if (previous != null) {
                cancelTasks(previous);
                existingSet.remove(previous);
            }
            existingSet.add(newLightData);
        }

        sendLightChangeOnRegion(blockLocation, brightness, observerIds, autoNearby);
        scheduleExpiry(newLightData);
    }

    private void scheduleExpiry(LightData lightData) {
        long delayMs = lightData.expiryTime - System.currentTimeMillis();
        long delayTicks = Math.max(1L, (delayMs + 49L) / 50L);

        ScheduledTask task = Bukkit.getRegionScheduler().runDelayed(plugin(), lightData.location, scheduledTask -> {
            if (!acceptingLights) {
                return;
            }
            if (!removeLightData(lightData)) {
                return;
            }
            beginFade(lightData);
        }, delayTicks);
        lightData.expiryTask = task;
    }

    private void beginFade(LightData lightData) {
        final int[] currentBrightness = {lightData.brightness};
        ScheduledTask fadeTask = Bukkit.getRegionScheduler().runAtFixedRate(plugin(), lightData.location, task -> {
            currentBrightness[0]--;
            if (currentBrightness[0] > 0) {
                sendLightChangeOnRegion(lightData.location, currentBrightness[0], lightData.observerIds, lightData.autoNearby);
            } else {
                task.cancel();
                sendLightChangeOnRegion(lightData.location, 0, lightData.observerIds, lightData.autoNearby);
            }
        }, 1L, 1L);
        lightData.fadeTask = fadeTask;
    }

    private void sendLightChangeOnRegion(Location location, int brightness, List<UUID> observerIds, boolean autoNearby) {
        BlockData blockData = brightness > 0 ? getLightData(location, brightness) : getCurrentBlockData(location);
        List<Player> targets = resolveObservers(location, observerIds, autoNearby);
        Location sendLocation = location.clone();
        BlockData sendData = blockData;

        for (Player player : targets) {
            UUID playerId = player.getUniqueId();
            SchedulerUtil.runForPlayer(plugin(), player, () -> {
                Player online = Bukkit.getPlayer(playerId);
                if (online != null && online.isOnline() && !online.isDead()) {
                    online.sendBlockChange(sendLocation, sendData);
                }
            });
        }
    }

    /**
     * When callers do not pass explicit observers, discover viewers with a bounded regional lookup
     * on the owning region thread instead of scanning all online players.
     */
    private List<Player> resolveObservers(Location location, List<UUID> observerIds, boolean autoNearby) {
        World world = location.getWorld();
        if (world == null) {
            return List.of();
        }

        double viewRadius = Bukkit.getServer().getViewDistance() * 16.0;

        if (autoNearby) {
            return new ArrayList<>(world.getNearbyPlayers(location, viewRadius, 1.0));
        }

        List<Player> targets = new ArrayList<>(observerIds.size());
        for (UUID playerId : observerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (!player.getWorld().equals(world)) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) > viewRadius * viewRadius) {
                continue;
            }
            targets.add(player);
        }
        return targets;
    }

    private BlockData getLightData(Location location, int lightLevel) {
        if (location.getBlock().getType() == Material.WATER) {
            return waterloggedLightDataMap.get(lightLevel);
        }
        return lightDataMap.get(lightLevel);
    }

    private BlockData getCurrentBlockData(Location location) {
        return location.getBlock().getBlockData();
    }

    public static LightManager get() {
        return INSTANCE;
    }

    public static LightBuilder createLight(Location location) {
        return new LightBuilder(location);
    }

    /**
     * Reverts all active lights immediately with no fade-out and cancels scheduled light tasks.
     * Called when ProjectKorra reloads configuration.
     */
    public void restart() {
        if (!modern) {
            return;
        }

        acceptingLights = false;

        List<LightData> lightsToRevert = new ArrayList<>();
        for (ConcurrentSkipListSet<LightData> set : lightMap.values()) {
            for (LightData lightData : set) {
                cancelTasks(lightData);
                lightsToRevert.add(lightData);
            }
        }
        lightMap.clear();

        for (LightData lightData : lightsToRevert) {
            Location location = lightData.location.clone();
            List<UUID> observerIds = lightData.observerIds;
            boolean autoNearby = lightData.autoNearby;
            Bukkit.getRegionScheduler().execute(plugin(), location, () ->
                    sendLightChangeOnRegion(location, 0, observerIds, autoNearby));
        }

        acceptingLights = true;
    }

    private boolean removeLightData(LightData lightData) {
        LightKey key = LightKey.from(lightData.location);
        Object lock = getLockForKey(key);
        synchronized (lock) {
            ConcurrentSkipListSet<LightData> set = lightMap.get(key);
            if (set == null) {
                return false;
            }
            boolean removed = set.remove(lightData);
            if (set.isEmpty()) {
                lightMap.remove(key, set);
            }
            return removed;
        }
    }

    private static LightData findMatchingLight(ConcurrentSkipListSet<LightData> set, LightData candidate) {
        for (LightData existing : set) {
            if (existing.sameObserverSet(candidate)) {
                return existing;
            }
        }
        return null;
    }

    private static void cancelTasks(LightData lightData) {
        ScheduledTask expiryTask = lightData.expiryTask;
        if (expiryTask != null && !expiryTask.isCancelled()) {
            expiryTask.cancel();
        }
        ScheduledTask fadeTask = lightData.fadeTask;
        if (fadeTask != null && !fadeTask.isCancelled()) {
            fadeTask.cancel();
        }
    }

    private static List<UUID> snapshotObserverIds(Collection<? extends Player> observers) {
        List<UUID> ids = new ArrayList<>(observers.size());
        for (Player player : observers) {
            if (player != null) {
                ids.add(player.getUniqueId());
            }
        }
        return List.copyOf(ids);
    }

    private Object getLockForKey(LightKey key) {
        return locks[(key.hashCode() & 0x7FFFFFFF) % locks.length];
    }

    private static ProjectAddons plugin() {
        return ProjectAddons.instance;
    }

    private record LightKey(UUID worldId, int x, int y, int z) {
        static LightKey from(Location location) {
            Location block = location.getBlock().getLocation();
            World world = block.getWorld();
            UUID worldUuid = world != null ? world.getUID() : new UUID(0L, 0L);
            return new LightKey(worldUuid, block.getBlockX(), block.getBlockY(), block.getBlockZ());
        }
    }

    private static final class LightData implements Comparable<LightData> {
        private final Location location;
        private final int brightness;
        private final List<UUID> observerIds;
        private final boolean autoNearby;
        private final long expiryTime;
        private volatile ScheduledTask expiryTask;
        private volatile ScheduledTask fadeTask;

        private LightData(Location location, int brightness, List<UUID> observerIds, boolean autoNearby, long expiryTime) {
            this.location = location;
            this.brightness = brightness;
            this.observerIds = observerIds;
            this.autoNearby = autoNearby;
            this.expiryTime = expiryTime;
        }

        private boolean sameObserverSet(LightData other) {
            return autoNearby == other.autoNearby && observerIds.equals(other.observerIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, brightness, observerIds, autoNearby, expiryTime);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            LightData that = (LightData) obj;
            return brightness == that.brightness
                    && expiryTime == that.expiryTime
                    && autoNearby == that.autoNearby
                    && location.equals(that.location)
                    && observerIds.equals(that.observerIds);
        }

        @Override
        public int compareTo(LightData other) {
            return Long.compare(this.expiryTime, other.expiryTime);
        }
    }

    public static class LightBuilder {
        private final Location location;
        private int brightness = 15;
        private long timeUntilFade = 50;
        private Collection<? extends Player> observers;

        public LightBuilder(Location location) {
            this.location = location;
        }

        public LightBuilder brightness(int brightness) {
            this.brightness = Math.max(1, Math.min(15, brightness));
            return this;
        }

        public LightBuilder timeUntilFadeout(long expiry) {
            this.timeUntilFade = expiry;
            return this;
        }

        /**
         * Sets explicit observers. When omitted, nearby players are resolved on the light's region thread.
         */
        public LightBuilder observers(Collection<? extends Player> observers) {
            this.observers = observers;
            return this;
        }

        public void emit() {
            LightManager.get().addLight(location, brightness, timeUntilFade, observers);
        }
    }
}
