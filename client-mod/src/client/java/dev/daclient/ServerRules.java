package dev.daclient;

import dev.daclient.module.Module;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server opt-out (like Lunar's): a server sends `daclient:disable` with module ids
 * (for example ["freelook"]) and those modules stay off until the player leaves.
 */
public final class ServerRules {
	public record DisablePayload(List<String> modules) implements CustomPacketPayload {
		public static final Type<DisablePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(DaClient.ID, "disable"));
		public static final StreamCodec<ByteBuf, DisablePayload> CODEC =
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64)).map(DisablePayload::new, DisablePayload::modules);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private ServerRules() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(DisablePayload.TYPE, DisablePayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(DisablePayload.TYPE, (payload, context) -> {
			List<String> locked = new ArrayList<>();
			for (String id : payload.modules()) {
				Module m = DaClient.modules().byId(id);
				if (m != null) {
					m.setServerLocked(true);
					locked.add(m.name);
				}
			}
			if (!locked.isEmpty() && context.player() != null) {
				DaClient.LOG.info("Server disabled: {}", locked);
				context.player().sendSystemMessage(Component.literal("§dDaClient§7: Dieser Server deaktiviert " + String.join(", ", locked) + "."));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			for (Module m : DaClient.modules().all()) m.setServerLocked(false);
		});
	}
}
