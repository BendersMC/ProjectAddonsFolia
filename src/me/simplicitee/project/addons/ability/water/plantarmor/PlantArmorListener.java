package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

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
}
