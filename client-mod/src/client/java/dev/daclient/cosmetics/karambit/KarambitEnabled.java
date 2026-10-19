package dev.daclient.cosmetics.karambit;

import com.mojang.serialization.MapCodec;
import dev.daclient.DaClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Item model condition {@code daclient:karambit}: true while the Karambit module is on,
 * so switching it off in the ClickGUI brings the vanilla sword back without a reload.
 */
public record KarambitEnabled() implements ConditionalItemModelProperty {
	public static final MapCodec<KarambitEnabled> MAP_CODEC = MapCodec.unit(new KarambitEnabled());

	@Override
	public boolean get(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext context) {
		return DaClient.ready() && DaClient.modules().karambit.active();
	}

	@Override
	public MapCodec<KarambitEnabled> type() {
		return MAP_CODEC;
	}
}
