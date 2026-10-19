package dev.daclient.hud;

import dev.daclient.module.Category;
import dev.daclient.module.Module;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import dev.daclient.setting.BoolSetting;
import dev.daclient.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A movable HUD element. Its position is stored as fractions of the free screen
 * space (0 = left/top edge, 1 = right/bottom edge), so it survives resizes and GUI scale changes.
 */
public abstract class HudModule extends Module {
	/** Gap to the screen edge in GUI pixels. */
	public static final int MARGIN = 4;

	public double fx;
	public double fy;
	public final NumberSetting scale = add(new NumberSetting("Größe", 1, 0.5, 2, 0.05, v -> String.format("%.2fx", v)));
	public final BoolSetting background;

	/** Last drawn bounds in GUI pixels (used by the HUD editor). */
	public int x, y, w, h;

	protected HudModule(String name, String description, boolean enabled, double fx, double fy, boolean background) {
		super(name, description, Category.HUD, enabled);
		this.fx = fx;
		this.fy = fy;
		this.background = add(new BoolSetting("Hintergrund", background));
	}

	public abstract int width(Minecraft mc);

	public abstract int height(Minecraft mc);

	/** Draws at (0, 0); scale and position are applied by the caller. */
	protected abstract void draw(GuiGraphicsExtractor g, Minecraft mc, boolean editing);

	/** Whether there is something to show (e.g. no effects active). The editor always shows the frame. */
	public boolean hasContent(Minecraft mc) {
		return true;
	}

	@Override
	public boolean listed() {
		return false;
	}

	public void render(GuiGraphicsExtractor g, Minecraft mc, boolean editing) {
		float s = scale.get().floatValue();
		w = Math.round(width(mc) * s);
		h = Math.round(height(mc) * s);
		x = MARGIN + (int) Math.round(fx * (g.guiWidth() - w - 2 * MARGIN));
		y = MARGIN + (int) Math.round(fy * (g.guiHeight() - h - 2 * MARGIN));
		if (!editing && !hasContent(mc)) return;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		draw(g, mc, editing);
		g.pose().popMatrix();
	}

	/** Moves the element so its top-left corner sits at (px, py). */
	public void moveTo(int px, int py, int screenW, int screenH) {
		int freeW = screenW - w - 2 * MARGIN, freeH = screenH - h - 2 * MARGIN;
		fx = freeW <= 0 ? 0 : Math.max(0, Math.min(1, (px - MARGIN) / (double) freeW));
		fy = freeH <= 0 ? 0 : Math.max(0, Math.min(1, (py - MARGIN) / (double) freeH));
	}

	/** Shared look: rounded translucent box when "Hintergrund" is on. */
	protected void box(GuiGraphicsExtractor g, int width, int height) {
		if (background.on()) Draw.round(g, 0, 0, width, height, 3, Theme.alpha(Theme.PANEL_SOLID, 0.72));
	}

	/** Simple one-line element: label in muted colour, value in text colour. */
	protected void labelValue(GuiGraphicsExtractor g, String label, String value) {
		box(g, width(Minecraft.getInstance()), height(Minecraft.getInstance()));
		int px = background.on() ? 5 : 0;
		Draw.shadowText(g, value, px, background.on() ? 4 : 0, Theme.TEXT);
		Draw.shadowText(g, label, px + Draw.width(value) + 4, background.on() ? 4 : 0, Theme.ACCENT_2);
	}

	protected int labelValueWidth(String label, String value) {
		return Draw.width(value) + 4 + Draw.width(label) + (background.on() ? 10 : 0);
	}

	protected int lineHeight() {
		return background.on() ? 16 : 9;
	}
}
