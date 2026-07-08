package me.simplicitee.project.addons.ability.water.plantarmor;

import com.projectkorra.projectkorra.ability.CoreAbility;
import me.simplicitee.project.addons.ability.water.PlantArmor;
import me.simplicitee.project.addons.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

	private static final long JOIN_RECOVERY_DELAY_TICKS = 5L;

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
	private final ConcurrentHashMap<UUID, AtomicBoolean> restoreClaims = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, AtomicBoolean> manualRemovalInProgress = new ConcurrentHashMap<>();

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

	public boolean hasBackup(UUID playerId) {
		return backupStore.hasBackup(playerId);
	}

	public void beginActivation(Player player, ItemStack[] temporaryArmor, Consumer<ActivationResult> callback) {
		SchedulerUtil.runForPlayer(plugin, player, () ->
				callback.accept(beginActivationOnEntityThread(player, temporaryArmor)));
	}

	public void restore(Player player, RestoreReason reason) {
		endPlantArmor(player, reason, null);
	}

	public void restore(Player player, RestoreReason reason, String expectedSessionId) {
		endPlantArmor(player, reason, expectedSessionId);
	}

	/**
	 * Single authoritative end path for all PlantArmor restore requests.
	 * Uses the durable backup as source of truth; does not require the ability instance or in-memory session.
	 */
	public void endPlantArmor(Player player, RestoreReason reason, String expectedSessionId) {
		SchedulerUtil.runForPlayer(plugin, player, () -> restoreOnEntityThread(player, reason, expectedSessionId));
	}

	public boolean hasActivePlantArmorState(UUID playerId) {
		return sessions.contains(playerId) || backupStore.hasBackup(playerId);
	}

	public String resolveSessionId(UUID playerId) {
		String sessionId = getSessionId(playerId);
		if (sessionId != null) {
			return sessionId;
		}
		if (!backupStore.hasBackup(playerId)) {
			return null;
		}
		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		return backup == null ? null : backup.sessionId();
	}

	public boolean involvesSessionPlantArmor(String sessionId, ItemStack... items) {
		if (sessionId == null) {
			return false;
		}
		for (ItemStack item : items) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, item, sessionId)) {
				return true;
			}
		}
		return false;
	}

	public void handleManualArmorRemoval(Player player) {
		SchedulerUtil.runForPlayer(plugin, player, () -> handleManualArmorRemovalOnEntityThread(player));
	}

	public void checkArmorIntegrityAfterInventoryChange(Player player) {
		UUID playerId = player.getUniqueId();
		PlantArmorSession session = sessions.get(playerId);
		if (session == null) {
			return;
		}
		if (countSessionArmorPieces(player, session.sessionId()) < session.expectedTaggedArmorCount()) {
			handleManualArmorRemovalOnEntityThread(player);
		}
	}

	public void scheduleRecoverOnJoin(Player player) {
		SchedulerUtil.runForPlayerDelayed(plugin, player, () -> recoverOnJoin(player), JOIN_RECOVERY_DELAY_TICKS);
	}

	public void recoverOnJoin(Player player) {
		UUID playerId = player.getUniqueId();
		if (!backupStore.hasBackup(playerId)) {
			return;
		}

		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			return;
		}

		if (currentArmorMatchesBackup(player, backup)) {
			cleanupStaleBackupIfAlreadyRestored(player);
			sessions.remove(playerId);
			return;
		}

		if (hasMatchingTaggedPlantArmor(player, backup.sessionId())) {
			restoreFromBackup(player, backup, RestoreReason.JOIN);
			return;
		}

		if (hasAnyPlantArmor(player)) {
			logAmbiguous(playerId, backup.sessionId(), RestoreReason.JOIN,
					"player wears PlantArmor tagged with a different session id");
		} else {
			plugin.getLogger().info("PlantArmor join recovery: restoring from unresolved backup for " + playerId);
			restoreOnEntityThread(player, RestoreReason.JOIN, backup.sessionId());
		}
	}

	public boolean restoreFromBackup(Player player, PlantArmorBackup backup, RestoreReason reason) {
		return restoreFromBackupOnEntityThread(player, backup, reason, false);
	}

	public boolean currentArmorMatchesBackup(Player player, PlantArmorBackup backup) {
		ItemStack[] current = player.getInventory().getArmorContents();
		ItemStack[] expected = backup.originalArmorClone();
		if (current.length != expected.length) {
			return false;
		}
		for (int i = 0; i < current.length; i++) {
			if (!stacksEqual(current[i], expected[i])) {
				return false;
			}
		}
		return true;
	}

	public boolean hasMatchingTaggedPlantArmor(Player player, String sessionId) {
		return countSessionPlantArmorItems(player, sessionId) > 0;
	}

	public int countSessionPlantArmorItems(Player player, String sessionId) {
		if (sessionId == null) {
			return 0;
		}
		int count = 0;
		for (ItemStack stack : player.getInventory().getArmorContents()) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, stack, sessionId)) {
				count++;
			}
		}
		for (ItemStack stack : player.getInventory().getStorageContents()) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, stack, sessionId)) {
				count++;
			}
		}
		if (PlantArmorItems.isPlantArmorForSession(plugin, player.getInventory().getItemInOffHand(), sessionId)) {
			count++;
		}
		if (player.getOpenInventory() != null) {
			ItemStack cursor = player.getOpenInventory().getCursor();
			if (PlantArmorItems.isPlantArmorForSession(plugin, cursor, sessionId)) {
				count++;
			}
		}
		return count;
	}

	public boolean cleanupStaleBackupIfAlreadyRestored(Player player) {
		UUID playerId = player.getUniqueId();
		if (!backupStore.hasBackup(playerId)) {
			return false;
		}
		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null || !currentArmorMatchesBackup(player, backup)) {
			return false;
		}
		if (!backupStore.deleteBackup(playerId)) {
			plugin.getLogger().warning("PlantArmor stale backup cleanup failed for " + playerId);
			return false;
		}
		sessions.remove(playerId);
		releaseRestoreClaim(playerId);
		return true;
	}

	public void handleQuit(Player player) {
		UUID playerId = player.getUniqueId();
		if (!backupStore.hasBackup(playerId) && !sessions.contains(playerId)) {
			return;
		}

		try {
			player.getScheduler().execute(plugin, () -> {
				if (!player.isOnline()) {
					return;
				}
				PlantArmorBackup backup = backupStore.loadBackup(playerId);
				if (backup != null) {
					restoreFromBackupOnEntityThread(player, backup, RestoreReason.QUIT, false);
				} else {
					String sessionId = getSessionId(playerId);
					if (sessionId != null) {
						restoreOnEntityThread(player, RestoreReason.QUIT, sessionId);
					}
				}
			}, null, 0L);
		} catch (Exception e) {
			plugin.getLogger().log(Level.WARNING,
					"PlantArmor quit restore could not be scheduled for " + playerId + "; backup retained", e);
		}
	}

	/**
	 * Death is handled synchronously on the player's entity thread so armor is restored before drops finalize.
	 */
	public void handleDeath(Player player, PlayerDeathEvent event) {
		UUID playerId = player.getUniqueId();
		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		String backupSessionId = backup == null ? null : backup.sessionId();

		if (backup != null && (hasMatchingTaggedPlantArmor(player, backup.sessionId()) || sessions.contains(playerId))) {
			restoreFromBackupOnEntityThread(player, backup, RestoreReason.DEATH, false);
		} else if (sessions.contains(playerId)) {
			restoreOnEntityThread(player, RestoreReason.DEATH, getSessionId(playerId));
		}

		stripPlantArmorFromDrops(event, backupSessionId);
	}

	public void handleRespawn(Player player) {
		UUID playerId = player.getUniqueId();
		sessions.remove(playerId);

		if (!backupStore.hasBackup(playerId)) {
			return;
		}

		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			return;
		}

		if (currentArmorMatchesBackup(player, backup)) {
			cleanupStaleBackupIfAlreadyRestored(player);
			return;
		}

		SchedulerUtil.runForPlayerDelayed(plugin, player, () -> {
			if (!backupStore.hasBackup(playerId)) {
				return;
			}
			PlantArmorBackup currentBackup = backupStore.loadBackup(playerId);
			if (currentBackup == null) {
				return;
			}
			if (currentArmorMatchesBackup(player, currentBackup)) {
				cleanupStaleBackupIfAlreadyRestored(player);
			} else if (hasMatchingTaggedPlantArmor(player, currentBackup.sessionId())) {
				restoreFromBackup(player, currentBackup, RestoreReason.RESPAWN);
			}
		}, JOIN_RECOVERY_DELAY_TICKS);
	}

	public int shutdownRestoreOnlinePlayers() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			UUID playerId = player.getUniqueId();
			if (!backupStore.hasBackup(playerId) && !sessions.contains(playerId)) {
				continue;
			}
			try {
				player.getScheduler().execute(plugin, () -> bestEffortShutdownRestore(player), null, 0L);
			} catch (Exception e) {
				plugin.getLogger().log(Level.WARNING,
						"PlantArmor disable: could not schedule restore for " + playerId + "; backup retained", e);
			}
		}

		int remaining = backupStore.countBackups();
		if (remaining > 0) {
			plugin.getLogger().info("PlantArmor disable: " + remaining
					+ " unresolved backup file(s) remain for next-login recovery: " + backupStore.listBackupPlayerIds());
		} else {
			plugin.getLogger().info("PlantArmor disable: no unresolved backup files remain");
		}
		return remaining;
	}

	/**
	 * Called only when forming completes and temporary PlantArmor is about to be equipped.
	 * No backup is created for bound abilities or forming-only state.
	 */
	ActivationResult beginActivationOnEntityThread(Player player, ItemStack[] temporaryArmor) {
		UUID playerId = player.getUniqueId();

		if (sessions.contains(playerId)) {
			plugin.getLogger().warning("PlantArmor activation rejected: active session already exists for " + playerId);
			return ActivationResult.failed();
		}
		if (backupStore.hasBackup(playerId) && !tryResolveStaleBackupBeforeActivation(player)) {
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
		ItemStack[] equip = PlantArmorSession.cloneArmor(temporaryArmor);
		int expectedTaggedArmorCount = countNonNullItems(equip);
		PlantArmorSession session = new PlantArmorSession(playerId, sessionId, originalArmor, createdAt, expectedTaggedArmorCount);
		if (sessions.putIfAbsent(playerId, session) != null) {
			plugin.getLogger().warning("PlantArmor activation aborted: concurrent session registered for " + playerId);
			backupStore.deleteBackup(playerId);
			return ActivationResult.failed();
		}

		for (ItemStack stack : equip) {
			if (stack != null) {
				PlantArmorItems.tagPlantArmor(plugin, stack, sessionId);
			}
		}
		player.getInventory().setArmorContents(equip);
		return ActivationResult.ok(sessionId);
	}

	private boolean restoreOnEntityThread(Player player, RestoreReason reason, String expectedSessionId) {
		UUID playerId = player.getUniqueId();
		PlantArmorSession activeSession = sessions.get(playerId);
		String activeSessionId = activeSession == null ? null : activeSession.sessionId();

		plugin.getLogger().info("PlantArmor restore requested: player=" + playerId + " reason=" + reason
				+ " expectedSession=" + expectedSessionId + " activeSession=" + activeSessionId);

		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			if (activeSession != null) {
				sessions.remove(playerId);
				activeSession.markRestored();
			}
			releaseRestoreClaim(playerId);
			plugin.getLogger().info("PlantArmor restore skipped: no backup for " + playerId + " (" + reason + ")");
			return true;
		}

		plugin.getLogger().info("PlantArmor restore started: player=" + playerId + " backupSession=" + backup.sessionId()
				+ " activeSession=" + activeSessionId);

		if (expectedSessionId != null && !expectedSessionId.equals(backup.sessionId())) {
			plugin.getLogger().warning("PlantArmor restore blocked: expected session " + expectedSessionId
					+ " does not match backup session " + backup.sessionId() + " for " + playerId);
			return false;
		}

		if (activeSession != null && !activeSession.sessionId().equals(backup.sessionId())) {
			logAmbiguous(playerId, backup.sessionId(), reason, "active session id does not match backup");
			return false;
		}

		if (activeSession != null) {
			sessions.remove(playerId);
			if (!activeSession.markRestored()) {
				plugin.getLogger().fine("PlantArmor restore ignored (" + reason + "): session already restored for " + playerId);
			}
		}

		return restoreFromBackupOnEntityThread(player, backup, reason, true);
	}

	private boolean restoreWithActiveSession(Player player, RestoreReason reason, String expectedSessionId) {
		return restoreOnEntityThread(player, reason, expectedSessionId);
	}

	private boolean restoreFromBackupOnEntityThread(Player player, PlantArmorBackup backup, RestoreReason reason,
			boolean sessionAlreadyRemoved) {
		UUID playerId = player.getUniqueId();
		if (!playerId.equals(backup.playerId())) {
			return false;
		}

		if (!sessionAlreadyRemoved) {
			PlantArmorSession session = sessions.remove(playerId);
			if (session != null && !session.markRestored()) {
				plugin.getLogger().fine("PlantArmor restore ignored (" + reason + "): session already restored for " + playerId);
				return false;
			}
			if (session != null && !session.sessionId().equals(backup.sessionId())) {
				logAmbiguous(playerId, backup.sessionId(), reason, "in-memory session id does not match backup");
				return false;
			}
		}

		if (!claimRestore(playerId)) {
			plugin.getLogger().info("PlantArmor restore blocked: restore already claimed for " + playerId + " (" + reason + ")");
			return false;
		}

		int stripped = stripSessionPlantArmorFromPlayer(player, backup.sessionId());
		plugin.getLogger().info("PlantArmor restore: stripped " + stripped + " matching temp piece(s) for " + playerId);

		if (!reconcileArmorSlots(player, backup.sessionId())) {
			releaseRestoreClaim(playerId);
			logAmbiguous(playerId, backup.sessionId(), reason, "could not reconcile armor slots");
			return false;
		}

		player.getInventory().setArmorContents(backup.originalArmorClone());
		plugin.getLogger().info("PlantArmor restore: setArmorContents from backup for " + playerId + " (" + reason + ")");

		if (countSessionPlantArmorItems(player, backup.sessionId()) > 0) {
			releaseRestoreClaim(playerId);
			logAmbiguous(playerId, backup.sessionId(), reason, "matching temp PlantArmor still present after restore");
			return false;
		}

		if (!backupStore.deleteBackup(playerId)) {
			plugin.getLogger().warning("PlantArmor restore completed but failed to delete backup for " + playerId);
		} else {
			plugin.getLogger().info("PlantArmor restore: backup deleted for " + playerId + " (" + reason + ")");
		}
		sessions.remove(playerId);
		releaseRestoreClaim(playerId);
		return true;
	}

	private void handleManualArmorRemovalOnEntityThread(Player player) {
		UUID playerId = player.getUniqueId();
		if (!hasActivePlantArmorState(playerId)) {
			return;
		}

		AtomicBoolean inProgress = manualRemovalInProgress.computeIfAbsent(playerId, id -> new AtomicBoolean(false));
		if (!inProgress.compareAndSet(false, true)) {
			return;
		}

		try {
			String sessionId = resolveSessionId(playerId);
			if (sessionId == null) {
				return;
			}

			PlantArmor ability = CoreAbility.getAbility(player, PlantArmor.class);
			if (ability != null) {
				ability.endFromManualArmorRemoval();
			} else {
				endPlantArmor(player, RestoreReason.MANUAL_ARMOR_REMOVAL, sessionId);
			}

			player.sendMessage(ChatColor.YELLOW + "PlantArmor ended because you removed the armor.");
		} finally {
			inProgress.set(false);
			manualRemovalInProgress.remove(playerId, inProgress);
		}
	}

	private boolean tryResolveStaleBackupBeforeActivation(Player player) {
		UUID playerId = player.getUniqueId();
		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			return !backupStore.hasBackup(playerId);
		}

		if (currentArmorMatchesBackup(player, backup)) {
			plugin.getLogger().info("PlantArmor activation recovery: armor already matches backup for " + playerId);
			return cleanupStaleBackupIfAlreadyRestored(player);
		}

		if (hasMatchingTaggedPlantArmor(player, backup.sessionId()) || sessions.contains(playerId)) {
			plugin.getLogger().info("PlantArmor activation recovery: restoring active/stale temp armor for " + playerId);
			return restoreOnEntityThread(player, RestoreReason.STALE_BACKUP_RECOVERY, backup.sessionId());
		}

		if (hasAnyPlantArmor(player)) {
			plugin.getLogger().warning("PlantArmor activation rejected: unresolved backup for " + playerId
					+ " with foreign PlantArmor items present");
			logAmbiguous(playerId, backup.sessionId(), RestoreReason.STALE_BACKUP_RECOVERY,
					"unresolved backup during activation with foreign PlantArmor items");
			return false;
		}

		plugin.getLogger().info("PlantArmor activation recovery: restoring from unresolved backup for " + playerId);
		return restoreOnEntityThread(player, RestoreReason.STALE_BACKUP_RECOVERY, backup.sessionId());
	}

	private int countSessionArmorPieces(Player player, String sessionId) {
		int count = 0;
		for (ItemStack stack : player.getInventory().getArmorContents()) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, stack, sessionId)) {
				count++;
			}
		}
		return count;
	}

	private static int countNonNullItems(ItemStack[] items) {
		int count = 0;
		for (ItemStack item : items) {
			if (item != null && !item.getType().isAir()) {
				count++;
			}
		}
		return count;
	}

	private int stripSessionPlantArmorFromPlayer(Player player, String sessionId) {
		int stripped = 0;
		ItemStack[] armor = player.getInventory().getArmorContents();
		boolean armorChanged = false;
		for (int i = 0; i < armor.length; i++) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, armor[i], sessionId)) {
				armor[i] = null;
				armorChanged = true;
				stripped++;
			}
		}
		if (armorChanged) {
			player.getInventory().setArmorContents(armor);
		}

		ItemStack[] contents = player.getInventory().getStorageContents();
		boolean contentsChanged = false;
		for (int i = 0; i < contents.length; i++) {
			if (PlantArmorItems.isPlantArmorForSession(plugin, contents[i], sessionId)) {
				contents[i] = null;
				contentsChanged = true;
				stripped++;
			}
		}
		if (contentsChanged) {
			player.getInventory().setStorageContents(contents);
		}

		if (PlantArmorItems.isPlantArmorForSession(plugin, player.getInventory().getItemInOffHand(), sessionId)) {
			player.getInventory().setItemInOffHand(null);
			stripped++;
		}

		if (player.getOpenInventory() != null) {
			ItemStack cursor = player.getOpenInventory().getCursor();
			if (PlantArmorItems.isPlantArmorForSession(plugin, cursor, sessionId)) {
				player.getOpenInventory().setCursor(null);
				stripped++;
			}
		}
		return stripped;
	}

	private void bestEffortShutdownRestore(Player player) {
		UUID playerId = player.getUniqueId();
		PlantArmorBackup backup = backupStore.loadBackup(playerId);
		if (backup == null) {
			sessions.remove(playerId);
			return;
		}

		if (hasMatchingTaggedPlantArmor(player, backup.sessionId()) || sessions.contains(playerId)) {
			restoreFromBackupOnEntityThread(player, backup, RestoreReason.PLUGIN_DISABLE, false);
		}
	}

	private boolean hasAnyPlantArmor(Player player) {
		for (ItemStack stack : player.getInventory().getArmorContents()) {
			if (stack != null && PlantArmorItems.isPlantArmorItem(plugin, stack)) {
				return true;
			}
		}
		for (ItemStack stack : player.getInventory().getStorageContents()) {
			if (stack != null && PlantArmorItems.isPlantArmorItem(plugin, stack)) {
				return true;
			}
		}
		if (PlantArmorItems.isPlantArmorItem(plugin, player.getInventory().getItemInOffHand())) {
			return true;
		}
		return false;
	}

	private void stripPlantArmorFromDrops(PlayerDeathEvent event, String sessionId) {
		event.getDrops().removeIf(item -> {
			if (!PlantArmorItems.isPlantArmorItem(plugin, item)) {
				return false;
			}
			if (sessionId == null) {
				return true;
			}
			return sessionId.equals(PlantArmorItems.getSessionId(plugin, item));
		});
	}

	private boolean claimRestore(UUID playerId) {
		return restoreClaims.computeIfAbsent(playerId, id -> new AtomicBoolean(false)).compareAndSet(false, true);
	}

	private void releaseRestoreClaim(UUID playerId) {
		AtomicBoolean claim = restoreClaims.get(playerId);
		if (claim != null) {
			claim.set(false);
		}
	}

	private void logAmbiguous(UUID playerId, String backupSessionId, RestoreReason reason, String detail) {
		plugin.getLogger().warning("PlantArmor ambiguous state (" + reason + ") for player " + playerId
				+ ", backup session " + backupSessionId + ": " + detail + "; backup left in place");
	}

	private static boolean stacksEqual(ItemStack a, ItemStack b) {
		if (a == null && b == null) {
			return true;
		}
		if (a == null || b == null) {
			return false;
		}
		return a.isSimilar(b) && a.getAmount() == b.getAmount();
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
				current[i] = null;
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
