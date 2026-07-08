package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class PlantArmorListener implements Listener {

	private final PlantArmorService plantArmorService;

	public PlantArmorListener(PlantArmorService plantArmorService) {
		this.plantArmorService = plantArmorService;
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		plantArmorService.scheduleRecoverOnJoin(event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		plantArmorService.handleQuit(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onPlayerDeath(PlayerDeathEvent event) {
		plantArmorService.handleDeath(event.getEntity(), event);
	}

	@EventHandler
	public void onPlayerRespawn(PlayerRespawnEvent event) {
		plantArmorService.handleRespawn(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		if (!plantArmorService.hasActivePlantArmorState(playerId)) {
			return;
		}

		String sessionId = plantArmorService.resolveSessionId(playerId);
		if (sessionId == null) {
			return;
		}

		ItemStack hotbarSwapItem = null;
		if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
			hotbarSwapItem = player.getInventory().getItem(event.getHotbarButton());
		}

		boolean involvesSessionArmor = plantArmorService.involvesSessionPlantArmor(
				sessionId,
				event.getCurrentItem(),
				event.getCursor(),
				hotbarSwapItem);

		if (!involvesSessionArmor && event.getSlotType() == SlotType.ARMOR) {
			involvesSessionArmor = plantArmorService.involvesSessionPlantArmor(sessionId, event.getCurrentItem());
		}

		if (!involvesSessionArmor && isArmorPickupAction(event)) {
			involvesSessionArmor = plantArmorService.involvesSessionPlantArmor(sessionId, event.getCursor(), hotbarSwapItem);
		}

		if (!involvesSessionArmor) {
			return;
		}

		event.setCancelled(true);
		plantArmorService.handleManualArmorRemoval(player);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryClickMonitor(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		plantArmorService.checkArmorIntegrityAfterInventoryChange(player);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInventoryDrag(InventoryDragEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}

		UUID playerId = player.getUniqueId();
		if (!plantArmorService.hasActivePlantArmorState(playerId)) {
			return;
		}

		String sessionId = plantArmorService.resolveSessionId(playerId);
		if (sessionId == null) {
			return;
		}

		for (ItemStack stack : event.getNewItems().values()) {
			if (plantArmorService.involvesSessionPlantArmor(sessionId, stack)) {
				event.setCancelled(true);
				plantArmorService.handleManualArmorRemoval(player);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onInventoryDragMonitor(InventoryDragEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		plantArmorService.checkArmorIntegrityAfterInventoryChange(player);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerDropItem(PlayerDropItemEvent event) {
		Player player = event.getPlayer();
		UUID playerId = player.getUniqueId();
		if (!plantArmorService.hasActivePlantArmorState(playerId)) {
			return;
		}

		String sessionId = plantArmorService.resolveSessionId(playerId);
		if (sessionId == null) {
			return;
		}

		if (!plantArmorService.involvesSessionPlantArmor(sessionId, event.getItemDrop().getItemStack())) {
			return;
		}

		event.setCancelled(true);
		plantArmorService.handleManualArmorRemoval(player);
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
		Player player = event.getPlayer();
		UUID playerId = player.getUniqueId();
		if (!plantArmorService.hasActivePlantArmorState(playerId)) {
			return;
		}

		String sessionId = plantArmorService.resolveSessionId(playerId);
		if (sessionId == null) {
			return;
		}

		if (!plantArmorService.involvesSessionPlantArmor(sessionId, event.getMainHandItem(), event.getOffHandItem())) {
			return;
		}

		event.setCancelled(true);
		plantArmorService.handleManualArmorRemoval(player);
	}

	private static boolean isArmorPickupAction(InventoryClickEvent event) {
		InventoryAction action = event.getAction();
		return event.getSlotType() == SlotType.ARMOR
				&& (action == InventoryAction.PICKUP_ALL
				|| action == InventoryAction.PICKUP_HALF
				|| action == InventoryAction.PICKUP_ONE
				|| action == InventoryAction.PICKUP_SOME
				|| action == InventoryAction.SWAP_WITH_CURSOR
				|| action == InventoryAction.HOTBAR_SWAP
				|| action == InventoryAction.HOTBAR_MOVE_AND_READD
				|| action == InventoryAction.MOVE_TO_OTHER_INVENTORY
				|| action == InventoryAction.COLLECT_TO_CURSOR);
	}
}
