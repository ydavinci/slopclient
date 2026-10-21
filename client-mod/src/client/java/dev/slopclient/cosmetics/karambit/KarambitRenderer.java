package dev.slopclient.cosmetics.karambit;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Special item model {@code slopclient:karambit}: the 3D knife in place of the netherite
 * sword. Used by the item definition the mod ships for the sword; in first person the
 * draw and inspect animations are applied on top of the hand pose.
 */
public final class KarambitRenderer implements SpecialModelRenderer<KarambitRenderer.Arg> {
	private final KarambitMesh mesh;
	/** Mesh of the last bake, for drawing the knife in the first-person hand. */
	private static volatile KarambitMesh latest;

	static KarambitMesh latestMesh() {
		return latest;
	}

	private KarambitRenderer(KarambitMesh mesh) {
		this.mesh = mesh;
		if (mesh != null) latest = mesh;
	}

	/**
	 * Where the knife is drawn. {@link #extractArgument} only sees the stack, so the
	 * display context is filled in by {@code SpecialModelWrapperMixin}.
	 */
	public record Arg(ItemDisplayContext context) {
		public static final Arg UNKNOWN = new Arg(ItemDisplayContext.NONE);
		private static final Map<ItemDisplayContext, Arg> BY_CONTEXT = new EnumMap<>(ItemDisplayContext.class);

		static {
			for (ItemDisplayContext c : ItemDisplayContext.values()) BY_CONTEXT.put(c, new Arg(c));
		}

		public static Arg of(ItemDisplayContext context) {
			return BY_CONTEXT.get(context);
		}
	}

	@Override
	public Arg extractArgument(ItemStack stack) {
		return Arg.UNKNOWN;
	}

	@Override
	public void submit(Arg arg, PoseStack pose, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		if (mesh == null) return;
		pose.pushPose();
		KarambitAnimation.animate(pose, arg.context(), mesh.pivot);
		// Enchanted: the same glint type the trident uses.
		var type = foil ? RenderTypes.entitySolidGlint(KarambitMesh.TEXTURE) : RenderTypes.entityCutout(KarambitMesh.TEXTURE);
		collector.submitCustomGeometry(pose, type, (p, out) -> mesh.emit(p, out, light, overlay));
		pose.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> out) {
		if (mesh == null) return;
		Vector3f a = mesh.min, b = mesh.max;
		for (int i = 0; i < 8; i++) {
			out.accept(new Vector3f((i & 1) == 0 ? a.x : b.x, (i & 2) == 0 ? a.y : b.y, (i & 4) == 0 ? a.z : b.z));
		}
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked<Arg> {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<Arg> bake(SpecialModelRenderer.BakingContext context) {
			// Baking runs on every resource reload, so a resource pack can swap the mesh.
			return new KarambitRenderer(KarambitMesh.load(Minecraft.getInstance().getResourceManager()));
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
