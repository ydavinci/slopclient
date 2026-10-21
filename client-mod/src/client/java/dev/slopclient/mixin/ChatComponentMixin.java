package dev.slopclient.mixin;

import dev.slopclient.SlopClient;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Chat module: a timestamp in front of each message, repeated messages folded into
 * one line with a counter ("(x3)") and a longer history than vanilla's 100 lines.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Unique
	private static final DateTimeFormatter slopclient$TIME = DateTimeFormatter.ofPattern("HH:mm");

	@Shadow
	@Final
	private List<GuiMessage> allMessages;

	@Shadow
	private void refreshTrimmedMessages() {
	}

	@Unique
	private String slopclient$last;
	@Unique
	private int slopclient$repeats;

	@ModifyVariable(
		method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"), argsOnly = true)
	private Component slopclient$decorate(Component message) {
		if (!SlopClient.ready() || !SlopClient.modules().chat.active()) return message;
		var chat = SlopClient.modules().chat;
		Component out = message;
		String plain = message.getString();
		if (chat.stack.on() && plain.equals(slopclient$last) && !allMessages.isEmpty()) {
			// Replace the previous copy instead of adding a second line.
			allMessages.remove(0);
			refreshTrimmedMessages();
			slopclient$repeats++;
			out = message.copy().append(Component.literal(" (x" + slopclient$repeats + ")").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			slopclient$last = plain;
			slopclient$repeats = 1;
		}
		if (chat.timestamps.on()) {
			out = Component.literal("[" + LocalTime.now().format(slopclient$TIME) + "] ").withStyle(ChatFormatting.DARK_GRAY).append(out);
		}
		return out;
	}

	@ModifyConstant(method = {"addMessageToDisplayQueue", "addMessageToQueue"}, constant = @Constant(intValue = 100), require = 1)
	private int slopclient$history(int max) {
		return SlopClient.ready() && SlopClient.modules().chat.active() ? SlopClient.modules().chat.history.get().intValue() : max;
	}
}
