package me.simplicitee.project.addons;

import com.projectkorra.projectkorra.GeneralMethods;
import me.simplicitee.project.addons.util.LightManager;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class Util {

	private Util() {}
	
	private static String[] lightning = {"e6efef", "03d2d2", "33e6ff", "03d2d2", "03d2d2", "33e6ff", "03d2d2", "33e6ff", "33e6ff"};
	
	public static final String LEAF_COLOR = "48B518";

	public static String leafParticleColor(Player player) {
		if (player == null) {
			return LEAF_COLOR;
		}
		return ProjectAddons.instance.getPlantArmorService()
				.getActivePlantArmorColor(player.getUniqueId())
				.map(Util::colorToHex)
				.orElse(LEAF_COLOR);
	}

	public static String colorToHex(Color color) {
		return String.format("%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
	}
	
	public static void playLightningParticles(Location loc, int amount, double xOff, double yOff, double zOff) {
		int i = (int) Math.round(Math.random() * (lightning.length - 1));
		GeneralMethods.displayColoredParticle(lightning[i], loc, amount, xOff, yOff, zOff);
	}

	public static void emitFireLight(Location loc) {
		boolean enabled = ProjectAddons.instance.getConfig().getBoolean("Properties.Fire.DynamicLight.Enabled");
		int brightness = ProjectAddons.instance.getConfig().getInt("Properties.Fire.DynamicLight.Brightness");
		long keepAlive = ProjectAddons.instance.getConfig().getLong("Properties.Fire.DynamicLight.KeepAlive");
		if (enabled) LightManager.createLight(loc).brightness(brightness).timeUntilFadeout(keepAlive).emit();
	}
	
	public static double clamp(double min, double max, double value) {
		if (value < min) {
			return min;
		} else return Math.min(value, max);
	}
}
