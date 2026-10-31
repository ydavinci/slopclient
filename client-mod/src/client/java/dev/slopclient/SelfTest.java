package dev.slopclient;

import dev.slopclient.gui.ClickGuiScreen;
import dev.slopclient.gui.SlopTitleScreen;
import dev.slopclient.gui.HudEditorScreen;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Development aid, only active with `-Dslopclient.selftest=true`: walks through the
 * title screen, ClickGUI and HUD editor, then creates a flat test world and looks
 * at the player from behind (cape), saving a screenshot of each step to
 * `screenshots/` before quitting. Then it shows the modules, pets, cape and elytra.
 */
final class SelfTest {
	private enum Step { TITLE, CLICKGUI, HUD_EDITOR, WORLD_LOADING, CAPE, MODULES, MODULES_FRONT, INFO_HUD, PETS, CAPE_SIDE, ELYTRA, ELYTRA_SIDE, SKY, SKY_UP, TRAIL, TRAIL_GOLD, GLASS_FRONT, GLASS_BACK, ANVIL, SPIRAL, PETALS, ABGRUND, GLASS_ARM, ANVIL_OPEN, PINNED, TRADE, LECTERN, COLLECTION, DONE }

	private static Step step;
	private static int wait;
	private static SlopTitleScreen title;
	private static boolean infoShot;
	/** Pets photographed in the PETS step: id and camera (front or back). */
	private static final String[][] PET_SHOTS = {
		{"igel", "front"}, {"minime", "front"}, {"katze", "back"}, {"drache", "front"}, {"biene", "back"}, {"fuchs", "back"}, {"panda", "front"},
	};
	private static int petShot = -1;
	/** Collection 2, photographed with -Dslopclient.selftest.focus=kosmetik: property, id, camera. */
	private static final String[][] COLLECTION = {
		{"pet", "axolotl", "front"}, {"pet", "eule", "front"}, {"pet", "phoenix", "front"}, {"pet", "qualle", "front"},
		{"pet", "kitsune", "front"}, {"pet", "golem", "front"},
		{"hat", "zauberhut", "front"}, {"hat", "bluetenkranz", "front"}, {"hat", "kopfhoerer", "front"}, {"hat", "fliegenpilz", "front"},
		{"accessory", "engelsfluegel", "back"}, {"accessory", "drachenfluegel", "back"},
	};
	private static int collectionShot = -1;

	private SelfTest() {
	}

