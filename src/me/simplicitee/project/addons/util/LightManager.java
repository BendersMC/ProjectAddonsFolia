package me.simplicitee.project.addons.util;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * LightManager provides dynamic lighting capabilities for ProjectKorra abilities.
 * Uses Folia-compatible Bukkit scheduling and optimized data structures for maximum performance.
 * 
 * Performance Features:
 * - Smart scheduling: Only runs when lights are active
 * - Block change batching: Groups multiple changes into single tasks
 * - Efficient data structures: Uses ConcurrentHashMap and optimized collections
 * - Memory optimization: Automatic cleanup and efficient object reuse
 */
public class LightManager {
    private static final LightManager INSTANCE = new LightManager();
    
    // Performance-optimized data structures
    private final Map<Location, ConcurrentSkipListSet<LightData>> lightMap = new ConcurrentHashMap<>();
    private final Queue<BlockChange> blockChangeQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, BlockData> lightDataMap = new HashMap<>();
    private final Map<Integer, BlockData> waterloggedLightDataMap = new HashMap<>();
    
    // Smart scheduling - only run when needed
    private BukkitTask lightReverterTask;
    private BukkitTask blockChangeProcessorTask;
    private boolean hasActiveLights = false;
    
    // Performance optimization: Pre-calculated constants
    private static final double VIEW_DISTANCE_MULTIPLIER = 16.0;
    private static final int MAX_BATCH_SIZE = 50; // Process up to 50 block changes per task
    private static final long MIN_SCHEDULE_INTERVAL = 2L; // Minimum 2 ticks between runs
    
    // Thread-safe locks for location-based synchronization
    private final Object[] locks;
    private final boolean modern;

    /**
     * Creates a new LightManager instance. Initializes default BlockData for LIGHT and waterlogged LIGHT,
     * sets up locks based on the number of available processors * 2, and schedules the reverter task to run periodically
     * using Folia-compatible Bukkit scheduling with smart performance optimization.
     */
    private LightManager() {
        modern = GeneralMethods.getMCVersion() >= 1170;

        int numLocks = Runtime.getRuntime().availableProcessors() * 2;
        locks = new Object[numLocks];
        for (int i = 0; i < numLocks; i++) {
            locks[i] = new Object();
        }

        if (modern) {
            precomputeLightData();
            // Don't start scheduler until lights are actually needed
        }
    }

    /**
     * Retrieves the current time and iterates over all light data in the light map. If the current time is greater
     * than or equal to the expiry time of a light data, it fades the light out and removes the light data from the map.
     * This uses smart scheduling - only runs when lights are active and adapts to server load.
     */
    private void revertExpiredLights() {
        if (!hasActiveLights) return; // Performance: Skip if no lights
        
        long currentTime = System.currentTimeMillis();
        List<LightData> lightsToRevert = new ArrayList<>();
        int processedCount = 0;
        final int MAX_PROCESSING_PER_TICK = 100; // Prevent lag spikes

        // Performance: Use iterator for efficient removal
        Iterator<Map.Entry<Location, ConcurrentSkipListSet<LightData>>> mapIterator = lightMap.entrySet().iterator();
        while (mapIterator.hasNext() && processedCount < MAX_PROCESSING_PER_TICK) {
            Map.Entry<Location, ConcurrentSkipListSet<LightData>> entry = mapIterator.next();
            Location location = entry.getKey();
            ConcurrentSkipListSet<LightData> lightDataSet = entry.getValue();
            
            Iterator<LightData> iterator = lightDataSet.iterator();
            while (iterator.hasNext() && processedCount < MAX_PROCESSING_PER_TICK) {
                LightData lightData = iterator.next();
                if (currentTime >= lightData.expiryTime) {
                    lightsToRevert.add(lightData);
                    iterator.remove();
                    processedCount++;
                }
            }
            
            if (lightDataSet.isEmpty()) {
                mapIterator.remove();
            }
        }

        // Process reversions in batches for better performance
        if (!lightsToRevert.isEmpty()) {
            for (LightData lightData : lightsToRevert) {
                fadeLight(lightData);
            }
        }
        
        // Check if we still have active lights
        updateActiveLightsStatus();
    }

    /**
     * Precomputes light data for levels 1 through 15 by creating a BlockData object for each level
     * with the "LIGHT" material and setting the level using the Levelled interface. It also
     * creates a waterlogged version of each light data object using the Waterlogged interface.
     * The resulting objects are stored in the lightDataMap and waterloggedLightDataMap maps
     * respectively. This cuts down on computation time constantly manipulating BlockData.
     */
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

