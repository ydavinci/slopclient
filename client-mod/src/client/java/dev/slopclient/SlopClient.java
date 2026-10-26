package dev.slopclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.slopclient.cosmetics.Cosmetics;
import dev.slopclient.cosmetics.karambit.KarambitModels;
import dev.slopclient.gui.ClickGuiScreen;
import dev.slopclient.hud.HudModule;
import dev.slopclient.module.ModuleManager;
import dev.slopclient.render.MenuShader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SlopClient implements ClientModInitializer {
	public static final String ID = "slopclient";
	public static final Logger LOG = LoggerFactory.getLogger("SlopClient");
	public static final String VERSION = FabricLoader.getInstance()
		.getModContainer(ID)
		.map(c -> c.getMetadata().getVersion().getFriendlyString())
		.orElse("dev");

	private static ModuleManager modules;
	private static Config config;
	private static KeyMapping clickGuiKey;

	public static ModuleManager modules() {
		return modules;
	}

	public static Config config() {
		return config;
	}

	public static KeyMapping clickGuiKey() {
		return clickGuiKey;
	}

	/** False until the client entrypoint ran (mixins can fire earlier). */
	public static boolean ready() {
		return modules != null;
	}

	/** Height of vanilla's sidebar in GUI pixels: title plus up to 15 lines of 9 px. */
	private static int sidebarHeight(Minecraft mc) {
		if (mc.level == null) return 0;
		var scoreboard = mc.level.getScoreboard();
		var objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
		if (objective == null) return 0;
		int lines = (int) Math.min(15, scoreboard.listPlayerScores(objective).stream().filter(e -> !e.isHidden()).count());
		return (lines + 1) * 9;
	}

	public static void save() {
		if (ready()) config.save(modules);
	}

	@Override
	public void onInitializeClient() {
		MenuShader.register();
		dev.slopclient.render.SlopSky.register();
		modules = new ModuleManager();
		config = new Config();
		config.load(modules);
		// The bundled cosmetics mod renders and ticks itself; it only needs our settings.
		Cosmetics.use(new ClientCosmetics());

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
		clickGuiKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.clickgui", InputConstants.KEY_RSHIFT, category));
		KeyMapping zoomKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.zoom", InputConstants.KEY_C, category));
		modules.zoom.bind(zoomKey);
		modules.freelook.bind(KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.freelook", InputConstants.KEY_LALT, category)));
		modules.karambit.bind(KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.inspect", InputConstants.KEY_V, category)));
		KarambitModels.register();
		KeyMapping screenshotKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.screenshot", InputConstants.UNKNOWN.getValue(), category));
		modules.stopwatch.bind(KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.slopclient.stopwatch", InputConstants.UNKNOWN.getValue(), category)));
		// Combo and reach watch the player's own hits.
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			level.isClientSide() ? dev.slopclient.hud.InfoHud.Hits.attacked(entity) : net.minecraft.world.InteractionResult.PASS);
		ServerRules.register();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (screenshotKey.consumeClick()) modules.screenshots.openLatest();
			while (clickGuiKey.consumeClick()) {
				if (mc.gui.screen() == null) mc.gui.setScreen(new ClickGuiScreen(null));
			}
			modules.tick(mc);
			Presence.tick(mc);
			dev.slopclient.hud.InfoHud.Hits.tick(mc);
			if (SelfTest.enabled()) SelfTest.tick(mc);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			modules.brightness.restore();
			save();
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(ID, "hud"), (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			for (HudModule hud : modules.hud()) {
				if (hud.active()) hud.render(g, mc, false);
			}
			modules.screenshots.render(g);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, vanilla -> (g, delta) -> {
			var board = modules.scoreboard;
			if (board.active() && board.hide.on()) return;
			float s = board.active() ? board.size.get().floatValue() : 1;
			// The sidebar sits centred on the right edge, where the ArrayList also lives:
			// move it below the list when they would overlap, and scale around its centre.
			float centre = g.guiHeight() / 2f;
			float half = sidebarHeight(Minecraft.getInstance()) * s / 2f;
			HudModule list = modules.hud().stream().filter(h -> h instanceof dev.slopclient.hud.HudModules.ArrayListHud).findFirst().orElse(null);
			if (list != null && list.active() && list.fx > 0.5 && list.y + list.h + 4 > centre - half) {
				centre = Math.min(g.guiHeight() - half - 2, list.y + list.h + 4 + half);
			}
			g.pose().pushMatrix();
			g.pose().translate(g.guiWidth(), centre);
			g.pose().scale(s, s);
			g.pose().translate(-g.guiWidth(), -g.guiHeight() / 2f);
			vanilla.extractRenderState(g, delta);
			g.pose().popMatrix();
		});
		// The Bossleiste module draws the bars itself, at its own place.
		HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, vanilla -> (g, delta) -> {
			if (!modules.bossBar.active()) vanilla.extractRenderState(g, delta);
		});
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (modules.crosshair.active() && mc.options.getCameraType().isFirstPerson()) modules.crosshair.draw(g);
			else vanilla.extractRenderState(g, delta);
		});
		LOG.info("SlopClient {} ready ({} modules)", VERSION, modules.all().size());
	}
}
