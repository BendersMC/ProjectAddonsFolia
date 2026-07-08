package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.Biome;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlantArmorStyleResolver {

	enum SourceCategory {
		CHERRY_LEAVES(0),
		PALE_OAK_LEAVES(1),
		FLOWERING_AZALEA_LEAVES(2),
		AZALEA_LEAVES(3),
		PALE_MOSS(4),
		MOSS(5),
		CACTUS_GROUP(6),
		MANGROVE_LEAVES(7),
		SPRUCE_LEAVES(8),
		BIRCH_LEAVES(9),
		JUNGLE_LEAVES(10),
		DARK_OAK_LEAVES(11),
		ACACIA_LEAVES(12),
		OAK_LEAVES(13),
		VINE(14),
		FERN(15),
		GRASS(16),
		WATER_PLANT(17),
		BAMBOO_GROUP(18),
		GENERAL_FOLIAGE(19),
		DEFAULT(20);

		private final int tiePriority;

		SourceCategory(int tiePriority) {
			this.tiePriority = tiePriority;
		}

		int tiePriority() {
			return tiePriority;
		}
	}

	private PlantArmorStyleResolver() {}

	public static PlantArmorStyle resolve(List<PlantArmorSourceSample> samples) {
		if (samples == null || samples.isEmpty()) {
			return defaultStyle(null);
		}

		Map<SourceCategory, Integer> counts = new EnumMap<>(SourceCategory.class);
		Map<SourceCategory, Integer> earliestOrder = new EnumMap<>(SourceCategory.class);
		Map<SourceCategory, Map<Biome, Integer>> biomeCounts = new EnumMap<>(SourceCategory.class);
		Map<SourceCategory, Biome> earliestBiome = new EnumMap<>(SourceCategory.class);
		Material dominantMaterial = samples.get(0).material();

		for (PlantArmorSourceSample sample : samples) {
			SourceCategory category = classify(sample.material());
			counts.merge(category, 1, Integer::sum);
			earliestOrder.merge(category, sample.order(), Math::min);

			if (sample.biome() != null) {
				biomeCounts.computeIfAbsent(category, key -> new HashMap<>())
						.merge(sample.biome(), 1, Integer::sum);
				earliestBiome.putIfAbsent(category, sample.biome());
			}
		}

		SourceCategory dominant = pickDominantCategory(counts, earliestOrder);
		for (PlantArmorSourceSample sample : samples) {
			if (classify(sample.material()) == dominant) {
				dominantMaterial = sample.material();
				break;
			}
		}

		Biome representativeBiome = pickRepresentativeBiome(dominant, biomeCounts, earliestBiome, samples);
		Material helmet = resolveHelmet(dominant);
		Material structure = resolveStructureMaterial(dominant);
		Color leather = resolveLeatherColor(dominant, dominantMaterial, representativeBiome);
		boolean thorns = dominant == SourceCategory.CACTUS_GROUP;
		String styleName = dominant.name().toLowerCase(Locale.ROOT);

		return new PlantArmorStyle(helmet, structure, leather, styleName, dominant.name(), representativeBiome,
				thorns, thorns ? 2 : 0);
	}

	public static boolean isCactusSource(Material material) {
		return classify(material) == SourceCategory.CACTUS_GROUP;
	}

	public static int countCactusSources(List<PlantArmorSourceSample> samples) {
		if (samples == null || samples.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (PlantArmorSourceSample sample : samples) {
			if (isCactusSource(sample.material())) {
				count++;
			}
		}
		return count;
	}

	public static boolean wouldResolveCactus(List<PlantArmorSourceSample> samples) {
		if (samples == null || samples.isEmpty()) {
			return false;
		}
		return SourceCategory.CACTUS_GROUP.name().equals(resolve(samples).dominantCategory());
	}

	public static int cactusFormationThreshold(int requiredPlants) {
		return Math.min(3, requiredPlants);
	}

	public static boolean hasEnoughCactusForEarlyCompletion(List<PlantArmorSourceSample> samples, int requiredPlants) {
		return countCactusSources(samples) >= cactusFormationThreshold(requiredPlants)
				&& wouldResolveCactus(samples);
	}

	public static boolean isDryDeadPlantBiome(Biome biome) {
		if (biome == null) {
			return false;
		}
		String name = biome.name();
		return name.contains("DESERT")
				|| name.contains("BADLANDS")
				|| name.contains("SAVANNA");
	}

	public static Material resolveLeafStructureMaterial(Biome biome, PlantArmorStyle activeStyle) {
		if (activeStyle != null) {
			return activeStyle.structureMaterial();
		}
		if (isDryDeadPlantBiome(biome)) {
			return PlantArmorMaterials.mangroveRoots();
		}
		return Material.OAK_LEAVES;
	}

	private static SourceCategory pickDominantCategory(Map<SourceCategory, Integer> counts,
			Map<SourceCategory, Integer> earliestOrder) {
		int maxCount = 0;
		for (int count : counts.values()) {
			maxCount = Math.max(maxCount, count);
		}

		SourceCategory best = SourceCategory.DEFAULT;
		int bestPriority = Integer.MAX_VALUE;
		int bestOrder = Integer.MAX_VALUE;

		for (Map.Entry<SourceCategory, Integer> entry : counts.entrySet()) {
			if (entry.getValue() < maxCount) {
				continue;
			}
			SourceCategory category = entry.getKey();
			int priority = category.tiePriority();
			int order = earliestOrder.getOrDefault(category, Integer.MAX_VALUE);
			if (priority < bestPriority || (priority == bestPriority && order < bestOrder)) {
				best = category;
				bestPriority = priority;
				bestOrder = order;
			}
		}
		return best;
	}

	private static Biome pickRepresentativeBiome(SourceCategory dominant,
			Map<SourceCategory, Map<Biome, Integer>> biomeCounts,
			Map<SourceCategory, Biome> earliestBiome,
			List<PlantArmorSourceSample> samples) {
		Map<Biome, Integer> dominantBiomes = biomeCounts.get(dominant);
		if (dominantBiomes == null || dominantBiomes.isEmpty()) {
			for (PlantArmorSourceSample sample : samples) {
				if (classify(sample.material()) == dominant && sample.biome() != null) {
					return sample.biome();
				}
			}
			return null;
		}

		int max = 0;
		for (int count : dominantBiomes.values()) {
			max = Math.max(max, count);
		}

		Biome best = earliestBiome.get(dominant);
		int bestOrder = Integer.MAX_VALUE;
		for (PlantArmorSourceSample sample : samples) {
			if (classify(sample.material()) != dominant || sample.biome() == null) {
				continue;
			}
			if (dominantBiomes.getOrDefault(sample.biome(), 0) < max) {
				continue;
			}
			if (sample.order() < bestOrder) {
				bestOrder = sample.order();
				best = sample.biome();
			}
		}
		return best;
	}

	static SourceCategory classify(Material material) {
		if (material == null) {
			return SourceCategory.DEFAULT;
		}

		return switch (material) {
			case CHERRY_LEAVES -> SourceCategory.CHERRY_LEAVES;
			case PALE_OAK_LEAVES -> SourceCategory.PALE_OAK_LEAVES;
			case FLOWERING_AZALEA_LEAVES -> SourceCategory.FLOWERING_AZALEA_LEAVES;
			case AZALEA_LEAVES -> SourceCategory.AZALEA_LEAVES;
			case MANGROVE_LEAVES -> SourceCategory.MANGROVE_LEAVES;
			case SPRUCE_LEAVES -> SourceCategory.SPRUCE_LEAVES;
			case BIRCH_LEAVES -> SourceCategory.BIRCH_LEAVES;
			case JUNGLE_LEAVES -> SourceCategory.JUNGLE_LEAVES;
			case DARK_OAK_LEAVES -> SourceCategory.DARK_OAK_LEAVES;
			case ACACIA_LEAVES -> SourceCategory.ACACIA_LEAVES;
			case OAK_LEAVES -> SourceCategory.OAK_LEAVES;
			case CACTUS, CACTUS_FLOWER -> SourceCategory.CACTUS_GROUP;
			case VINE, GLOW_LICHEN, TWISTING_VINES, TWISTING_VINES_PLANT, WEEPING_VINES, WEEPING_VINES_PLANT,
					CAVE_VINES, CAVE_VINES_PLANT -> SourceCategory.VINE;
			case FERN, LARGE_FERN -> SourceCategory.FERN;
			case SHORT_GRASS, TALL_GRASS, GRASS_BLOCK -> SourceCategory.GRASS;
			case KELP, KELP_PLANT, SEAGRASS, TALL_SEAGRASS -> SourceCategory.WATER_PLANT;
			case BAMBOO, BAMBOO_SAPLING, SUGAR_CANE -> SourceCategory.BAMBOO_GROUP;
			default -> classifyByName(material.name());
		};
	}

	private static SourceCategory classifyByName(String name) {
		if (name.contains("PALE_MOSS")) {
			return SourceCategory.PALE_MOSS;
		}
		if (name.contains("MOSS")) {
			return SourceCategory.MOSS;
		}
		if (name.contains("CACTUS")) {
			return SourceCategory.CACTUS_GROUP;
		}
		if (name.endsWith("_LEAVES")) {
			return SourceCategory.GENERAL_FOLIAGE;
		}
		if (name.contains("VINE") || name.contains("LICHEN")) {
			return SourceCategory.VINE;
		}
		if (name.contains("FERN")) {
			return SourceCategory.FERN;
		}
		if (name.contains("GRASS")) {
			return SourceCategory.GRASS;
		}
		if (name.contains("KELP") || name.contains("SEAGRASS")) {
			return SourceCategory.WATER_PLANT;
		}
		if (name.contains("BAMBOO") || name.contains("SUGAR_CANE")) {
			return SourceCategory.BAMBOO_GROUP;
		}
		return SourceCategory.GENERAL_FOLIAGE;
	}

	private static Material resolveHelmet(SourceCategory category) {
		return switch (category) {
			case CHERRY_LEAVES -> Material.CHERRY_LEAVES;
			case PALE_OAK_LEAVES, PALE_MOSS -> PlantArmorMaterials.paleOakLeaves();
			case FLOWERING_AZALEA_LEAVES -> Material.FLOWERING_AZALEA_LEAVES;
			case AZALEA_LEAVES -> Material.AZALEA_LEAVES;
			case MANGROVE_LEAVES -> Material.MANGROVE_LEAVES;
			case SPRUCE_LEAVES -> Material.SPRUCE_LEAVES;
			case BIRCH_LEAVES -> Material.BIRCH_LEAVES;
			case JUNGLE_LEAVES -> Material.JUNGLE_LEAVES;
			case DARK_OAK_LEAVES -> Material.DARK_OAK_LEAVES;
			case ACACIA_LEAVES -> Material.ACACIA_LEAVES;
			case OAK_LEAVES -> Material.OAK_LEAVES;
			case MOSS -> Material.MOSS_BLOCK;
			case CACTUS_GROUP -> PlantArmorMaterials.firstAvailable(
					PlantArmorMaterials.cactusFlower(),
					PlantArmorMaterials.cactus(),
					Material.OAK_LEAVES);
			default -> Material.OAK_LEAVES;
		};
	}

	private static Material resolveStructureMaterial(SourceCategory category) {
		return switch (category) {
			case CHERRY_LEAVES -> Material.CHERRY_LEAVES;
			case PALE_OAK_LEAVES, PALE_MOSS -> PlantArmorMaterials.paleOakLeaves();
			case FLOWERING_AZALEA_LEAVES -> Material.FLOWERING_AZALEA_LEAVES;
			case AZALEA_LEAVES -> Material.AZALEA_LEAVES;
			case MANGROVE_LEAVES -> Material.MANGROVE_LEAVES;
			case SPRUCE_LEAVES -> Material.SPRUCE_LEAVES;
			case BIRCH_LEAVES -> Material.BIRCH_LEAVES;
			case JUNGLE_LEAVES -> Material.JUNGLE_LEAVES;
			case DARK_OAK_LEAVES -> Material.DARK_OAK_LEAVES;
			case ACACIA_LEAVES -> Material.ACACIA_LEAVES;
			case OAK_LEAVES -> Material.OAK_LEAVES;
			case MOSS -> Material.MOSS_BLOCK;
			case CACTUS_GROUP -> PlantArmorMaterials.mangroveRoots();
			default -> Material.OAK_LEAVES;
		};
	}

	private static Color resolveLeatherColor(SourceCategory category, Material dominantMaterial, Biome biome) {
		Color fixed = fixedLeafColor(category);
		if (fixed != null) {
			return fixed;
		}

		if (category == SourceCategory.MANGROVE_LEAVES) {
			if (biomeNameContains(biome, "MANGROVE_SWAMP")) {
				return hex("#4F7A3D");
			}
			return hex("#6B8F3A");
		}

		if (category == SourceCategory.PALE_MOSS || biomeNameEquals(biome, "PALE_GARDEN")) {
			return hex("#D8D2BE");
		}

		if (usesBiomeTint(category)) {
			return biomeTint(biome);
		}

		return categoryFallbackColor(category, dominantMaterial);
	}

	private static Color fixedLeafColor(SourceCategory category) {
		return switch (category) {
			case CHERRY_LEAVES -> hex("#F7A8C8");
			case PALE_OAK_LEAVES -> hex("#D8D2BE");
			case SPRUCE_LEAVES -> hex("#619961");
			case BIRCH_LEAVES -> hex("#80A755");
			case AZALEA_LEAVES -> hex("#75AB4D");
			case FLOWERING_AZALEA_LEAVES -> hex("#D98AA8");
			case MOSS -> hex("#5E8F3D");
			case PALE_MOSS -> hex("#D8D2BE");
			case FERN -> hex("#6FAE42");
			case CACTUS_GROUP -> hex("#588A3B");
			default -> null;
		};
	}

	private static boolean usesBiomeTint(SourceCategory category) {
		return switch (category) {
			case OAK_LEAVES, JUNGLE_LEAVES, ACACIA_LEAVES, DARK_OAK_LEAVES, VINE, GRASS, GENERAL_FOLIAGE -> true;
			default -> false;
		};
	}

	private static Color categoryFallbackColor(SourceCategory category, Material dominantMaterial) {
		return switch (category) {
			case GRASS -> hex("#91BD59");
			case GENERAL_FOLIAGE -> hex("#77AB2F");
			case VINE -> hex("#4F8F34");
			case WATER_PLANT -> waterPlantColor(dominantMaterial);
			case BAMBOO_GROUP -> bambooGroupColor(dominantMaterial);
			default -> hex("#77AB2F");
		};
	}

	private static Color waterPlantColor(Material material) {
		if (material != null && material.name().contains("KELP")) {
			return hex("#3F7F4A");
		}
		return hex("#4E9A5B");
	}

	private static Color bambooGroupColor(Material material) {
		if (material == null) {
			return hex("#7BAF3A");
		}
		String name = material.name();
		if (name.contains("SUGAR_CANE")) {
			return hex("#A4C75A");
		}
		return hex("#7BAF3A");
	}

	private static Color biomeTint(Biome biome) {
		if (biome == null) {
			return hex("#77AB2F");
		}
		String name = biome.name();
		if (name.contains("CHERRY_GROVE")) {
			return hex("#F7A8C8");
		}
		if (name.contains("PALE_GARDEN")) {
			return hex("#D8D2BE");
		}
		if (name.contains("MANGROVE_SWAMP")) {
			return hex("#4F7A3D");
		}
		if (name.contains("SWAMP")) {
			return hex("#6A7039");
		}
		if (name.contains("BAMBOO_JUNGLE") || name.equals("JUNGLE")) {
			return hex("#537B2F");
		}
		if (name.contains("SPARSE_JUNGLE")) {
			return hex("#6FA233");
		}
		if (name.contains("JUNGLE")) {
			return hex("#537B2F");
		}
		if (name.contains("DARK_FOREST")) {
			return hex("#507A32");
		}
		if (name.contains("FLOWER_FOREST")) {
			return hex("#79B53A");
		}
		if (name.contains("FOREST")) {
			return hex("#77AB2F");
		}
		if (name.contains("OLD_GROWTH_BIRCH") || name.contains("BIRCH_FOREST")) {
			return hex("#80A755");
		}
		if (name.contains("OLD_GROWTH_PINE") || name.contains("OLD_GROWTH_SPRUCE") || name.contains("TAIGA")) {
			return hex("#619961");
		}
		if (name.contains("SNOWY_TAIGA") || name.contains("SNOWY_PLAINS") || name.contains("SNOWY_SLOPES")
				|| name.equals("GROVE")) {
			return hex("#A7B58A");
		}
		if (name.contains("WINDSWEPT_SAVANNA")) {
			return hex("#AEA42A");
		}
		if (name.contains("SAVANNA")) {
			return hex("#BFB755");
		}
		if (name.contains("DESERT")) {
			return hex("#BFB755");
		}
		if (name.contains("BADLANDS")) {
			return hex("#9E8148");
		}
		if (name.contains("LUSH_CAVES")) {
			return hex("#5AA832");
		}
		if (name.contains("MUSHROOM_FIELDS")) {
			return hex("#55C93F");
		}
		if (name.contains("MEADOW")) {
			return hex("#83BB4F");
		}
		if (name.contains("RIVER")) {
			return hex("#8EB971");
		}
		if (name.contains("BEACH")) {
			return hex("#91BD59");
		}
		if (name.contains("PLAINS")) {
			return hex("#91BD59");
		}
		return hex("#77AB2F");
	}

	private static PlantArmorStyle defaultStyle(Biome biome) {
		return new PlantArmorStyle(Material.OAK_LEAVES, Material.OAK_LEAVES, biomeTint(biome), "default",
				SourceCategory.DEFAULT.name(), biome, false, 0);
	}

	private static boolean biomeNameEquals(Biome biome, String expected) {
		return biome != null && expected.equals(biome.name());
	}

	private static boolean biomeNameContains(Biome biome, String fragment) {
		return biome != null && biome.name().contains(fragment);
	}

	private static Color hex(String value) {
		return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
	}
}
