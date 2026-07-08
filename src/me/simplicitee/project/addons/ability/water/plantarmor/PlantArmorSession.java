package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory mirror of an active PlantArmor session. Durable {@link PlantArmorBackupStore} is the
 * source of truth across restart/crash; this object must not be the only record of original armor.
 */
public final class PlantArmorSession {

	private final UUID playerId;
	private final String sessionId;
	private final ItemStack[] originalArmor;
	private final long createdAtMillis;
	private final int expectedTaggedArmorCount;
	private final AtomicBoolean restored = new AtomicBoolean(false);

	public PlantArmorSession(UUID playerId, String sessionId, ItemStack[] originalArmor, long createdAtMillis,
			int expectedTaggedArmorCount) {
		this.playerId = playerId;
		this.sessionId = sessionId;
		this.originalArmor = cloneArmor(originalArmor);
		this.createdAtMillis = createdAtMillis;
		this.expectedTaggedArmorCount = expectedTaggedArmorCount;
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

	public int expectedTaggedArmorCount() {
		return expectedTaggedArmorCount;
	}

	public ItemStack[] originalArmorClone() {
		return cloneArmor(originalArmor);
	}

	public boolean markRestored() {
		return restored.compareAndSet(false, true);
	}

	public boolean isRestored() {
		return restored.get();
	}

	public static ItemStack[] cloneArmor(ItemStack[] armor) {
		ItemStack[] clone = new ItemStack[armor.length];
		for (int i = 0; i < armor.length; i++) {
			clone[i] = armor[i] == null ? null : armor[i].clone();
		}
		return clone;
	}
}
