package me.simplicitee.project.addons.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SchedulerUtil {

	private SchedulerUtil() {}

	public static void runForPlayer(Plugin plugin, Player player, Runnable runnable) {
		player.getScheduler().execute(plugin, runnable, null, 0L);
	}

	public static void runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
		entity.getScheduler().execute(plugin, runnable, null, 0L);
	}

	public static void runForLocation(Plugin plugin, Location location, Runnable runnable) {
		Bukkit.getRegionScheduler().execute(plugin, location, runnable);
	}

	public static void runGlobal(Plugin plugin, Runnable runnable) {
		Bukkit.getGlobalRegionScheduler().run(plugin, task -> runnable.run());
	}
}
