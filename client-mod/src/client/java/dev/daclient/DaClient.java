package dev.daclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.daclient.gui.ClickGuiScreen;
import dev.daclient.hud.HudModule;
import dev.daclient.module.ModuleManager;
import dev.daclient.render.MenuShader;
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

public final class DaClient implements ClientModInitializer {
	public static final String ID = "daclient";
	public static final Logger LOG = LoggerFactory.getLogger("DaClient");
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

	public static void save() {
		if (ready()) config.save(modules);
	}

	@Override
	public void onInitializeClient() {
		MenuShader.register();
		modules = new ModuleManager();
		config = new Config();
		config.load(modules);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));
		clickGuiKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.daclient.clickgui", InputConstants.KEY_RSHIFT, category));
		KeyMapping zoomKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.daclient.zoom", InputConstants.KEY_C, category));
		modules.zoom.bind(zoomKey);
		modules.freelook.bind(KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.daclient.freelook", InputConstants.KEY_LALT, category)));
		ServerRules.register();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (clickGuiKey.consumeClick()) {
				if (mc.gui.screen() == null) mc.gui.setScreen(new ClickGuiScreen(null));
			}
			modules.tick(mc);
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
		});
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, delta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (modules.crosshair.active() && mc.options.getCameraType().isFirstPerson()) modules.crosshair.draw(g);
			else vanilla.extractRenderState(g, delta);
		});
		LOG.info("DaClient {} ready ({} modules)", VERSION, modules.all().size());
	}
}
