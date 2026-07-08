package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class PlantArmorItems {

	private static final byte PLANT_ARMOR_MARKER = (byte) 1;

	private PlantArmorItems() {}

	public static NamespacedKey plantArmorKey(Plugin plugin) {
		return new NamespacedKey(plugin, "plant_armor");
	}

	public static NamespacedKey sessionKey(Plugin plugin) {
		return new NamespacedKey(plugin, "plant_armor_session");
	}

	/**
	 * Tags {@code item} in place; caller must pass a dedicated temporary stack, not a live inventory reference.
	 * Real temporary armor is used for PlantArmor behavior. Packet-only fake armor is not implemented here
	 * because restore correctness depends on disposable PDC-tagged physical temp pieces.
	 */
	public static void tagPlantArmor(Plugin plugin, ItemStack item, String sessionId) {
		if (item == null) {
			return;
		}
		item.editMeta(meta -> {
			PersistentDataContainer pdc = meta.getPersistentDataContainer();
			pdc.set(plantArmorKey(plugin), PersistentDataType.BYTE, PLANT_ARMOR_MARKER);
			pdc.set(sessionKey(plugin), PersistentDataType.STRING, sessionId);
		});
	}

	public static boolean isPlantArmorItem(Plugin plugin, ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return false;
		}
		Byte marker = item.getItemMeta().getPersistentDataContainer()
				.get(plantArmorKey(plugin), PersistentDataType.BYTE);
		return marker != null && marker == PLANT_ARMOR_MARKER;
	}

	public static boolean isPlantArmorForSession(Plugin plugin, ItemStack item, String sessionId) {
		if (sessionId == null || !isPlantArmorItem(plugin, item)) {
			return false;
		}
		return sessionId.equals(getSessionId(plugin, item));
	}

	public static String getSessionId(Plugin plugin, ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		return item.getItemMeta().getPersistentDataContainer()
				.get(sessionKey(plugin), PersistentDataType.STRING);
	}

	public static void stripPlantArmorTag(Plugin plugin, ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return;
		}
		ItemMeta meta = item.getItemMeta();
		if (meta == null) {
			return;
		}
		PersistentDataContainer pdc = meta.getPersistentDataContainer();
		pdc.remove(plantArmorKey(plugin));
		pdc.remove(sessionKey(plugin));
		item.setItemMeta(meta);
	}
}