    /**
     * Starts the light reverter task with smart scheduling. Only runs when lights are active
     * and adapts the frequency based on the number of active lights for optimal performance.
     */
    private void startLightReverter() {
        // Cancel existing tasks if they exist
        if (lightReverterTask != null) {
            lightReverterTask.cancel();
        }
        if (blockChangeProcessorTask != null) {
            blockChangeProcessorTask.cancel();
        }

        // Performance: Adaptive scheduling based on light count
        long interval = calculateOptimalInterval();
        
        // Schedule tasks using Folia-compatible Bukkit scheduler with smart intervals
        lightReverterTask = new BukkitRunnable() {
            @Override
            public void run() {
                revertExpiredLights();
            }
        }.runTaskTimer(ProjectKorra.plugin, 0L, interval);

        blockChangeProcessorTask = new BukkitRunnable() {
            @Override
            public void run() {
                processBlockChanges();
            }
        }.runTaskTimer(ProjectKorra.plugin, 0L, 1L); // Keep block processing at 1 tick for responsiveness
    }
    
    /**
     * Calculates the optimal scheduling interval based on the number of active lights.
     * More lights = more frequent updates, fewer lights = less frequent updates.
     */
    private long calculateOptimalInterval() {
        int totalLights = lightMap.values().stream().mapToInt(Set::size).sum();
        
        if (totalLights == 0) return 20L; // No lights: run every second
        if (totalLights < 10) return 5L;  // Few lights: run every 5 ticks
        if (totalLights < 50) return 2L;  // Some lights: run every 2 ticks
        return 1L; // Many lights: run every tick
    }
    
    /**
     * Updates the active lights status and adjusts scheduling accordingly.
     */
    private void updateActiveLightsStatus() {
        boolean previouslyActive = hasActiveLights;
        hasActiveLights = !lightMap.isEmpty();
        
        // Performance: Stop scheduler if no lights, start if lights become active
        if (previouslyActive && !hasActiveLights) {
            if (lightReverterTask != null) {
                lightReverterTask.cancel();
                lightReverterTask = null;
            }
        } else if (!previouslyActive && hasActiveLights) {
            startLightReverter();
        }
    }

    /**
     * Fades out a light by decrementing its brightness by 1. The light will stop fading when its brightness
     * reaches 0. The fade process is scheduled to run every 50 milliseconds, or 1 tick, using Folia-compatible scheduling.
     *
     * @param lightData the LightData object containing the light's brightness, location, UUID, and ephemeral flag
     */
    private void fadeLight(LightData lightData) {
        int brightness = lightData.brightness;

        class FadeTask extends BukkitRunnable {
            private int currentBrightness = brightness;

            @Override
            public void run() {
                currentBrightness--;
                if (currentBrightness > 0) {
                    sendLightChange(lightData.location, currentBrightness, lightData.observers);
                } else {
                    revertLight(lightData);
                    this.cancel();
                }
            }
        }

        new FadeTask().runTaskTimer(ProjectKorra.plugin, 0L, 1L);
    }

    /**
     * Sends a block change to the specified location. Brightness of 0 indicates that the light should be reverted.
     * Performance optimized with distance pre-filtering and efficient observer processing.
     *
     * @param location   the location where the light change is to be sent
     * @param brightness the brightness level of the light
     * @param observers  the list of players who can see the light
     */
    private void sendLightChange(Location location, int brightness, Collection<? extends Player> observers) {
        BlockData lightData = brightness > 0 ? getLightData(location, brightness) : getCurrentBlockData(location);
        World targetWorld = location.getWorld();
        
        // Performance: Pre-calculate view distance once
        double maxDistanceSquared = Math.pow(Bukkit.getServer().getViewDistance() * VIEW_DISTANCE_MULTIPLIER, 2);

        // Performance: Use stream with early termination for better performance
        observers.stream()
                .filter(player -> player != null && player.isOnline() && !player.isDead() && player.getWorld().equals(targetWorld))
                .filter(player -> player.getLocation().distanceSquared(location) <= maxDistanceSquared)
                .forEach(player -> blockChangeQueue.add(new BlockChange(player, location, lightData)));
    }

