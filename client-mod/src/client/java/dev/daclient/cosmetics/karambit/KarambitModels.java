package dev.daclient.cosmetics.karambit;

import dev.daclient.DaClient;
import dev.daclient.mixin.ConditionalItemModelPropertiesAccessor;
import dev.daclient.mixin.SpecialModelRenderersAccessor;
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
	private static final Identifier BASE = Identifier.fromNamespaceAndPath(DaClient.ID, "item/karambit");
	private static final ExtraModelKey<Boolean> BASE_KEY = ExtraModelKey.create(() -> "daclient:karambit_base");

	private KarambitModels() {
	}

	public static void register() {
		Identifier id = Identifier.fromNamespaceAndPath(DaClient.ID, "karambit");
		SpecialModelRenderersAccessor.daclient$ids().put(id, KarambitRenderer.Unbaked.MAP_CODEC);
		ConditionalItemModelPropertiesAccessor.daclient$ids().put(id, KarambitEnabled.MAP_CODEC);

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
