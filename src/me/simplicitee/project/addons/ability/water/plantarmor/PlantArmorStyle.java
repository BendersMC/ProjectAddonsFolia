package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.Biome;

public final class PlantArmorStyle {

	private final Material helmetMaterial;
	private final Color leatherColor;
	private final String styleName;
	private final String dominantCategory;
	private final Biome representativeBiome;

	public PlantArmorStyle(Material helmetMaterial, Color leatherColor, String styleName,
			String dominantCategory, Biome representativeBiome) {
		this.helmetMaterial = helmetMaterial;
		this.leatherColor = leatherColor;
		this.styleName = styleName;
		this.dominantCategory = dominantCategory;
		this.representativeBiome = representativeBiome;
	}

	public Material helmetMaterial() {
		return helmetMaterial;
	}

	public Color leatherColor() {
		return leatherColor;
	}

	public String styleName() {
		return styleName;
	}

	public String dominantCategory() {
		return dominantCategory;
	}

	public Biome representativeBiome() {
		return representativeBiome;
	}
}
