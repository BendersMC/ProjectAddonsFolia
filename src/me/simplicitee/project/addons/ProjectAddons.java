package me.simplicitee.project.addons;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.ElementType;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.airbending.AirShield;
import com.projectkorra.projectkorra.firebending.FireShield;
import me.simplicitee.project.addons.ability.air.GaleGust;
import me.simplicitee.project.addons.ability.earth.Crumble;
import me.simplicitee.project.addons.ability.fire.CombustBeam;
import me.simplicitee.project.addons.ability.fire.FireDisc;
import me.simplicitee.project.addons.ability.water.RazorLeaf;
import me.simplicitee.project.addons.util.versionadapter.ParticleAdapter;
import me.simplicitee.project.addons.util.versionadapter.ParticleAdapterFactory;
import me.simplicitee.project.addons.util.versionadapter.PotionEffectAdapter;
import me.simplicitee.project.addons.util.versionadapter.PotionEffectAdapterFactory;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProjectAddons - A comprehensive addon for ProjectKorra with performance optimizations.
 * 
 * Performance Features:
 * - Configuration caching for faster access
 * - Performance monitoring and metrics
 * - Optimized ability registration
 * - Memory-efficient data structures
 */
public class ProjectAddons extends JavaPlugin {
	
	public static ProjectAddons instance;
	
	private FileConfiguration config;
	private MainListener listener;
	private Element soundElement;

	private ParticleAdapter particleAdapter;
	private PotionEffectAdapter potionEffectAdapter;

	// Performance optimization: Configuration caching
	private final Map<String, Object> configCache = new ConcurrentHashMap<>();
	private final Map<String, Long> configCacheTimestamps = new ConcurrentHashMap<>();
	private static final long CONFIG_CACHE_TTL = 30000; // 30 seconds cache TTL
	
	// Performance monitoring
	private long startupTime;
	private long abilityRegistrationTime;
	private int totalAbilitiesRegistered;

	@Override
	public void onEnable() {
		instance = this;
		startupTime = System.currentTimeMillis();
		
		// Load configuration using the old, reliable method
		if (!setupConfig()) {
			getLogger().severe("Configuration failed to load! Plugin cannot start.");
			return;
		}

		// Register custom elements
		try {
			soundElement = new SubElement("Sound", Element.AIR, ElementType.BENDING, this);
			getLogger().info("Sound sub-element registered successfully");
		} catch (Exception e) {
			getLogger().warning("Failed to register Sound sub-element: " + e.getMessage());
		}

		// Register abilities with performance monitoring
		long abilityStartTime = System.currentTimeMillis();
		try {
			CoreAbility.registerPluginAbilities(this, "me.simplicitee.project.addons.ability");
			abilityRegistrationTime = System.currentTimeMillis() - abilityStartTime;
			totalAbilitiesRegistered = CoreAbility.getAbilities().size();
			getLogger().info("Abilities registered successfully in " + abilityRegistrationTime + "ms");
		} catch (Exception e) {
			getLogger().severe("Failed to register abilities: " + e.getMessage());
		}

		// Setup collisions
		this.setupCollisions();

		// Initialize version adapters
		try {
			ParticleAdapterFactory particleAdapterFactory = new ParticleAdapterFactory();
			particleAdapter = particleAdapterFactory.getAdapter();

			PotionEffectAdapterFactory potionEffectAdapterFactory = new PotionEffectAdapterFactory();
			potionEffectAdapter = potionEffectAdapterFactory.getAdapter();
			
			getLogger().info("Version adapters initialized successfully");
		} catch (Exception e) {
			getLogger().warning("Failed to initialize version adapters: " + e.getMessage());
		}

		// Register listener and command
		this.listener = new MainListener(this);
		this.getCommand("projectaddons").setExecutor(new ProjectCommand(this));
		
		long totalStartupTime = System.currentTimeMillis() - startupTime;
		getLogger().info("ProjectAddons v" + getDescription().getVersion() + " has been enabled in " + totalStartupTime + "ms!");
		getLogger().info("Performance: " + totalAbilitiesRegistered + " abilities registered in " + abilityRegistrationTime + "ms");
	}
	
	@Override
	public void onDisable() {
		if (listener != null) {
			listener.revertSwappedBinds();
		}
		
		// Revert any Crumble changes
		if (CoreAbility.getAbility(Crumble.class) != null) {
			for (Crumble c : CoreAbility.getAbilities(Crumble.class)) {
				try {
					c.revert();
				} catch (Exception e) {
					getLogger().warning("Failed to revert Crumble ability: " + e.getMessage());
				}
			}
		}
		
		// Performance: Clear caches
		configCache.clear();
		configCacheTimestamps.clear();
		
		getLogger().info("ProjectAddons has been disabled!");
	}
	
	public String prefix() {
		return ChatColor.GRAY + "[" + ChatColor.GREEN + "ProjectAddons" + ChatColor.GRAY + "]";
	}
	
	public String version() {
		return prefix() + " v." + this.getDescription().getVersion();
	}
	
	public Element getSoundElement() {
		return soundElement;
	}

	public ParticleAdapter getParticleAdapter() {
		return this.particleAdapter;
	}

	public PotionEffectAdapter getPotionEffectAdapter() {
		return this.potionEffectAdapter;
	}
	
	@NotNull
	@Override
	public FileConfiguration getConfig() {
		return config;
	}
	
	public FileConfiguration config() {
		return config;
	}
	
