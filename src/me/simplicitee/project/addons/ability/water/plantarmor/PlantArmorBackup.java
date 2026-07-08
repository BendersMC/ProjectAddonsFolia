package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** Durable on-disk backup of a player's original armor for one PlantArmor session. */
public final class PlantArmorBackup {

	private final UUID playerId;
	private final String sessionId;
	private final long createdAtMillis;
	private final ItemStack[] originalArmor;

	public PlantArmorBackup(UUID playerId, String sessionId, long createdAtMillis, ItemStack[] originalArmor) {
		this.playerId = playerId;
		this.sessionId = sessionId;
		this.createdAtMillis = createdAtMillis;
		this.originalArmor = PlantArmorSession.cloneArmor(originalArmor);
	}

	public UUID playerId() {
		return playerId;
	}

	public String sessionId() {
		return sessionId;
	}

	public long createdAtMillis() {
		return createdAtMillis;
	}

	public ItemStack[] originalArmorClone() {
		return PlantArmorSession.cloneArmor(originalArmor);
	}
}
