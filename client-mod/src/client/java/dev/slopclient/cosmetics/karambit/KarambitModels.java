package dev.slopclient.cosmetics.karambit;

import dev.slopclient.SlopClient;
import dev.slopclient.mixin.ConditionalItemModelPropertiesAccessor;
import dev.slopclient.mixin.SpecialModelRenderersAccessor;
import java.util.Optional;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.renderer.item.ConditionalItemModel;
import net.minecraft.client.renderer.item.SpecialModelWrapper;
import net.minecraft.resources.Identifier;

/**
 * Puts the Karambit on the netherite sword in code rather than through an item
 * definition file: resource packs rank above mod assets, so a pack that ships its
 * own {@code items/netherite_sword.json} (Enchantment Outlines does) would hide it.
 * Whatever model the sword ends up with stays as the fallback when the module is off.
 */
public final class KarambitModels {
	private static final Identifier SWORD = Identifier.withDefaultNamespace("netherite_sword");
	/** Display transforms (hand, GUI, ground) and the particle texture. */
	private static final Identifier BASE = Identifier.fromNamespaceAndPath(SlopClient.ID, "item/karambit");
	private static final ExtraModelKey<Boolean> BASE_KEY = ExtraModelKey.create(() -> "slopclient:karambit_base");

	private KarambitModels() {
	}

	public static void register() {
		Identifier id = Identifier.fromNamespaceAndPath(SlopClient.ID, "karambit");
		SpecialModelRenderersAccessor.slopclient$ids().put(id, KarambitRenderer.Unbaked.MAP_CODEC);
		ConditionalItemModelPropertiesAccessor.slopclient$ids().put(id, KarambitEnabled.MAP_CODEC);

		ModelLoadingPlugin.register(context -> {
			// Makes sure the base model is loaded even though no item definition names it.
			context.addModel(BASE_KEY, new SimpleUnbakedExtraModel<>(BASE, (model, baker) -> true));
			context.modifyItemModelBeforeBake().register(ModelModifier.WRAP_LAST_PHASE, (model, ctx) -> {
				if (!SWORD.equals(ctx.itemId())) return model;
				var karambit = new SpecialModelWrapper.Unbaked(BASE, Optional.empty(), new KarambitRenderer.Unbaked());
				return new ConditionalItemModel.Unbaked(Optional.empty(), new KarambitEnabled(), karambit, model);
			});
		});
	}
}
