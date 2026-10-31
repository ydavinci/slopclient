package dev.slopclient.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * One particle of the trail module. Added straight to the particle engine, so it needs
 * no registered particle type and works on any server. Uses vanilla's particle sprites,
 * tinted; each {@link Look} decides sprite, size, life and motion.
 */
public final class TrailParticle extends SingleQuadParticle {
	/** What a trail particle looks like and how it moves. */
	public enum Look {
		/** A tiny glowing star that twinkles; firework sparks, shrinking as it ages. */
		SPARK("spark_", 8, true, 0.035f, 0.06f, 24, 28, -0.004f, 1f, true),
		/** A soft glow puff between the sparks. */
		HAZE("glow", 0, false, 0.22f, 0.16f, 16, 14, -0.002f, 0.3f, false),
		/** A cherry petal that drifts down, turning. */
		PETAL("cherry_", 12, false, 0.07f, 0.05f, 50, 30, 0.0012f, 1f, false),
		/** A little blue soul flame that rises. */
		SOUL("soul_", 11, true, 0.09f, 0.05f, 20, 12, -0.012f, 0.95f, false),
		/** A music note that floats up. */
		NOTE("note", 0, false, 0.09f, 0.03f, 30, 14, -0.01f, 1f, false),
		/** A heart that floats up. */
		HEART("heart", 0, false, 0.08f, 0.04f, 30, 14, -0.008f, 1f, false);

		final String sprite;
		final int frames;
		final boolean animate;
		final float size, sizeSpread;
		final int life, lifeSpread;
		final float gravity, alpha;
		final boolean twinkle;

		Look(String sprite, int frames, boolean animate, float size, float sizeSpread, int life, int lifeSpread, float gravity, float alpha, boolean twinkle) {
			this.sprite = sprite;
			this.frames = frames;
			this.animate = animate;
			this.size = size;
			this.sizeSpread = sizeSpread;
			this.life = life;
			this.lifeSpread = lifeSpread;
			this.gravity = gravity;
			this.alpha = alpha;
			this.twinkle = twinkle;
		}
	}

	private final Look look;
	private final TextureAtlasSprite[] sprites;
	private final float size;
	private final float phase;
	private final float spin;

	private TrailParticle(ClientLevel level, Look look, TextureAtlasSprite[] sprites, double x, double y, double z,
		double vx, double vy, double vz, int rgb) {
		super(level, x, y, z, sprites[0]);
		this.look = look;
		this.sprites = sprites;
		xd = vx;
		yd = vy;
		zd = vz;
		friction = 0.91f;
		gravity = look.gravity;
		hasPhysics = false;
		lifetime = look.life + random.nextInt(look.lifeSpread + 1);
		size = look.size + random.nextFloat() * look.sizeSpread;
		quadSize = size;
		phase = random.nextFloat() * 6.28f;
		spin = (random.nextFloat() - 0.5f) * 0.25f;
		roll = oRoll = random.nextFloat() * 6.28f;
		setColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f);
		setSprite(sprites[look.animate ? 0 : random.nextInt(sprites.length)]);
		alpha = look.alpha;
	}

	/** Adds a particle of `look` at the given point, tinted `rgb` (white keeps the sprite's own colours). */
	public static void spawn(Minecraft mc, Look look, double x, double y, double z, double vx, double vy, double vz, int rgb) {
		if (mc.level == null) return;
		mc.particleEngine.add(new TrailParticle(mc.level, look, sprites(mc, look), x, y, z, vx, vy, vz, rgb));
	}

	/** The look's sprites from the particle atlas; looked up each time, since resource reloads replace them. */
	private static TextureAtlasSprite[] sprites(Minecraft mc, Look look) {
		var atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.PARTICLES);
		if (look.frames == 0) return new TextureAtlasSprite[] {atlas.getSprite(Identifier.withDefaultNamespace(look.sprite))};
		TextureAtlasSprite[] out = new TextureAtlasSprite[look.frames];
		for (int i = 0; i < look.frames; i++) out[i] = atlas.getSprite(Identifier.withDefaultNamespace(look.sprite + i));
		return out;
	}

	@Override
	public void tick() {
		super.tick();
		float life = (float) age / lifetime;
		float fadeIn = Math.min(1, age / 3f);
		switch (look) {
			case SPARK -> {
				// Fades out and shrinks a little; the twinkle is what makes it read as glitter.
				float flicker = 0.55f + 0.45f * (float) Math.sin(age * 0.9f + phase);
				alpha = (1 - life * life) * flicker;
				quadSize = size * (1 - 0.5f * life);
				// The firework sparks get smaller from 7 to 0: step down as the spark ages.
				setSprite(sprites[Math.max(0, sprites.length - 1 - (int) (life * sprites.length))]);
			}
			case PETAL -> {
				// Sways sideways while it sinks, and turns.
				xd += Math.sin(age * 0.15f + phase) * 0.0015;
				oRoll = roll;
				roll += spin;
				alpha = look.alpha * (1 - Math.max(0, life - 0.7f) / 0.3f);
			}
			default -> {
				if (look.animate) setSprite(sprites[Math.min(sprites.length - 1, (int) (life * sprites.length))]);
				alpha = look.alpha * (1 - life * life) * fadeIn;
				if (look == Look.NOTE || look == Look.HEART) quadSize = size * (0.8f + 0.2f * fadeIn);
			}
		}
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
