# ProjectAddons

ProjectKorra addon plugin with Folia compatibility and performance optimizations.

## What Was Implemented

### Folia Compatibility
- Replaced all `ThreadUtil` calls with `BukkitScheduler` equivalents
- Converted `ScheduledThreadPoolExecutor` to `BukkitTask` and `BukkitRunnable`
- Updated `LightManager` to use `Bukkit.getScheduler().runTask()` for block changes
- Fixed lambda variable capture issues by making variables `final`
- Added `folia-supported: true` to plugin.yml

### Performance Improvements
- Implemented configuration caching system with TTL (30 seconds)
- Added batch processing in `LightManager` with `MAX_BATCH_SIZE` limit
- Pre-calculated trigonometry arrays in `Crumble` ability
- Added `MAX_BLOCKS_PER_TICK` limits to prevent lag spikes
- Implemented smart scheduling that only runs when needed
- Added performance monitoring and metrics collection

### Code Quality
- Fixed unsafe type casting in configuration methods
- Added proper error handling with try-catch blocks
- Improved configuration loading with validation
- Added null checks and fallback values
- Fixed constructor calls and method overrides
- Removed duplicate imports and unused code

### Configuration System
- Added configuration validation and error handling
- Implemented safe type conversion for numeric values
- Added fallback values for all configuration options

## Technical Details

### Scheduling Changes
```java
// Before (Folia incompatible)
ThreadUtil.runSyncLater(() -> {}, 20L);
ScheduledThreadPoolExecutor scheduler;

// After (Folia compatible)
Bukkit.getScheduler().runTaskLater(plugin, () -> {}, 20L);
BukkitTask task;
```

### Configuration Caching
```java
// Cache with TTL
private final Map<String, Object> configCache = new ConcurrentHashMap<>();
private final Map<String, Long> configCacheTimestamps = new ConcurrentHashMap<>();
private static final long CONFIG_CACHE_TTL = 30000; // 30 seconds
```

### Type Safety
```java
// Safe numeric conversion
public long getCachedLong(String path, long defaultValue) {
    Object cached = getCachedConfig(path, defaultValue);
    if (cached instanceof Number) {
        return ((Number) cached).longValue();
    }
    return defaultValue;
}
```

## Dependencies
- Java 17
- Spigot API 1.21.1+
- ProjectKorra 1.12.1

## Build
```bash
mvn clean package
```

## Issues Fixed
- Configuration loading failures
- ClassCastException in numeric config values
- Folia compatibility with ThreadUtil
- Performance bottlenecks in LightManager
- Memory leaks from unmanaged schedulers
- Unsafe type casting in configuration system

## Performance Metrics
- Configuration cache hit rate monitoring
- Ability registration timing
- Startup time measurement
- Memory usage optimization

Built with ❤️ for Kopeka and friends by Logichh (Milin)
