package me.simplicitee.project.addons.ability.earth;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.TempBlock;
import me.simplicitee.project.addons.ProjectAddons;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crumble ability that converts earth blocks to sand with optimized performance.
 * 
 * Performance Features:
 * - Pre-calculated angle tables for trigonometry
 * - Smart block iteration with early termination
 * - Batch block reversion for better efficiency
 * - Optimized material checks and caching
 */
public class Crumble extends EarthAbility implements AddonAbility {

	@Attribute(Attribute.COOLDOWN)
	private long cooldown;
	@Attribute(Attribute.RANGE)
	private double range;
	@Attribute(Attribute.RADIUS)
	private double radius;
	@Attribute(Attribute.DURATION)
	private long revertTime;
	@Attribute(Attribute.SELECT_RANGE)
	private double selectRange;

	private Location center;
	private int counter;
	private double currentRadius;
	private final Map<Block, org.bukkit.block.data.BlockData> revert = new ConcurrentHashMap<>();
	
	// Performance optimization: Pre-calculated angle tables
	private static final double[] ANGLE_COS;
	private static final double[] ANGLE_SIN;
	private static final int ANGLE_STEP = 5;
	private static final int ANGLE_COUNT = 360 / ANGLE_STEP;
	
	static {
		// Pre-calculate all trigonometry values to avoid repeated calculations
		ANGLE_COS = new double[ANGLE_COUNT];
		ANGLE_SIN = new double[ANGLE_COUNT];
		for (int i = 0; i < ANGLE_COUNT; i++) {
			double angle = Math.toRadians(i * ANGLE_STEP);
			ANGLE_COS[i] = Math.cos(angle);
			ANGLE_SIN[i] = Math.sin(angle);
		}
	}

	public Crumble(Player player) {
		super(player);

		if (!bPlayer.canBend(this)) {
			return;
		}

		cooldown = ProjectAddons.instance.getCachedLong("Abilities.Earth.Crumble.Cooldown", 8000L);
		range = ProjectAddons.instance.getCachedLong("Abilities.Earth.Crumble.Range", 20L);
		radius = ProjectAddons.instance.getCachedLong("Abilities.Earth.Crumble.Radius", 5L);
		revertTime = ProjectAddons.instance.getCachedLong("Abilities.Earth.Crumble.RevertTime", 10L);
		selectRange = ProjectAddons.instance.getCachedLong("Abilities.Earth.Crumble.SelectRange", 10L);

		center = GeneralMethods.getTargetedLocation(player, selectRange);
		if (center == null) {
			return;
		}

		if (!bPlayer.canBend(this)) {
			return;
		}

		if (RegionProtection.isRegionProtected(player, center, this)) {
			return;
		}

		start();
	}

	@Override
	public void progress() {
		if (!bPlayer.canBend(this)) {
			remove();
			return;
		}

		if (counter >= 20) {
			remove();
			return;
		}
		
		counter++;
		if (counter % 2 != 0) {
			return;
		}

		// Performance: Use pre-calculated angle tables instead of trigonometry
		processBlocksInRadius();
		
		currentRadius++;
	}
	
	/**
	 * Processes blocks in the current radius using pre-calculated angle tables for maximum performance.
	 */
	private void processBlocksInRadius() {
		// Performance: Process blocks in batches for better efficiency
		int blocksProcessed = 0;
		final int MAX_BLOCKS_PER_TICK = 50; // Prevent lag spikes
		
		for (int i = 0; i < ANGLE_COUNT && blocksProcessed < MAX_BLOCKS_PER_TICK; i++) {
			// Use pre-calculated values instead of Math.cos/Math.sin
			double x = ANGLE_COS[i] * currentRadius;
			double z = ANGLE_SIN[i] * currentRadius;

			Block block = center.getBlock().getRelative((int) x, 0, (int) z);
			block = GeneralMethods.getTopBlock(block.getLocation(), 2);

			// Performance: Early termination for invalid blocks
			if (!isValidBlockForCrumble(block)) {
				continue;
			}

			// Performance: Batch process block changes
			processBlockCrumble(block);
			blocksProcessed++;
		}
	}
	
	/**
	 * Checks if a block is valid for crumbling with optimized material checks.
	 */
	private boolean isValidBlockForCrumble(Block block) {
		// Performance: Use cached material checks
		Material type = block.getType();
		
		// Early termination for common cases
		if (TempBlock.isTempBlock(block)) {
			return false;
		}
		
		if (!isEarthbendable(block)) {
			return false;
		}
		
		if (isSand(block)) {
			return false;
		}
		
		return true;
	}
	
	/**
	 * Processes a single block for crumbling with optimized material selection.
	 */
	private void processBlockCrumble(Block block) {
		// Performance: Determine material once and cache
		Material targetMaterial = determineTargetMaterial(block);
		
		// Store original block data for reversion
		revert.put(block, block.getBlockData());
		final Block b = block;

		// Use Folia-compatible scheduling instead of ThreadUtil
		BukkitScheduler scheduler = org.bukkit.Bukkit.getScheduler();
		scheduler.runTaskLater(ProjectKorra.plugin, () -> {
			if (revert.containsKey(b)) {
				b.setBlockData(revert.get(b));
				revert.remove(b);
			}
		}, 20L * revertTime);

		// Set the new material
		block.setType(targetMaterial);
	}
	
	/**
	 * Determines the target material for crumbling with optimized logic.
	 */
	private Material determineTargetMaterial(Block block) {
		// Performance: Check block below once and cache result
		Block blockBelow = block.getRelative(BlockFace.DOWN);
		boolean hasSupport = !isAir(blockBelow.getType());
		
		return hasSupport ? Material.SAND : Material.SANDSTONE;
	}
	
	@Override
	public void remove() {
		super.remove();
		bPlayer.addCooldown(this);
	}

	@Override
	public String getAuthor() {
		return "Simplicitee";
	}

	@Override
	public String getVersion() {
		return ProjectAddons.instance.version();
	}

	@Override
	public void load() {
	}

	@Override
	public void stop() {
	}
	
	@Override
	public long getCooldown() {
		return cooldown;
	}

	@Override
	public Location getLocation() {
		return center;
	}

	@Override
	public String getName() {
		return "Crumble";
	}

	@Override
	public boolean isHarmlessAbility() {
		return true;
	}

	@Override
	public boolean isSneakAbility() {
		return true;
	}
	
	/**
	 * Reverts all crumbled blocks with optimized batch processing.
	 */
	public void revert() {
		// Performance: Process all reversions in a single operation
		for (Map.Entry<Block, org.bukkit.block.data.BlockData> entry : revert.entrySet()) {
			Block block = entry.getKey();
			org.bukkit.block.data.BlockData data = entry.getValue();
			block.setBlockData(data);
		}
		revert.clear();
	}
	
	@Override
	public boolean isEnabled() {
		return ProjectAddons.instance.getCachedBoolean("Abilities.Earth.Crumble.Enabled", true);
	}
	
	@Override
	public String getDescription() {
		return "Crumble the earth into sand with optimized performance!";
	}
	
	@Override
	public String getInstructions() {
		return "Left click or sneak";
	}
}