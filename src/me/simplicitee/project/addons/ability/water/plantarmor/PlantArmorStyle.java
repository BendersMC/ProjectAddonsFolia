package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.Biome;

public final class PlantArmorStyle {

	private final Material helmetMaterial;
	private final Material structureMaterial;
	private final Color leatherColor;
	private final String styleName;
	private final String dominantCategory;
	private final Biome representativeBiome;
	private final boolean thorns;
	private final int thornsLevel;

	public PlantArmorStyle(Material helmetMaterial, Material structureMaterial, Color leatherColor, String styleName,
			String dominantCategory, Biome representativeBiome, boolean thorns, int thornsLevel) {
		this.helmetMaterial = helmetMaterial;
		this.structureMaterial = structureMaterial;
		this.leatherColor = leatherColor;
		this.styleName = styleName;
		this.dominantCategory = dominantCategory;
		this.representativeBiome = representativeBiome;
		this.thorns = thorns;
		this.thornsLevel = thornsLevel;
	}

	public Material helmetMaterial() {
		return helmetMaterial;
	}

	public Material structureMaterial() {
		return structureMaterial;
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

	public boolean thorns() {
		return thorns;
	}

	public int thornsLevel() {
		return thornsLevel;
	}
}
