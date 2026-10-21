package dev.slopclient.mixin;

import dev.slopclient.render.Draw;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Font module, "Überall im Spiel": every text that uses Minecraft's default font
 * (chat, menus, tooltips, signs, nametags …) is drawn in the chosen font instead.
 * Characters the font lacks fall back to Minecraft's font through the font JSON.
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class FontProviderMixin {
	@ModifyVariable(method = "glyphs", at = @At("HEAD"), argsOnly = true)
	private FontDescription slopclient$everywhere(FontDescription description) {
		if (!FontDescription.DEFAULT.equals(description)) return description;
		FontDescription replacement = Draw.gameFont();
		return replacement == null ? description : replacement;
	}
}
