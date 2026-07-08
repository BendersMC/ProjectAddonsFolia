package me.simplicitee.project.addons.ability.water.plantarmor;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Durable backup store for PlantArmor original armor. Writes only; does not equip or restore armor.
 */
public final class PlantArmorBackupStore {

	private static final String BACKUP_DIR = "plantarmor-backups";
	private static final String SUFFIX = ".yml";
	private static final String TEMP_SUFFIX = ".yml.tmp";

	private final Plugin plugin;
	private final File backupDirectory;

	public PlantArmorBackupStore(Plugin plugin) {
		this.plugin = plugin;
		this.backupDirectory = new File(plugin.getDataFolder(), BACKUP_DIR);
	}

	public void initialize() {
		if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
			plugin.getLogger().warning("Failed to create PlantArmor backup directory: " + backupDirectory.getAbsolutePath());
		}
	}

	public File getBackupDirectory() {
		return backupDirectory;
	}

	public boolean saveBackup(UUID playerId, String sessionId, ItemStack[] originalArmor) throws IOException {
		long createdAtMillis = System.currentTimeMillis();
		ItemStack[] clonedArmor = PlantArmorSession.cloneArmor(originalArmor);

		YamlConfiguration config = new YamlConfiguration();
		config.set("player-id", playerId.toString());
		config.set("session-id", sessionId);
		config.set("created-at", createdAtMillis);
		for (int i = 0; i < clonedArmor.length; i++) {
			config.set("armor." + i, clonedArmor[i]);
		}

		File finalFile = backupFile(playerId);
		File tempFile = new File(backupDirectory, playerId + TEMP_SUFFIX);
		config.save(tempFile);
		atomicReplace(tempFile, finalFile);
		return true;
	}

	public PlantArmorBackup loadBackup(UUID playerId) {
		File file = backupFile(playerId);
		if (!file.isFile()) {
			return null;
		}

		YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
		String playerIdRaw = config.getString("player-id");
		String sessionId = config.getString("session-id");
		if (playerIdRaw == null || sessionId == null) {
			return null;
		}

		UUID storedPlayerId;
		try {
			storedPlayerId = UUID.fromString(playerIdRaw);
		} catch (IllegalArgumentException e) {
			plugin.getLogger().warning("Invalid player-id in PlantArmor backup for " + playerId + ": " + playerIdRaw);
			return null;
		}

		long createdAt = config.getLong("created-at", 0L);
		ItemStack[] armor = new ItemStack[4];
		for (int i = 0; i < armor.length; i++) {
			ItemStack stack = config.getItemStack("armor." + i);
			armor[i] = stack == null ? null : stack.clone();
		}

		return new PlantArmorBackup(storedPlayerId, sessionId, createdAt, armor);
	}

	public boolean hasBackup(UUID playerId) {
		return backupFile(playerId).isFile();
	}

	public boolean deleteBackup(UUID playerId) {
		File file = backupFile(playerId);
		if (!file.exists()) {
			return false;
		}
		if (!file.delete()) {
			plugin.getLogger().warning("Failed to delete PlantArmor backup: " + file.getAbsolutePath());
			return false;
		}
		return true;
	}

	private File backupFile(UUID playerId) {
		return new File(backupDirectory, playerId + SUFFIX);
	}

	private void atomicReplace(File tempFile, File finalFile) throws IOException {
		try {
			Files.move(tempFile.toPath(), finalFile.toPath(),
					StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			plugin.getLogger().log(Level.WARNING, "Failed to move PlantArmor backup into place: " + finalFile.getName(), e);
			throw e;
		}
	}
}
