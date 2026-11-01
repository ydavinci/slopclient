package dev.slopclient.mixin.restyle;

import dev.slopclient.ui.Restyle;
import java.util.Map;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advancements window in the launcher look: a glass panel instead of the stone frame,
 * title in the menu font, tabs as glass (see AdvancementTabTypeRestyleMixin). The tree
 * inside keeps its own background, it belongs to the tab (stone, nether, end ...).
 */
@Mixin(AdvancementsScreen.class)
public abstract class AdvancementsScreenRestyleMixin {
	@Shadow
	private int leftPos;
	@Shadow
	private int topPos;
	@Shadow
	@Final
	private Map<AdvancementHolder, AdvancementTab> tabs;
	@Shadow
	private AdvancementTab selectedTab;
	@Shadow
	@Final
	private static Component TITLE;

	/** The panel goes under the tree, so it is drawn right before the inside, not with the frame. */
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/advancements/AdvancementsScreen;extractInside(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"), require = 0)
	private void slopclient$panel(GuiGraphicsExtractor g, int mouseX, int mouseY, float a, CallbackInfo ci) {
		if (Restyle.active()) Restyle.advancementPanel(g, leftPos, topPos);
	}

	@Inject(method = "extractWindow", at = @At("HEAD"), cancellable = true, require = 0)
	private void slopclient$window(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
		if (!Restyle.active()) return;
		if (tabs.size() > 1) {
			for (AdvancementTab tab : tabs.values()) tab.extractTab(g, leftPos, topPos, mouseX, mouseY, tab == selectedTab);
			for (AdvancementTab tab : tabs.values()) tab.extractIcon(g, leftPos, topPos);
		}
		Restyle.advancementFrame(g, leftPos, topPos, (selectedTab != null ? selectedTab.getTitle() : TITLE).getString());
		ci.cancel();
	}
}