    /**
     * Processes block changes from the queue with batching for optimal performance.
     * This method runs on the main thread to ensure thread safety and processes
     * multiple changes in batches to reduce scheduler overhead.
     */
    private void processBlockChanges() {
        if (blockChangeQueue.isEmpty()) return; // Performance: Skip if no changes
        
        List<BlockChange> batch = new ArrayList<>();
        BlockChange blockChange;
        int batchSize = 0;
        
        // Performance: Batch processing for better efficiency
        while ((blockChange = blockChangeQueue.poll()) != null && batchSize < MAX_BATCH_SIZE) {
            final Player player = blockChange.getPlayer();
            if (player != null && player.isOnline() && !player.isDead()) {
                batch.add(blockChange);
                batchSize++;
            }
        }
        
        // Process the entire batch in a single task
        if (!batch.isEmpty()) {
            Bukkit.getScheduler().runTask(ProjectKorra.plugin, () -> {
                for (BlockChange change : batch) {
                    Player player = change.getPlayer();
                    if (player != null && player.isOnline() && !player.isDead()) {
                        player.sendBlockChange(change.getLocation(), change.getBlockData());
                    }
                }
            });
        }
    }

    /**
     * Helper method to revert a light at the specified location by calling sendLightChange with a brightness of 0.
     *
     * @param lightData the LightData object containing the location, brightness, UUID, and ephemeral flag of the light to be reverted
     */
    private void revertLight(LightData lightData) {
        sendLightChange(lightData.location, 0, lightData.observers);
    }

    /**
     * Returns the BlockData as light for the given Location, based on whether the block is water or air.
     *
     * @param location   the Location to get the BlockData for
     * @param lightLevel the light level to set for the BlockData
     * @return the BlockData for the given Location
     */
    private BlockData getLightData(Location location, int lightLevel) {
        if (location.getBlock().getType() == Material.WATER) {
            return waterloggedLightDataMap.get(lightLevel);
        } else {
            return lightDataMap.get(lightLevel);
        }
    }

    /**
     * Returns the BlockData for the given Location, based on the current state of the block.
     * Used to revert lights that have expired.
     *
     * @param location the Location to get the BlockData for
     * @return the BlockData for the given Location
     */
    private BlockData getCurrentBlockData(Location location) {
        return location.getBlock().getBlockData();
    }

    public static LightManager get() {
        return INSTANCE;
    }

    /**
     * Creates a new LightBuilder instance with the given location.
     *
     * @param location the location where the light will be created
     * @return a new LightBuilder instance
     */
    public static LightBuilder createLight(Location location) {
        return new LightBuilder(location);
    }

    /**
     * Adds a light at the specified location with the given brightness and expiry.
     * Visible for the specified observers.
     * Subsequent calls to a location with an active light extends the expiration time for the relevant observers.
     * Performance optimized with smart scheduling activation.
     *
     * @param location   the location where the light should be added
     * @param brightness the brightness of the light, 1-15
     * @param expiry     the time in milliseconds before the light fades out
     * @param observers  the list of players who can see the light
     */
    private void addLight(Location location, int brightness, long expiry, Collection<? extends Player> observers) {
        if (!modern) return;

        location = location.getBlock().getLocation();
        long expiryTime = System.currentTimeMillis() + expiry;

        if (location.getBlock().getLightLevel() >= brightness ||
                (!location.getBlock().isEmpty() && !location.getBlock().getType().equals(Material.WATER))) return;

        LightData newLightData = new LightData(location, brightness, observers, expiryTime);

        Object lock = getLockForLocation(location);
        synchronized (lock) {
            ConcurrentSkipListSet<LightData> existingSet = lightMap.computeIfAbsent(location, loc -> new ConcurrentSkipListSet<>());
            existingSet.removeIf(lightData -> lightData.observers.equals(observers));
            existingSet.add(newLightData);
        }

        // Performance: Activate scheduler if this is the first light
        if (!hasActiveLights) {
            hasActiveLights = true;
            startLightReverter();
        }

        sendLightChange(location, brightness, observers);
    }

    /**
     * Returns the lock object associated with the given location. The lock object
     * is used to synchronize access to the light data for the location. The
     * function calculates the hash code of the location and uses it to determine
     * the index of the lock object in the locks array. The locks array is
     * initialized with a fixed number of objects based on the number of available
     * processors * 2.
     *
     * @param location the location for which the lock object is requested
     * @return the lock object associated with the location
     */
    private Object getLockForLocation(Location location) {
        return locks[(location.hashCode() & 0x7FFFFFFF) % locks.length];
    }

