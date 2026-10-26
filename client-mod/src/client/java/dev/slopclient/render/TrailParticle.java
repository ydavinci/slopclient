package dev.slopclient.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * One spark of the trail module: a tiny glowing star that drifts, twinkles and fades.
 * Added straight to the particle engine, so it needs no registered particle type and
 * works on any server. Uses vanilla's firework sparks, tinted.
 */
public final class TrailParticle extends SingleQuadParticle {
	private static final int SPARKS = 8;
	/** Opacity of the soft glow puffs between the sparks. */
	private static final float HAZE = 0.3f;
	private final TextureAtlasSprite[] sprites;
	private final float size;
	private final float twinkle;
	private final boolean haze;

	private TrailParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
		TextureAtlasSprite[] sprites, int rgb, boolean haze) {
		super(level, x, y, z, sprites[0]);
		this.sprites = sprites;
		this.haze = haze;
		xd = vx;
		yd = vy;
		zd = vz;
		friction = 0.91f;
		gravity = -0.004f;
		hasPhysics = false;
		lifetime = haze ? 16 + random.nextInt(14) : 24 + random.nextInt(28);
		size = haze ? 0.22f + random.nextFloat() * 0.16f : 0.035f + random.nextFloat() * 0.06f;
		quadSize = size;
		twinkle = random.nextFloat() * 6.28f;
		setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
		setSprite(haze ? sprites[SPARKS] : sprites[random.nextInt(SPARKS)]);
		alpha = haze ? HAZE : 1f;
	}

	/** Adds a spark (or, with `haze`, a soft glow around the sparks) at the given point. */
	public static void spawn(Minecraft mc, double x, double y, double z, double vx, double vy, double vz, int rgb, boolean haze) {
		TextureAtlasSprite[] sprites = sprites(mc);
		if (sprites == null || mc.level == null) return;
		mc.particleEngine.add(new TrailParticle(mc.level, x, y, z, vx, vy, vz, sprites, rgb, haze));
	}

	/** spark_0..7 and glow from the particle atlas; looked up each time, since resource reloads replace them. */
	private static TextureAtlasSprite[] sprites(Minecraft mc) {
		var atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
		TextureAtlasSprite[] out = new TextureAtlasSprite[SPARKS + 1];
		for (int i = 0; i < SPARKS; i++) out[i] = atlas.getSprite(Identifier.withDefaultNamespace("spark_" + i));
		out[SPARKS] = atlas.getSprite(Identifier.withDefaultNamespace("glow"));
		return out;
	}

	@Override
	public void tick() {
		super.tick();
		float life = (float) age / lifetime;
		if (haze) {
			alpha = HAZE * (1 - life) * Math.min(1, age / 3f);
			return;
		}
		// Fades out and shrinks a little; the twinkle is what makes it read as glitter.
		float flicker = 0.55f + 0.45f * (float) Math.sin(age * 0.9f + twinkle);
		alpha = (1 - life * life) * flicker;
		quadSize = size * (1 - 0.5f * life);
		// The firework sparks get smaller from 7 to 0: step down as the spark ages.
		setSprite(sprites[Math.max(0, SPARKS - 1 - (int) (life * SPARKS))]);
	}

	@Override
	protected int getLightCoords(float partialTick) {
		return 0xF000F0;
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}
}