	static boolean enabled() {
		return Boolean.getBoolean("slopclient.selftest");
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "slopclient-selftest-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
			msg -> SlopClient.LOG.info("Self-test: {}", msg.getString()));
	}

	static void tick(Minecraft mc) {
		if (step == null) {
			if (!(mc.gui.screen() instanceof SlopTitleScreen screen)) return;
			title = screen;
			step = Step.TITLE;
			wait = 70; // let the intro animation finish
			return;
		}
		if (--wait > 0) return;
		switch (step) {
			case TITLE -> {
				shot(mc, "1-title");
				ClickGuiScreen gui = new ClickGuiScreen(title);
				mc.gui.setScreen(gui);
				gui.expand("Keystrokes");
				gui.expand("Capes");
				// Shows how a module switched off by a server looks.
				SlopClient.modules().freelook.setServerLocked(true);
				next(Step.CLICKGUI, 30);
			}
			case CLICKGUI -> {
				shot(mc, "2-clickgui");
				SlopClient.modules().freelook.setServerLocked(false);
				mc.gui.setScreen(new HudEditorScreen(title));
				next(Step.HUD_EDITOR, 30);
			}
			case HUD_EDITOR -> {
				shot(mc, "3-hud-editor");
				// A fresh flat creative world, so the player (and the cape) can be seen.
				var settings = new LevelSettings("SlopClient Selbsttest", GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
				mc.createWorldOpenFlows().createFreshLevel("slopclient-selftest-" + System.currentTimeMillis(), settings,
					WorldOptions.defaultWithRandomSeed(), WorldPresets::createTestWorldDimensions, title);
				next(Step.WORLD_LOADING, 20);
			}
			case WORLD_LOADING -> {
				if (mc.player == null || mc.level == null || mc.gui.screen() != null) {
					wait = 20;
					return;
				}
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(10);
				// -Dslopclient.selftest.focus=sky: only the skybox and trail shots.
				if ("sky".equals(System.getProperty("slopclient.selftest.focus"))) {
					SlopClient.modules().skybox.setEnabled(true);
					mc.options.setCameraType(CameraType.FIRST_PERSON);
					mc.player.setXRot(-8);
					next(Step.SKY, 100);
					return;
				}
				if ("kosmetik".equals(System.getProperty("slopclient.selftest.focus"))) {
					mc.player.connection.sendCommand("time set noon");
					// The own nametag would cover the hats.
					SlopClient.modules().nametags.setEnabled(false);
					next(Step.COLLECTION, 60);
					return;
				}
				// -Dslopclient.selftest.focus=neu: trail styles, glass variants, pinned plan, lectern.
				if ("neu".equals(System.getProperty("slopclient.selftest.focus"))) {
					var p = mc.player.blockPosition();
					var c = mc.player.connection;
					c.sendCommand("time set noon");
					c.sendCommand("setblock " + p.getX() + " " + p.getY() + " " + (p.getZ() + 3) + " minecraft:anvil");
					c.sendCommand("setblock " + (p.getX() + 3) + " " + p.getY() + " " + (p.getZ() + 3) + " minecraft:lectern");
					c.sendCommand("summon minecraft:villager " + (p.getX() + 4.5) + " " + p.getY() + " " + (p.getZ() + 4.5)
						+ " {NoAI:1b,VillagerData:{profession:\"minecraft:librarian\",level:2,type:\"minecraft:plains\"},Offers:{Recipes:["
						+ "{buy:{id:\"minecraft:emerald\",count:24},sell:{id:\"minecraft:enchanted_book\",count:1,components:{\"minecraft:stored_enchantments\":{\"minecraft:mending\":1}}},maxUses:12},"
						+ "{buy:{id:\"minecraft:emerald\",count:31},sell:{id:\"minecraft:enchanted_book\",count:1,components:{\"minecraft:stored_enchantments\":{\"minecraft:sharpness\":4}}},maxUses:12}]}}");
					var trail = SlopClient.modules().trail;
					trail.style.set("Spirale");
					trail.color.set("Chroma");
					trail.setEnabled(true);
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
					mc.player.setXRot(10);
					next(Step.SPIRAL, 100);
					return;
				}
				// -Dslopclient.selftest.focus=anvil: the anvil planner with a fully enchanted sword.
				if ("anvil".equals(System.getProperty("slopclient.selftest.focus"))) {
					var planner = new dev.slopclient.gui.AnvilPlannerScreen(null);
					mc.gui.setScreen(planner);
					planner.preset(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD),
						"sharpness", "unbreaking", "mending", "looting", "fire_aspect", "knockback", "sweeping_edge", "smite");
					next(Step.ANVIL, 40);
					return;
				}
				// -Dslopclient.selftest.focus=glass: only the glass figure.
				if ("glass".equals(System.getProperty("slopclient.selftest.focus"))) {
					SlopClient.modules().glass.setEnabled(true);
					mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
					mc.player.setXRot(5);
					next(Step.GLASS_FRONT, 100);
					return;
				}
				next(Step.CAPE, 100); // chunks and the cape texture settle
			}
			case CAPE -> {
				shot(mc, "4-cape");
				// M12 modules: outline, own nametag, chat stacking, scoreboard.
				var m = SlopClient.modules();
				for (var module : java.util.List.of(m.blockOutline, m.hitColor, m.lowFire, m.nametags, m.chat, m.scoreboard)) module.setEnabled(true);
				m.blockOutline.color.set("Chroma");
				var c = mc.player.connection;
				c.sendCommand("scoreboard objectives add slopclient dummy \"SlopClient\"");
				c.sendCommand("scoreboard objectives setdisplay sidebar slopclient");
				c.sendCommand("scoreboard players set DevPlayer slopclient 42");
				for (int i = 0; i < 3; i++) c.sendCommand("say Hallo aus dem Selbsttest");
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(70); // look at the ground: block outline
				next(Step.MODULES, 40);
			}
			case MODULES -> {
				shot(mc, "9-modules");
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				mc.player.setXRot(10);
				next(Step.MODULES_FRONT, 30);
			}
			case MODULES_FRONT -> {
				shot(mc, "10-nametag");
				// M13 HUD: every new element on, with something to count and a boss bar.
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(0);
				for (var hud : SlopClient.modules().hud()) {
					if (hud.getClass().getEnclosingClass() == dev.slopclient.hud.InfoHud.class) hud.setEnabled(true);
				}
				SlopClient.modules().bossBar.fy = 0.12;
				var c = mc.player.connection;
				c.sendCommand("give @s arrow 37");
				c.sendCommand("give @s totem_of_undying 2");
				c.sendCommand("give @s ender_pearl 16");
				c.sendCommand("give @s golden_apple 5");
				c.sendCommand("bossbar add slopclient:test \"Selbsttest-Boss\"");
				c.sendCommand("bossbar set slopclient:test players @a");
				c.sendCommand("bossbar set slopclient:test value 60");
				next(Step.INFO_HUD, 40);
			}
			case INFO_HUD -> {
				// The pearl is in the inventory now: give it a cooldown to show.
				if (!infoShot) for (int i = 0; i < 9; i++) {
					var stack = mc.player.getInventory().getItem(i);
					if (stack.is(net.minecraft.world.item.Items.ENDER_PEARL)) mc.player.getCooldowns().addCooldown(stack, 200);
				}
				if (wait == 0 && !infoShot) {
					infoShot = true;
					wait = 25;
					return;
				}
				shot(mc, "11-info-hud");
				next(Step.PETS, 5);
			}
			case PETS -> {
				// One shot per pet after it had time to settle (walking pets follow first).
				if (petShot >= 0) shot(mc, "12-pet-" + PET_SHOTS[petShot][0]);
				petShot++;
				if (petShot >= PET_SHOTS.length) {
					// The cape at an angle: its sides carry on the image.
					System.setProperty("slopclient.pet", "");
					mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
					mc.player.setXRot(10);
					mc.player.setYBodyRot(mc.player.getYRot() + 50);
					next(Step.CAPE_SIDE, 15);
					return;
				}
				System.setProperty("slopclient.pet", PET_SHOTS[petShot][0]);
				System.setProperty("slopclient.hat", "");
				mc.options.setCameraType(PET_SHOTS[petShot][1].equals("front") ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(15);
				next(Step.PETS, 40);
			}
			case CAPE_SIDE -> {
				shot(mc, "13-cape-side");
				// The cape on elytra: wings, not two slabs.
				mc.player.connection.sendCommand("item replace entity @s armor.chest with elytra");
				mc.player.setYBodyRot(mc.player.getYRot());
				next(Step.ELYTRA, 30);
			}
			case ELYTRA -> {
				shot(mc, "14-elytra-back");
				mc.player.setYBodyRot(mc.player.getYRot() + 50);
				next(Step.ELYTRA_SIDE, 15);
			}
			case ELYTRA_SIDE -> {
				shot(mc, "15-elytra-side");
				next(Step.DONE, 10);
			}
			case SKY -> {
				shot(mc, "16-skybox");
				mc.player.setXRot(-55);
				next(Step.SKY_UP, 10);
			}
			case SKY_UP -> {
				shot(mc, "17-skybox-up");
				// Strafe past the camera so the trail streams sideways through the picture.
				var trail = SlopClient.modules().trail;
				trail.color.set("Chroma");
				trail.setEnabled(true);
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(12);
				mc.options.keyLeft.setDown(true);
				next(Step.TRAIL, 30);
			}
			case TRAIL -> {
				shot(mc, "18-trail-chroma");
				SlopClient.modules().trail.color.set("Gold");
				mc.options.keyLeft.setDown(false);
				mc.options.keyRight.setDown(true);
				next(Step.TRAIL_GOLD, 30);
			}
			case GLASS_FRONT -> {
				shot(mc, "20-glass-front");
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				next(Step.GLASS_BACK, 15);
			}
			case ANVIL -> {
				shot(mc, "22-anvil-planner");
				next(Step.DONE, 10);
			}
			case SPIRAL -> {
				shot(mc, "23-trail-spiral");
				SlopClient.modules().trail.style.set("Blüten");
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				mc.options.keyLeft.setDown(true);
				next(Step.PETALS, 30);
			}
			case PETALS -> {
				shot(mc, "24-trail-petals");
				mc.options.keyLeft.setDown(false);
				SlopClient.modules().trail.setEnabled(false);
				var glass = SlopClient.modules().glass;
				glass.variant.set("Abgrund");
				glass.setEnabled(true);
				mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
				next(Step.ABGRUND, 30);
			}
			case ABGRUND -> {
				shot(mc, "25-glass-abgrund");
				SlopClient.modules().glass.variant.set("Prisma");
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.getInventory().setSelectedSlot(8);
				mc.player.setXRot(20);
				next(Step.GLASS_ARM, 20);
			}
			case GLASS_ARM -> {
				shot(mc, "26-glass-arm");
				SlopClient.modules().glass.setEnabled(false);
				// Pin a plan, then open the anvil placed three blocks ahead.
				var planner = new dev.slopclient.gui.AnvilPlannerScreen(null);
				mc.gui.setScreen(planner);
				planner.preset(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE),
					"efficiency", "unbreaking", "mending", "fortune");
				planner.pinForTest();
				mc.gui.setScreen(null);
				// Strafing moved the player away: stand two blocks in front of the anvil.
				var anvil = findBlock(mc, net.minecraft.world.level.block.Blocks.ANVIL, 12);
				if (anvil != null) mc.player.connection.sendCommand("tp @s " + (anvil.getX() + 0.5) + " " + anvil.getY() + " " + (anvil.getZ() - 1.5));
				next(Step.ANVIL_OPEN, 15);
			}
			case ANVIL_OPEN -> {
				var anvil = findBlock(mc, net.minecraft.world.level.block.Blocks.ANVIL, 6);
				if (anvil != null) {
					lookAt(mc, net.minecraft.world.phys.Vec3.atCenterOf(anvil));
					mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
						new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(anvil), net.minecraft.core.Direction.UP, anvil, false));
				}
				next(Step.PINNED, 20);
			}
			case PINNED -> {
				shot(mc, "27-anvil-pinned");
				mc.player.closeContainer();
				var villager = mc.level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, mc.player.getBoundingBox().inflate(12)).stream().findFirst().orElse(null);
				if (villager != null) {
					SlopClient.modules().librarian.interacted(villager);
					mc.gameMode.interact(mc.player, villager, new net.minecraft.world.phys.EntityHitResult(villager), net.minecraft.world.InteractionHand.MAIN_HAND);
				}
				next(Step.TRADE, 20);
			}
			case TRADE -> {
				mc.player.closeContainer();
				var lectern = findBlock(mc, net.minecraft.world.level.block.Blocks.LECTERN, 8);
				if (lectern != null) lookAt(mc, net.minecraft.world.phys.Vec3.atCenterOf(lectern));
				next(Step.LECTERN, 10);
			}
			case COLLECTION -> {
				if (collectionShot >= 0) shot(mc, "30-" + COLLECTION[collectionShot][1]);
				collectionShot++;
				for (String kind : new String[] {"pet", "hat", "accessory"}) System.setProperty("slopclient." + kind, "");
				if (collectionShot >= COLLECTION.length) {
					next(Step.DONE, 10);
					return;
				}
				String[] item = COLLECTION[collectionShot];
				System.setProperty("slopclient." + item[0], item[1]);
				mc.options.setCameraType(item[2].equals("front") ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
				mc.player.setXRot(item[0].equals("hat") ? 8 : 12);
				// Walking pets need a moment to catch up and settle beside the player.
				next(Step.COLLECTION, item[0].equals("pet") ? 50 : 25);
			}
			case LECTERN -> {
				shot(mc, "28-lectern");
				next(Step.DONE, 10);
			}
			case GLASS_BACK -> {
				shot(mc, "21-glass-back");
				next(Step.DONE, 10);
			}
			case TRAIL_GOLD -> {
				shot(mc, "19-trail-gold");
				mc.options.keyRight.setDown(false);
				next(Step.DONE, 10);
			}
			case DONE -> {
				SlopClient.LOG.info("Self-test finished");
				mc.stop();
			}
		}
	}

	/** The nearest block of `block` within `range` blocks of the player, or null. */
	private static net.minecraft.core.BlockPos findBlock(Minecraft mc, net.minecraft.world.level.block.Block block, int range) {
		var at = mc.player.blockPosition();
		for (var pos : net.minecraft.core.BlockPos.withinManhattan(at, range)) {
			if (mc.level.getBlockState(pos).is(block)) return pos.immutable();
		}
		return null;
	}

	private static void lookAt(Minecraft mc, net.minecraft.world.phys.Vec3 target) {
		var eye = mc.player.getEyePosition();
		double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
		mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
		mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
	}

	private static void next(Step s, int ticks) {
		step = s;
		wait = ticks;
	}
}
