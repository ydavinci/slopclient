package dev.daclient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.daclient.hud.HudModule;
import dev.daclient.module.Module;
import dev.daclient.module.ModuleManager;
import dev.daclient.setting.Setting;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Stores modules, settings, HUD positions and ClickGUI panel positions in
 * `config/daclient/config.json`, where the launcher can find it too.
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = FabricLoader.getInstance().getConfigDir().resolve("daclient").resolve("config.json");
	/** ClickGUI panel positions by category name. */
	public final Map<String, int[]> panels = new HashMap<>();

	public void load(ModuleManager modules) {
		if (!Files.isRegularFile(file)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			JsonObject mods = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
			for (Module m : modules.all()) {
				if (!mods.has(m.name)) continue;
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
				if (o.has("enabled")) m.setEnabled(o.get("enabled").getAsBoolean());
			}
			if (root.has("panels")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("panels").entrySet()) {
					var arr = e.getValue().getAsJsonArray();
					panels.put(e.getKey(), new int[] {arr.get(0).getAsInt(), arr.get(1).getAsInt()});
				}
			}
		} catch (IOException | RuntimeException e) {
			DaClient.LOG.warn("Could not read {}, using defaults", file, e);
		}
	}

	public void save(ModuleManager modules) {
		JsonObject root = new JsonObject();
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
		root.add("modules", mods);
		JsonObject panelJson = new JsonObject();
		panels.forEach((name, pos) -> {
			var arr = new com.google.gson.JsonArray();
			arr.add(pos[0]);
			arr.add(pos[1]);
			panelJson.add(name, arr);
		});
		root.add("panels", panelJson);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root));
		} catch (IOException e) {
			DaClient.LOG.warn("Could not save {}", file, e);
		}
	}
}
