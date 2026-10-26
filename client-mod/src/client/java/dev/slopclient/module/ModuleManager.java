package dev.slopclient.module;

import dev.slopclient.hud.HudModule;
import dev.slopclient.hud.HudModules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ModuleManager {
	private final List<Module> modules = new ArrayList<>();

	public final Features.Zoom zoom = add(new Features.Zoom());
	public final Features.Brightness brightness = add(new Features.Brightness());
	public final Features.Crosshair crosshair = add(new Features.Crosshair());
	public final Features.ToggleSprint toggleSprint = add(new Features.ToggleSprint());
	public final Features.Freelook freelook = add(new Features.Freelook());
	public final Features.UiFont uiFont = add(new Features.UiFont());
	public final Features.Capes capes = add(new Features.Capes());
	public final Features.Extras extras = add(new Features.Extras());
	public final Features.Karambit karambit = add(new Features.Karambit());
	public final Visuals.BlockOutline blockOutline = add(new Visuals.BlockOutline());
	public final Visuals.HitColor hitColor = add(new Visuals.HitColor());
	public final Visuals.Glow glow = add(new Visuals.Glow());
	public final Visuals.Trail trail = add(new Visuals.Trail());
	public final Visuals.Skybox skybox = add(new Visuals.Skybox());
	public final Visuals.Glass glass = add(new Visuals.Glass());
	public final Visuals.LowFire lowFire = add(new Visuals.LowFire());
	public final Visuals.Nametags nametags = add(new Visuals.Nametags());
	public final Visuals.ChatPlus chat = add(new Visuals.ChatPlus());
	public final Visuals.Scoreboard scoreboard = add(new Visuals.Scoreboard());
	public final dev.slopclient.gui.ScreenshotPreview screenshots = add(new dev.slopclient.gui.ScreenshotPreview());
	public final Features.TitleScreenModule titleScreen = add(new Features.TitleScreenModule());
	public final dev.slopclient.hud.InfoHud.BossBar bossBar = new dev.slopclient.hud.InfoHud.BossBar();
	public final dev.slopclient.hud.InfoHud.Stopwatch stopwatch = new dev.slopclient.hud.InfoHud.Stopwatch();

	public ModuleManager() {
		add(new HudModules.Watermark());
		add(new HudModules.ArrayListHud());
		add(new HudModules.Fps());
		add(new HudModules.CpsHud());
		add(new HudModules.Ping());
		add(new HudModules.Clock());
		add(new HudModules.ServerIp());
		add(new HudModules.Speed());
		add(new HudModules.Coordinates());
		add(new HudModules.Keystrokes());
		add(new HudModules.Armor());
		add(new HudModules.Effects());
		add(new dev.slopclient.hud.InfoHud.Compass());
		add(new dev.slopclient.hud.InfoHud.ItemCounter());
		add(new dev.slopclient.hud.InfoHud.Combo());
		add(new dev.slopclient.hud.InfoHud.Reach());
		add(new dev.slopclient.hud.InfoHud.Cooldowns());
		add(bossBar);
		add(new dev.slopclient.hud.InfoHud.Memory());
		add(new dev.slopclient.hud.InfoHud.Biome());
		add(new dev.slopclient.hud.InfoHud.Day());
		add(stopwatch);
		add(new dev.slopclient.hud.InfoHud.Packs());
	}

	private <M extends Module> M add(M module) {
		modules.add(module);
		return module;
	}

	public List<Module> all() {
		return modules;
	}

	public List<Module> in(Category category) {
		return modules.stream().filter(m -> m.category == category).toList();
	}

	public List<HudModule> hud() {
		return modules.stream().filter(m -> m instanceof HudModule).map(m -> (HudModule) m).toList();
	}

	public Module byId(String id) {
		return modules.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
	}

	public Module byName(String name) {
		return modules.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
	}

	public void tick(Minecraft mc) {
		for (Module m : modules) {
			if (m.active()) m.tick(mc);
		}
	}
}
