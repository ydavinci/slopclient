package dev.slopclient.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings of the cosmetics mod on its own (without the SlopClient mod), from
 * {@code config/slopclient-cosmetics.json}. Missing values are written back with their defaults.
 */
final class StandaloneSettings implements CosmeticsSettings {
	/** SlopClient's own cosmetics server, the same default as the launcher (slopclient-core config.rs). */
	static final String DEFAULT_SERVER = "https://cosmetics.ydavinci.me";

	/** The JSON file; field names are the keys. */
	private static final class File {
		String server = DEFAULT_SERVER;
		boolean capes = true;
		boolean capesOfOthers = true;
		boolean capesOverMojang = true;
		boolean animateCapes = true;
		boolean capesAsElytra = true;
		boolean hatsAndPets = true;
		boolean hatsAndPetsOfOthers = true;
		boolean hideHatWithHelmet = false;
	}

	private final File file;
	private final String server;

	private StandaloneSettings(File file) {
		this.file = file;
		// -Dslopclient.cosmetics (what the launcher passes) wins over the file.
		String prop = System.getProperty("slopclient.cosmetics", "").trim();
		String s = prop.isEmpty() ? (file.server == null ? "" : file.server.trim()) : prop;
		this.server = s.replaceAll("/+$", "");
	}

	static StandaloneSettings load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("slopclient-cosmetics.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		File file = null;
		try {
			if (Files.isRegularFile(path)) file = gson.fromJson(Files.readString(path), File.class);
		} catch (Exception e) {
			Cosmetics.LOG.warn("Could not read {}, using defaults", path, e);
		}
		if (file == null) file = new File();
		try {
			Files.writeString(path, gson.toJson(file));
		} catch (Exception e) {
			Cosmetics.LOG.warn("Could not write {}", path, e);
		}
		return new StandaloneSettings(file);
	}

	public String server() {
		return server;
	}

	public boolean capes() {
		return file.capes;
	}

	public boolean capesOfOthers() {
		return file.capesOfOthers;
	}

	public boolean capesOverMojang() {
		return file.capesOverMojang;
	}

	public boolean animateCapes() {
		return file.animateCapes;
	}

	public boolean capesAsElytra() {
		return file.capesAsElytra;
	}

	public boolean extras() {
		return file.hatsAndPets;
	}

	public boolean extrasOfOthers() {
		return file.hatsAndPetsOfOthers;
	}

	public boolean hideHatWithHelmet() {
		return file.hideHatWithHelmet;
	}
}