	/**
	 * Gets a cached configuration value for optimal performance.
	 * Automatically refreshes cache when TTL expires.
	 * 
	 * @param path Configuration path
	 * @param defaultValue Default value if not found
	 * @return Cached configuration value
	 */
	@SuppressWarnings("unchecked")
	public <T> T getCachedConfig(String path, T defaultValue) {
		long currentTime = System.currentTimeMillis();
		Long timestamp = configCacheTimestamps.get(path);
		
		// Check if cache is valid
		if (timestamp != null && (currentTime - timestamp) < CONFIG_CACHE_TTL) {
			Object cached = configCache.get(path);
			// Safe type conversion for cached values
			if (cached != null && defaultValue != null && cached.getClass().isAssignableFrom(defaultValue.getClass())) {
				return (T) cached;
			}
		}
		
		// Cache miss or expired, fetch from config
		Object rawValue = config.get(path, defaultValue);
		
		// Update cache with raw value
		configCache.put(path, rawValue);
		configCacheTimestamps.put(path, currentTime);
		
		// Safe type conversion for return value
		if (rawValue != null && defaultValue != null && rawValue.getClass().isAssignableFrom(defaultValue.getClass())) {
			return (T) rawValue;
		}
		
		return defaultValue;
	}
	
	/**
	 * Gets a cached boolean configuration value.
	 */
	public boolean getCachedBoolean(String path, boolean defaultValue) {
		return getCachedConfig(path, defaultValue);
	}
	
	/**
	 * Gets a cached long configuration value.
	 */
	public long getCachedLong(String path, long defaultValue) {
		Object cached = getCachedConfig(path, defaultValue);
		if (cached instanceof Number) {
			return ((Number) cached).longValue();
		}
		return defaultValue;
	}
	
	/**
	 * Gets a cached int configuration value.
	 */
	public int getCachedInt(String path, int defaultValue) {
		Object cached = getCachedConfig(path, defaultValue);
		if (cached instanceof Number) {
			return ((Number) cached).intValue();
		}
		return defaultValue;
	}
	
	/**
	 * Gets a cached double configuration value.
	 */
	public double getCachedDouble(String path, double defaultValue) {
		Object cached = getCachedConfig(path, defaultValue);
		if (cached instanceof Number) {
			return ((Number) cached).doubleValue();
		}
		return defaultValue;
	}
	
	/**
	 * Gets a cached string configuration value.
	 */
	public String getCachedString(String path, String defaultValue) {
		return getCachedConfig(path, defaultValue);
	}
	
	/**
	 * Clears the configuration cache. Useful after config reloads.
	 */
	public void clearConfigCache() {
		configCache.clear();
		configCacheTimestamps.clear();
		getLogger().info("Configuration cache cleared");
	}
	
	/**
	 * Gets performance metrics for monitoring.
	 */
	public Map<String, Object> getPerformanceMetrics() {
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("startupTime", startupTime);
		metrics.put("abilityRegistrationTime", abilityRegistrationTime);
		metrics.put("totalAbilitiesRegistered", totalAbilitiesRegistered);
		metrics.put("configCacheSize", configCache.size());
		metrics.put("configCacheHits", configCache.size()); // Simplified metric
		return metrics;
	}
	
	/**
	 * Sets up configuration using the old, reliable method
	 * @return true if config loaded successfully, false otherwise
	 */
	private boolean setupConfig() {
		try {
			File configFile = new File(getDataFolder(), "project_addons.yml");
			if (!configFile.exists()) {
				getDataFolder().mkdirs();
				saveResource("project_addons.yml", false);
				getLogger().info("Created default project_addons.yml configuration file");
			}
			
			this.config = YamlConfiguration.loadConfiguration(configFile);
			
			// Verify config is not empty
			if (this.config.getKeys(false).isEmpty()) {
				getLogger().severe("Configuration file is empty or invalid!");
				return false;
			}
			
			getLogger().info("Configuration loaded successfully using manual method");
			return true;
		} catch (Exception e) {
			getLogger().severe("Failed to load configuration: " + e.getMessage());
			getLogger().severe("Stack trace: " + Arrays.toString(e.getStackTrace()));
			return false;
		}
	}
	
	private void setupCollisions() {
		if (CoreAbility.getAbility(FireDisc.class) != null) {
			ProjectKorra.getCollisionInitializer().addSmallAbility(CoreAbility.getAbility(FireDisc.class));
		}
		
		if (CoreAbility.getAbility(RazorLeaf.class) != null) {
			ProjectKorra.getCollisionInitializer().addSmallAbility(CoreAbility.getAbility(RazorLeaf.class));
		}
		
		if (CoreAbility.getAbility(GaleGust.class) != null) {
			ProjectKorra.getCollisionInitializer().addSmallAbility(CoreAbility.getAbility(GaleGust.class));
		}
		
		if (CoreAbility.getAbility(CombustBeam.class) != null) {
			ProjectKorra.getCollisionInitializer().addLargeAbility(CoreAbility.getAbility(CombustBeam.class));
			ProjectKorra.getCollisionManager().addCollision(new Collision(CoreAbility.getAbility(FireShield.class), CoreAbility.getAbility(CombustBeam.class), false, true));
			ProjectKorra.getCollisionManager().addCollision(new Collision(CoreAbility.getAbility(AirShield.class), CoreAbility.getAbility(CombustBeam.class), false, true));
		}
	}
}
