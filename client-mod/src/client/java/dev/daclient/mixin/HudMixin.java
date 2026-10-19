package dev.daclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.daclient.DaClient;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Scoreboard module: drops the red score numbers of the sidebar. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@ModifyExpressionValue(
		method = "displayScoreboardSidebar",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;numberFormatOrDefault(Lnet/minecraft/network/chat/numbers/NumberFormat;)Lnet/minecraft/network/chat/numbers/NumberFormat;"))
	private NumberFormat daclient$hideNumbers(NumberFormat format) {
		var board = DaClient.ready() ? DaClient.modules().scoreboard : null;
		return board != null && board.active() && board.hideNumbers.on() ? BlankFormat.INSTANCE : format;
	}
}
