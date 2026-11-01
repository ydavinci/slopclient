package dev.slopclient.mixin;

import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.WidgetSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The sprite of an icon button, so the classic ESC menu can draw mods' icon buttons in its own frame. */
@Mixin(SpriteIconButton.class)
public interface SpriteIconButtonAccessor {
	@Accessor("sprite")
	WidgetSprites slopclient$sprite();

	@Accessor("spriteWidth")
	int slopclient$spriteWidth();

	@Accessor("spriteHeight")
	int slopclient$spriteHeight();
}
