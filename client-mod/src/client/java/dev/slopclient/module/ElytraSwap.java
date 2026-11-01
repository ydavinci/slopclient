package dev.slopclient.module;

import dev.slopclient.setting.BoolSetting;
import dev.slopclient.setting.ModeSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Swaps the elytra and the chestplate. Mode "Taste": the key swaps. Mode
 * "Automatisch": pressing jump in mid-air puts the elytra on and starts gliding
 * at once, like jumping with it already worn. Either way the chestplate can come
 * back by itself after landing.
 *
 * <p>The swap uses normal inventory clicks (the same as doing it by hand in the
 * inventory), so servers see nothing unusual; they can still switch the module off.
 */
public final class ElytraSwap extends Module {
	public static final String KEY = "Taste", AUTO = "Automatisch";

	public final ModeSetting mode = add(new ModeSetting("Modus", KEY, KEY, AUTO));
	public final BoolSetting backOnLanding = add(new BoolSetting("Nach Landung zurück", true));

	/** Menu slot of the chest armor in the player's inventory menu. */
	private static final int CHEST_MENU_SLOT = 6;
	/** Ticks between two swaps, so a held key or a lag spike can't flip it back and forth. */
	private static final int COOLDOWN = 5;
	/** Ticks on the ground before the chestplate goes back on (a short bounce keeps the elytra). */
	private static final int LANDED_TICKS = 3;

	private KeyMapping key;
	private boolean jumpWasDown;
	private int cooldown;
	private int landed;
	/** The elytra on the chest was put there by this module (so landing may take it off again). */
	private boolean ours;
	/** Glided since the elytra came on; only then does landing swap back (equipping on the ground stays). */
	private boolean glided;
	/** Inventory slot where the chestplate went when the elytra came on, or -1. */
	private int chestplateSlot = -1;

	public ElytraSwap() {
		super("Elytra-Swap", "Tauscht Elytra und Brustplatte per Taste oder automatisch beim Springen in der Luft, nach der Landung zurück", Category.COMFORT, true);
	}

	public void bind(KeyMapping key) {
		this.key = key;
	}

	@Override
	public String suffix() {
		return mode.is(AUTO) ? "Auto" : "";
	}

	@Override
	protected void onDisable() {
		ours = false;
		chestplateSlot = -1;
	}

	/** The key is polled every tick, so presses while the module is off don't pile up. */
	public void pollKey(Minecraft mc) {
		while (key != null && key.consumeClick()) {
			if (mc.player == null) continue;
			if (serverLocked()) {
				mc.player.sendOverlayMessage(Component.literal("Elytra-Swap ist auf diesem Server deaktiviert"));
			} else if (!enabled()) {
				mc.player.sendOverlayMessage(Component.literal("Elytra-Swap ist aus (ClickGUI → Komfort)"));
			} else if (canSwap(mc)) {
				if (wearingElytra(mc.player)) toChestplate(mc, true);
				else toElytra(mc, true);
			}
		}
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) return;
		if (cooldown > 0) cooldown--;
		// Elytra gone by other means (taken off by hand, broken): nothing to undo any more.
		if (!wearingElytra(player)) ours = false;

		boolean jumpDown = player.input.keyPresses.jump();
		boolean jumpPressed = jumpDown && !jumpWasDown;
		jumpWasDown = jumpDown;

		if (mode.is(AUTO) && jumpPressed && !wearingElytra(player) && mayGlideNow(player) && canSwap(mc) && toElytra(mc, false)) {
			// Vanilla already handled this jump press without an elytra; start gliding now.
			if (player.tryToStartFallFlying()) {
				player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			}
		}

