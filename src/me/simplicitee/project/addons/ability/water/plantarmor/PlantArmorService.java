package me.simplicitee.project.addons.ability.water.plantarmor;

import me.simplicitee.project.addons.util.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Coordinates PlantArmor activation and restore. Durable {@link PlantArmorBackupStore} is the source
 * of truth; {@link PlantArmorSession} is an in-memory mirror only.
 *
 * <p>ProjectKorra {@code TempArmor} is not used for armor swap/restore. It cannot guarantee exactly-once
 * restore or durable crash-safe backups, and {@code TempArmor.revert()} can duplicate armor when combined
 * with a separate restore path.
 */
public final class PlantArmorService {

	public record ActivationResult(boolean success, String sessionId) {
		public static ActivationResult failed() {
			return new ActivationResult(false, null);
		}

		public static ActivationResult ok(String sessionId) {
			return new ActivationResult(true, sessionId);
		}
	}

	private final Plugin plugin;
	private final PlantArmorBackupStore backupStore;
	private final PlantArmorSessions sessions;

	public PlantArmorService(Plugin plugin, PlantArmorBackupStore backupStore, PlantArmorSessions sessions) {
		this.plugin = plugin;
		this.backupStore = backupStore;
		this.sessions = sessions;
	}

	public boolean hasActiveSession(UUID playerId) {
		return sessions.contains(playerId);
	}

	public String getSessionId(UUID playerId) {
		PlantArmorSession session = sessions.get(playerId);
		return session == null ? null : session.sessionId();
	}

	public void beginActivation(Player player, ItemStack[] temporaryArmor, Consumer<ActivationResult> callback) {
		SchedulerUtil.runForPlayer(plugin, player, () ->
				callback.accept(beginActivationOnEntityThread(player, temporaryArmor)));
	}

	public void restore(Player player, RestoreReason reason) {
		restore(player, reason, null);
	}

	public void restore(Player player, RestoreReason reason, String expectedSessionId) {
		SchedulerUtil.runForPlayer(plugin, player, () ->
				restoreOnEntityThread(player, reason, expectedSessionId));
	}

	ActivationResult beginActivationOnEntityThread(Player player, ItemStack[] temporaryArmor) {
		UUID playerId = player.getUniqueId();

		if (sessions.contains(playerId)) {
			plugin.getLogger().warning("PlantArmor activation rejected: active session already exists for " + playerId);
			return ActivationResult.failed();
		}
		if (backupStore.hasBackup(playerId)) {
			plugin.getLogger().warning("PlantArmor activation rejected: durable backup already exists for " + playerId);
			return ActivationResult.failed();
		}

		String sessionId = UUID.randomUUID().toString();
		ItemStack[] originalArmor = PlantArmorSession.cloneArmor(player.getInventory().getArmorContents());

		try {
			backupStore.saveBackup(playerId, sessionId, originalArmor);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING,
					"PlantArmor activation aborted: failed to write durable backup for " + playerId, e);
			return ActivationResult.failed();
		}

		long createdAt = System.currentTimeMillis();
		PlantArmorSession session = new PlantArmorSession(playerId, sessionId, originalArmor, createdAt);
		if (sessions.putIfAbsent(playerId, session) != null) {
			plugin.getLogger().warning("PlantArmor activation aborted: concurrent session registered for " + playerId);
			backupStore.deleteBackup(playerId);
			return ActivationResult.failed();
		}

		ItemStack[] equip = PlantArmorSession.cloneArmor(temporaryArmor);
		for (ItemStack stack : equip) {
			if (stack != null) {
				PlantArmorItems.tagPlantArmor(plugin, stack, sessionId);
			}
		}
		player.getInventory().setArmorContents(equip);
		return ActivationResult.ok(sessionId);
	}

	boolean restoreOnEntityThread(Player player, RestoreReason reason, String expectedSessionId) {
		UUID playerId = player.getUniqueId();

		PlantArmorSession session = sessions.get(playerId);
		if (session == null) {
			return false;
		}
		if (expectedSessionId != null && !expectedSessionId.equals(session.sessionId())) {
			plugin.getLogger().fine("PlantArmor restore ignored (" + reason + "): stale session id for " + playerId);
			return false;
		}

		session = sessions.remove(playerId);
		if (session == null) {
			return false;
		}
		if (!session.markRestored()) {
			plugin.getLogger().fine("PlantArmor restore ignored (" + reason + "): already restored for " + playerId);
			return false;
		}

		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			plugin.getLogger().warning("PlantArmor restore ambiguous (" + reason + "): no durable backup for " + playerId
					+ "; leaving any on-disk state untouched");
			return false;
		}
		if (!backup.sessionId().equals(session.sessionId())) {
			plugin.getLogger().warning("PlantArmor restore ambiguous (" + reason + "): backup session id mismatch for "
					+ playerId + "; backup left in place");
			return false;
		}

		if (!reconcileArmorSlots(player, session.sessionId())) {
			plugin.getLogger().warning("PlantArmor restore ambiguous (" + reason + "): could not reconcile armor slots for "
					+ playerId + "; backup left in place");
			return false;
		}

		player.getInventory().setArmorContents(backup.originalArmorClone());

		if (!backupStore.deleteBackup(playerId)) {
			plugin.getLogger().warning("PlantArmor restore completed but failed to delete backup for " + playerId);
		}
		return true;
	}

	private boolean reconcileArmorSlots(Player player, String sessionId) {
		ItemStack[] current = player.getInventory().getArmorContents();
		Map<Integer, ItemStack> overflow = new HashMap<>();

		for (int i = 0; i < current.length; i++) {
			ItemStack slot = current[i];
			if (slot == null) {
				continue;
			}
			if (PlantArmorItems.isPlantArmorForSession(plugin, slot, sessionId)) {
				continue;
			}
			if (PlantArmorItems.isPlantArmorItem(plugin, slot)) {
				plugin.getLogger().warning("PlantArmor restore: unexpected PlantArmor from another session in slot " + i
						+ " for " + player.getUniqueId());
				return false;
			}
			Map<Integer, ItemStack> leftover = player.getInventory().addItem(slot.clone());
			overflow.putAll(leftover);
			current[i] = null;
		}

		player.getInventory().setArmorContents(current);

		if (!overflow.isEmpty()) {
			dropOverflow(player, overflow);
		}
		return true;
	}

	private void dropOverflow(Player player, Map<Integer, ItemStack> overflow) {
		SchedulerUtil.runForLocation(plugin, player.getLocation(), () -> {
			for (ItemStack stack : overflow.values()) {
				if (stack != null && !stack.getType().isAir()) {
					player.getWorld().dropItemNaturally(player.getLocation(), stack);
				}
			}
		});
	}
}
