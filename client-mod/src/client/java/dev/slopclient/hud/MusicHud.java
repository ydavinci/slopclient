package dev.slopclient.hud;

import com.mojang.blaze3d.platform.NativeImage;
import dev.slopclient.SlopClient;
import dev.slopclient.music.Listening;
import dev.slopclient.music.MusicHelper;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import dev.slopclient.setting.BoolSetting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Music: what Spotify, Cider, Apple Music or another music app plays (the launcher's music
 * helper reads the Windows media session; browsers never count), with cover, progress and
 * keys for play/pause and skip. Shared with other SlopClient players only when "Teilen" is
 * on; their songs show above their nametags.
 */
public final class MusicHud extends HudModule {
	private static final int W = 168, H = 44, COVER = 34;
	private static final Identifier COVER_TEXTURE = Identifier.fromNamespaceAndPath(SlopClient.ID, "music/cover");

	public final BoolSetting share = add(new BoolSetting("Teilen", false));
	public final BoolSetting others = add(new BoolSetting("Bei anderen zeigen", true));
	public final BoolSetting showCover = add(new BoolSetting("Cover", true));
	private KeyMapping toggleKey, nextKey, previousKey;
	private byte[] coverShown;
	private boolean hasCover;

	public MusicHud() {
		super("Musik", "Spotify, Cider oder Apple Music: Titel, Cover und Steuerung", false, 1, 0.62, true);
	}

	public void bind(KeyMapping toggle, KeyMapping next, KeyMapping previous) {
		toggleKey = toggle;
		nextKey = next;
		previousKey = previous;
	}

	@Override
	public void tick(Minecraft mc) {
		MusicHelper.keepRunning(share.on());
		while (toggleKey != null && toggleKey.consumeClick()) MusicHelper.send("toggle");
		while (nextKey != null && nextKey.consumeClick()) MusicHelper.send("next");
		while (previousKey != null && previousKey.consumeClick()) MusicHelper.send("previous");
		if (others.on()) Listening.tick(mc);
		else Listening.clear();
	}

	@Override
	protected void onDisable() {
		MusicHelper.stop();
		Listening.clear();
	}

	/** The line above another player's nametag, or null. */
	public String lineFor(java.util.UUID uuid) {
		if (!active() || !others.on()) return null;
		Minecraft mc = Minecraft.getInstance();
		var song = Listening.of(uuid);
		if (song == null && mc.player != null && uuid.equals(mc.player.getUUID())) {
			// Your own (third person, own nametag on): straight from the helper while sharing.
			var t = MusicHelper.track();
			return share.on() && t != null && t.playing() ? line(t.title(), t.artist()) : null;
		}
		return song == null ? null : line(song.title(), song.artist());
	}

	private static String line(String title, String artist) {
		String s = artist.isEmpty() ? title : title + " – " + artist;
		return s.length() > 48 ? s.substring(0, 47) + "…" : s;
	}

	@Override
	public boolean hasContent(Minecraft mc) {
		return MusicHelper.track() != null;
	}

	@Override
	public int width(Minecraft mc) {
		return W;
	}

	@Override
	public int height(Minecraft mc) {
		return H;
	}

	@Override
	protected void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
		var t = MusicHelper.track();
		if (t == null) {
			if (!editing) return;
			t = new MusicHelper.Track("Spotify", "Kein Titel", MusicHelper.available() ? "Spiel Musik in Spotify oder Cider" : "Nur mit dem SlopClient-Launcher",
				"", false, 0, 0, System.currentTimeMillis(), null);
		}
		box(g, W, H);
		int x = 5, y = 5;
		boolean cover = showCover.on() && updateCover(mc, t.cover());
		if (showCover.on()) {
			if (cover) {
				g.blit(RenderPipelines.GUI_TEXTURED, COVER_TEXTURE, x, y, 0, 0, COVER, COVER, COVER, COVER, 0xFFFFFFFF);
			} else {
				Draw.round(g, x, y, COVER, COVER, 4, Theme.alpha(Theme.ACCENT, 0.35));
				Draw.text(g, "♪", x + COVER / 2 - Draw.width("♪") / 2, y + COVER / 2 - 4, Theme.TEXT);
			}
			x += COVER + 6;
		}
		int textW = W - x - 18;
		Draw.shadowText(g, fit(t.title(), textW), x, y + 1, Theme.TEXT);
		String sub = t.artist().isEmpty() ? t.app() : t.artist() + " · " + t.app();
		Draw.scaledText(g, fit(sub, Math.round(textW / 0.85f)), x, y + 12, 0.85f, Theme.MUTED, false);
		playState(g, W - 14, y + 2, t.playing());
		// Progress, when the app tells the length (Cider, for one, does not).
		int barY = y + COVER - 4;
		if (t.durationMs() > 0) {
			int barW = W - x - 6;
			double k = Math.min(1, (double) t.position() / t.durationMs());
			g.fill(x, barY, x + barW, barY + 2, 0x33FFFFFF);
			g.fill(x, barY, x + (int) Math.round(barW * k), barY + 2, Theme.chroma(0));
			String time = clock(t.position()) + " / " + clock(t.durationMs());
			Draw.scaledText(g, time, x, barY - 9, 0.75f, Theme.DIM, false);
		} else if (share.on()) {
			Draw.scaledText(g, "Wird geteilt", x, barY - 6, 0.75f, Theme.ACCENT_2, false);
		}
	}

	/** A small play triangle or pause bars. */
	private static void playState(GuiGraphicsExtractor g, int x, int y, boolean playing) {
		int c = Theme.alpha(Theme.TEXT, 0.85);
		if (playing) {
			g.fill(x, y, x + 2, y + 7, c);
			g.fill(x + 4, y, x + 6, y + 7, c);
		} else {
			for (int i = 0; i < 4; i++) g.fill(x + i, y + i, x + i + 1, y + 7 - i, c);
		}
	}

	/** Uploads a new cover when it changed; false when there is none. */
	private boolean updateCover(Minecraft mc, byte[] png) {
		if (png == null) {
			coverShown = null;
			return false;
		}
		if (png != coverShown) {
			coverShown = png;
			try {
				NativeImage image = NativeImage.read(png);
				mc.getTextureManager().register(COVER_TEXTURE, new DynamicTexture(() -> "slopclient music cover", image));
				hasCover = true;
			} catch (Exception e) {
				hasCover = false;
			}
		}
		return hasCover;
	}

	private static String clock(long ms) {
		long s = ms / 1000;
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	private static String fit(String s, int max) {
		if (Draw.width(s) <= max) return s;
		int end = s.length();
		while (end > 0 && Draw.width(s.substring(0, end) + "…") > max) end--;
		return s.substring(0, end) + "…";
	}
}