		if (ours && player.isFallFlying()) glided = true;
		boolean down = player.onGround() || player.isInLiquid();
		landed = down && !player.isFallFlying() ? landed + 1 : 0;
		if (backOnLanding.on() && ours && glided && landed >= LANDED_TICKS && canSwap(mc)) toChestplate(mc, false);
	}

	/** In the air in a state where vanilla would let you glide. */
	private static boolean mayGlideNow(LocalPlayer player) {
		var abilities = player.getAbilities();
		// Creative flight uses the same double jump; leave it alone.
		return !player.onGround() && !player.isFallFlying() && !player.isInLiquid() && !player.onClimbable()
			&& !player.isPassenger() && !abilities.flying && !abilities.mayfly;
	}

	private boolean canSwap(Minecraft mc) {
		// Clicks go to the player's own inventory menu, which must be the open one.
		return cooldown == 0 && mc.gameMode != null && mc.gui.screen() == null && mc.player.containerMenu == mc.player.inventoryMenu
			&& mc.player.containerMenu.getCarried().isEmpty();
	}

	private static boolean wearingElytra(LocalPlayer player) {
		return player.getItemBySlot(EquipmentSlot.CHEST).has(DataComponents.GLIDER);
	}

	private static boolean isChestplate(ItemStack stack) {
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot() == EquipmentSlot.CHEST && !stack.has(DataComponents.GLIDER);
	}

	/** Puts the elytra with the most durability left on. */
	private boolean toElytra(Minecraft mc, boolean tell) {
		var inventory = mc.player.getInventory();
		int best = -1;
		int bestLeft = -1;
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			// canGlideUsing also rules out an elytra that is about to break.
			if (!LivingEntity.canGlideUsing(stack, EquipmentSlot.CHEST)) continue;
			int left = stack.getMaxDamage() - stack.getDamageValue();
			if (left > bestLeft) {
				best = slot;
				bestLeft = left;
			}
		}
		if (best < 0) {
			if (tell) mc.player.sendOverlayMessage(Component.literal("Keine nutzbare Elytra im Inventar"));
			return false;
		}
		boolean hadChestplate = isChestplate(mc.player.getItemBySlot(EquipmentSlot.CHEST));
		swapWithChest(mc, best);
		chestplateSlot = hadChestplate ? best : -1;
		ours = true;
		glided = false;
		if (tell) mc.player.sendOverlayMessage(Component.literal("Elytra angelegt"));
		return true;
	}

	/** Puts the chestplate back on: the one that came off, otherwise any from the inventory. */
	private void toChestplate(Minecraft mc, boolean tell) {
		var inventory = mc.player.getInventory();
		int slot = chestplateSlot >= 0 && isChestplate(inventory.getItem(chestplateSlot)) ? chestplateSlot : -1;
		for (int i = 0; i < 36 && slot < 0; i++) if (isChestplate(inventory.getItem(i))) slot = i;
		ours = false;
		chestplateSlot = -1;
		if (slot < 0) {
			// Only on request: without a chestplate the elytra is simply taken off.
			if (!tell) return;
			if (inventory.getFreeSlot() < 0) {
				mc.player.sendOverlayMessage(Component.literal("Kein Platz im Inventar für die Elytra"));
				return;
			}
			click(mc, CHEST_MENU_SLOT, 0, ContainerInput.QUICK_MOVE);
			mc.player.sendOverlayMessage(Component.literal("Elytra abgelegt (keine Brustplatte im Inventar)"));
			return;
		}
		swapWithChest(mc, slot);
		if (tell) mc.player.sendOverlayMessage(Component.literal("Brustplatte angelegt"));
	}

	/** Swaps inventory slot `slot` (0-8 hotbar, 9-35 main) with the chest armor slot. */
	private void swapWithChest(Minecraft mc, int slot) {
		if (slot < 9) {
			// Number-key swap: armor slot <-> hotbar slot, one click.
			click(mc, CHEST_MENU_SLOT, slot, ContainerInput.SWAP);
		} else {
			// Main inventory (same index in the menu): pick up, swap with the chest, put down.
			click(mc, slot, 0, ContainerInput.PICKUP);
			click(mc, CHEST_MENU_SLOT, 0, ContainerInput.PICKUP);
			click(mc, slot, 0, ContainerInput.PICKUP);
		}
		cooldown = COOLDOWN;
		landed = 0;
	}

	private static void click(Minecraft mc, int menuSlot, int button, ContainerInput input) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, button, input, mc.player);
	}
}
