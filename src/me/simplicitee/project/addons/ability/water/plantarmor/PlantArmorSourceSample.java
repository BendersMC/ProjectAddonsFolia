package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.Material;
import org.bukkit.block.Biome;

/**
 * Lightweight snapshot of a plant source consumed during PlantArmor formation.
 * No block or location references are retained.
 */
public record PlantArmorSourceSample(Material material, Biome biome, int order) {
}
