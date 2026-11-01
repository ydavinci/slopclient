package dev.slopclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.slopclient.hud.HudModule;
import dev.slopclient.module.Module;
import dev.slopclient.module.ModuleManager;
import dev.slopclient.setting.Setting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Config in `config/slopclient/` (where the launcher can find it too):
 * `client.json` holds the active profile and ClickGUI panel positions,
 * `profiles/<name>.json` the modules, settings and HUD positions of each profile.
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String DEFAULT_PROFILE = "Standard";

	private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("slopclient");
	private final Path profiles = dir.resolve("profiles");
	private final Path clientFile = dir.resolve("client.json");
	/** ClickGUI panel positions by category name. */
	public final Map<String, int[]> panels = new HashMap<>();
	/** Size of the ClickGUI relative to Minecraft's GUI scale (0.6 to 1.5). */
	public float guiScale = 1f;
	/** What was played last, for the title screen's "Spielen": null or {"world"|"server", id or address, name, epoch ms}. */
	public String[] lastPlayed;
	private String profile = DEFAULT_PROFILE;

	public String profile() {
		return profile;
	}

	/** Profile names, sorted, always containing the active one. */
	public List<String> profiles() {
		List<String> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(profiles)) {
			files.map(p -> p.getFileName().toString())
				.filter(n -> n.endsWith(".json"))
				.map(n -> n.substring(0, n.length() - 5))
				.forEach(names::add);
		} catch (IOException ignored) {
			// No profiles yet.
		}
		if (!names.contains(profile)) names.add(profile);
		names.sort(String.CASE_INSENSITIVE_ORDER);
		return names;
	}

	private Path file(String name) {
		return profiles.resolve(name + ".json");
	}

	public void load(ModuleManager modules) {
		migrate();
		try {
			if (Files.isRegularFile(clientFile)) {
				JsonObject root = JsonParser.parseString(Files.readString(clientFile)).getAsJsonObject();
				if (root.has("profile")) profile = sanitize(root.get("profile").getAsString());
				if (root.has("guiScale")) guiScale = clampGuiScale(root.get("guiScale").getAsFloat());
				if (root.has("lastPlayed")) {
					JsonArray last = root.getAsJsonArray("lastPlayed");
					if (last.size() == 4) {
						lastPlayed = new String[4];
						for (int i = 0; i < 4; i++) lastPlayed[i] = last.get(i).getAsString();
					}
				}
				if (root.has("panels")) {
					for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("panels").entrySet()) {
						JsonArray arr = e.getValue().getAsJsonArray();
						panels.put(e.getKey(), new int[] {arr.get(0).getAsInt(), arr.get(1).getAsInt()});
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			SlopClient.LOG.warn("Could not read {}", clientFile, e);
		}
		loadProfile(modules, profile, true);
	}

	public static float clampGuiScale(float scale) {
		if (!Float.isFinite(scale)) return 1f;
		return Math.round(Math.max(0.6f, Math.min(1.5f, scale)) * 10) / 10f;
	}

	/** Moves the single-file config of SlopClient 0.1.0 into the default profile. */
	private void migrate() {
		Path old = dir.resolve("config.json");
		if (!Files.isRegularFile(old) || Files.exists(file(DEFAULT_PROFILE))) return;
		try {
			Files.createDirectories(profiles);
			JsonObject root = JsonParser.parseString(Files.readString(old)).getAsJsonObject();
			JsonObject profileJson = new JsonObject();
			if (root.has("modules")) profileJson.add("modules", root.get("modules"));
			Files.writeString(file(DEFAULT_PROFILE), GSON.toJson(profileJson));
			JsonObject client = new JsonObject();
			client.addProperty("profile", DEFAULT_PROFILE);
			if (root.has("panels")) client.add("panels", root.get("panels"));
			Files.writeString(clientFile, GSON.toJson(client));
			Files.delete(old);
		} catch (IOException | RuntimeException e) {
			SlopClient.LOG.warn("Could not migrate {}", old, e);
		}
	}

	/**
	 * Applies a profile. At startup (`quiet`) modules only take their saved state;
	 * when switching profiles in game their enable/disable callbacks run as usual.
	 * Each module is read on its own, so one bad entry never costs the others.
	 */
	private void loadProfile(ModuleManager modules, String name, boolean quiet) {
		for (Module m : modules.all()) {
			if (quiet) m.resetQuietly();
			else m.reset();
		}
		Path file = file(name);
		if (!Files.isRegularFile(file)) return;
		JsonObject mods;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			mods = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
		} catch (IOException | RuntimeException e) {
			SlopClient.LOG.warn("Could not read profile {}, using defaults", file, e);
			return;
		}
		for (Module m : modules.all()) {
			if (!mods.has(m.name)) continue;
			try {
				JsonObject o = mods.getAsJsonObject(m.name);
				if (o.has("settings")) {
					JsonObject s = o.getAsJsonObject("settings");
					for (Setting<?> setting : m.settings()) {
						if (s.has(setting.name())) setting.load(s.get(setting.name()));
					}
				}
				if (m instanceof HudModule hud && o.has("x") && o.has("y")) {
					hud.fx = o.get("x").getAsDouble();
					hud.fy = o.get("y").getAsDouble();
				}
				if (o.has("enabled")) {
					if (quiet) m.restoreEnabled(o.get("enabled").getAsBoolean());
					else m.setEnabled(o.get("enabled").getAsBoolean());
				}
			} catch (RuntimeException e) {
				SlopClient.LOG.warn("Could not apply saved settings of {}", m.name, e);
			}
		}
	}

	public void save(ModuleManager modules) {
		JsonObject mods = new JsonObject();
		for (Module m : modules.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.enabled());
			JsonObject s = new JsonObject();
			for (Setting<?> setting : m.settings()) s.add(setting.name(), setting.save());
			o.add("settings", s);
			if (m instanceof HudModule hud) {
				o.addProperty("x", hud.fx);
				o.addProperty("y", hud.fy);
			}
			mods.add(m.name, o);
		}
		JsonObject profileJson = new JsonObject();
		profileJson.add("modules", mods);

		JsonObject client = new JsonObject();
		client.addProperty("profile", profile);
		client.addProperty("guiScale", guiScale);
		if (lastPlayed != null) {
			JsonArray last = new JsonArray();
			for (String s : lastPlayed) last.add(s);
			client.add("lastPlayed", last);
		}
		JsonObject panelJson = new JsonObject();
		panels.forEach((name, pos) -> {
			JsonArray arr = new JsonArray();
			arr.add(pos[0]);
			arr.add(pos[1]);
			panelJson.add(name, arr);
		});
		client.add("panels", panelJson);
		try {
			Files.createDirectories(profiles);
			Files.writeString(file(profile), GSON.toJson(profileJson));
			Files.writeString(clientFile, GSON.toJson(client));
		} catch (IOException e) {
			SlopClient.LOG.warn("Could not save the SlopClient config", e);
		}
	}

	/** Saves the current profile, then loads `name`. */
	public void switchTo(ModuleManager modules, String name) {
		save(modules);
		profile = sanitize(name);
		loadProfile(modules, profile, false);
		save(modules);
	}

	/** New profile ("Profil 2", …) starting from the current settings. */
	public String create(ModuleManager modules) {
		save(modules);
		List<String> existing = profiles();
		int n = 2;
		while (existing.contains("Profil " + n)) n++;
		profile = "Profil " + n;
		save(modules);
		return profile;
	}

	/** Deletes the active profile and switches to the first remaining one. */
	public void deleteCurrent(ModuleManager modules) {
		List<String> names = profiles();
		if (names.size() <= 1) return;
		try {
			Files.deleteIfExists(file(profile));
		} catch (IOException e) {
			SlopClient.LOG.warn("Could not delete profile {}", profile, e);
			return;
		}
		names.remove(profile);
		profile = names.getFirst();
		loadProfile(modules, profile, false);
		save(modules);
	}

	/** Profile names become file names, so keep them simple. */
	private static String sanitize(String name) {
		String clean = name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
		return clean.isEmpty() ? DEFAULT_PROFILE : clean;
	}
}
