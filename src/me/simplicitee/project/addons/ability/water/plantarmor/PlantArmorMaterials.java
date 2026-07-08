package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.Material;

/**
 * Safe material resolution for PlantArmor visuals. Uses {@link Material#matchMaterial(String)}
 * so missing constants fall back without compile-time guessing.
 */
public final class PlantArmorMaterials {

	private static final Material PALE_OAK_LEAVES = match("PALE_OAK_LEAVES", Material.OAK_LEAVES);
	private static final Material PALE_MOSS_BLOCK = match("PALE_MOSS_BLOCK", Material.OAK_LEAVES);
	private static final Material CACTUS_FLOWER = match("CACTUS_FLOWER", Material.CACTUS);
	private static final Material MANGROVE_ROOTS = match("MANGROVE_ROOTS", Material.OAK_LEAVES);

	private PlantArmorMaterials() {}

	public static Material paleOakLeaves() {
		return PALE_OAK_LEAVES;
	}

	public static Material paleMossBlock() {
		return PALE_MOSS_BLOCK;
	}

	public static Material cactusFlower() {
		return CACTUS_FLOWER;
	}

	public static Material cactus() {
		return Material.CACTUS;
	}

	public static Material mangroveRoots() {
		return MANGROVE_ROOTS;
	}

	public static Material firstAvailable(Material primary, Material secondary, Material fallback) {
		if (isHelmetMaterial(primary)) {
			return primary;
		}
		if (isHelmetMaterial(secondary)) {
			return secondary;
		}
		return fallback;
	}

	private static boolean isHelmetMaterial(Material material) {
		return material != null && material != Material.AIR && material.isItem();
	}

	private static Material match(String name, Material fallback) {
		Material matched = Material.matchMaterial(name);
		return matched != null ? matched : fallback;
	}
}