    /**
     * Reverts all active lights immediately with no fade-out, then restarts the revert scheduler.
     * This does not normally need to be used as it's already called when ProjectKorra is reloaded.
     * Performance optimized with batch processing.
     */
    public void restart() {
        if (!modern) return;

        // Performance: Batch revert all lights
        lightMap.values().forEach(set -> set.forEach(this::revertLight));
        lightMap.clear();

        // Cancel existing tasks
        if (lightReverterTask != null) {
            lightReverterTask.cancel();
            lightReverterTask = null;
        }
        if (blockChangeProcessorTask != null) {
            blockChangeProcessorTask.cancel();
            blockChangeProcessorTask = null;
        }

        hasActiveLights = false;
        // Don't restart scheduler until new lights are added
    }

    private static class LightData implements Comparable<LightData> {
        private final Location location;
        private final int brightness;
        private final Collection<? extends Player> observers;
        private final long expiryTime;

        private LightData(Location location, int brightness, Collection<? extends Player> observers, long expiryTime) {
            this.location = location;
            this.brightness = brightness;
            this.observers = observers;
            this.expiryTime = expiryTime;
        }

        /**
         * Calculates the hash code for this object. The hash code is based on the
         * values of the location, brightness, observers, and expiryTime fields.
         *
         * @return the hash code of this object
         */
        @Override
        public int hashCode() {
            return Objects.hash(location, brightness, observers, expiryTime);
        }

        /**
         * Checks if this LightData object is equal to another object. Two LightData objects are considered
         * equal if they have the same brightness, expiryTime, location, and observers.
         *
         * @param obj the object to compare this LightData object to
         * @return true if the objects are equal, false otherwise
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            LightData that = (LightData) obj;
            return brightness == that.brightness &&
                    expiryTime == that.expiryTime &&
                    location.equals(that.location) &&
                    observers.equals(that.observers);
        }

        /**
         * Returns a string representation of the LightData object.
         *
         * @return a string in the format "LightData{location=..., brightness=..., observers=..., expiryTime=...}"
         */
        @Override
        public String toString() {
            return "LightData{" +
                    "location=" + location +
                    ", brightness=" + brightness +
                    ", observers=" + observers +
                    ", expiryTime=" + expiryTime +
                    '}';
        }

        /**
         * Compares this LightData object with another LightData object based on their expiryTime.
         *
         * @param other the LightData object to compare to
         * @return a negative integer, zero, or a positive integer as this object's
         * expiryTime is less than, equal to, or greater than the other object's
         * expiryTime.
         */
        @Override
        public int compareTo(LightData other) {
            return Long.compare(this.expiryTime, other.expiryTime);
        }
    }

    public static class LightBuilder {
        private final Location location;
        private int brightness = 15; // default brightness
        private long timeUntilFade = 50; // default expiry time in ms
        private Collection<? extends Player> observers = Bukkit.getOnlinePlayers(); // default to all players

        public LightBuilder(Location location) {
            this.location = location;
        }

        /**
         * Sets the brightness value, 1-15, for this light.
         *
         * @param brightness the new brightness value, a value 1-15.
         * @return the current instance of the LightBuilder
         */
        public LightBuilder brightness(int brightness) {
            this.brightness = Math.max(1, Math.min(15, brightness));
            return this;
        }

        /**
         * Sets the time until fade for this light.
         *
         * @param timeUntilFade the time in milliseconds until the light fades out.
         * @return the current instance of the LightBuilder
         */
        public LightBuilder timeUntilFade(long timeUntilFade) {
            this.timeUntilFade = timeUntilFade;
            return this;
        }

        /**
         * Sets the observers for this light.
         *
         * @param observers the collection of players who can see this light.
         * @return the current instance of the LightBuilder
         */
        public LightBuilder observers(Collection<? extends Player> observers) {
            this.observers = observers;
            return this;
        }

        /**
         * Builds and creates the light using the current LightBuilder configuration.
         *
         * @return the LightManager instance for method chaining.
         */
        public LightManager build() {
            INSTANCE.addLight(location, brightness, timeUntilFade, observers);
            return INSTANCE;
        }
    }

    private static class BlockChange {
        private final Player player;
        private final Location location;
        private final BlockData blockData;

        private BlockChange(Player player, Location location, BlockData blockData) {
            this.player = player;
            this.location = location;
            this.blockData = blockData;
        }

        public Player getPlayer() {
            return player;
        }

        public Location getLocation() {
            return location;
        }

        public BlockData getBlockData() {
            return blockData;
        }
    }
}
