package dev.daclient.gui;

import dev.daclient.DaClient;
import dev.daclient.hud.HudModule;
import dev.daclient.render.Draw;
import dev.daclient.render.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Drag HUD elements around. Edges snap to the screen edges, the centre lines and
 * other elements; active guides are drawn while dragging. Scroll to resize.
 */
public final class HudEditorScreen extends Screen {
	private static final int SNAP = 4;

	private final Screen parent;
	private HudModule dragging;
	private int dx, dy;
	private Integer guideX, guideY;

	public HudEditorScreen(Screen parent) {
		super(Component.literal("HUD-Editor"));
		this.parent = parent;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		DaClient.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, 0x55000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		// Centre lines stay faintly visible as orientation.
		g.fill(width / 2, 0, width / 2 + 1, height, 0x10FFFFFF);
		g.fill(0, height / 2, width, height / 2 + 1, 0x10FFFFFF);

		for (HudModule hud : DaClient.modules().hud()) {
			if (!hud.enabled()) continue;
			// In a world the HUD itself draws the elements; in menus the editor draws them.
			if (minecraft.player == null) hud.render(g, minecraft, true);
			boolean hover = hud == dragging || Draw.inside(mouseX, mouseY, hud.x, hud.y, hud.w, hud.h);
			Draw.dashed(g, hud.x - 2, hud.y - 2, hud.w + 4, hud.h + 4, hover ? Theme.chroma(0) : 0x66FFFFFF);
			if (hover) Draw.scaledText(g, hud.name, hud.x - 2, Math.max(1, hud.y - 10), 0.85f, Theme.ACCENT_2, true);
		}
		if (dragging != null) {
			if (guideX != null) g.fill(guideX, 0, guideX + 1, height, Theme.chroma(0));
			if (guideY != null) g.fill(0, guideY, width, guideY + 1, Theme.chroma(0.2));
		}
		String help = "Ziehen zum Verschieben · Mausrad: Größe · Esc: fertig";
		Draw.text(g, help, (width - Draw.width(help)) / 2, height - 20, Theme.DIM);
	}

	private HudModule at(double mx, double my) {
		var list = DaClient.modules().hud();
		for (int i = list.size() - 1; i >= 0; i--) {
			HudModule h = list.get(i);
			if (h.enabled() && Draw.inside(mx, my, h.x - 2, h.y - 2, h.w + 4, h.h + 4)) return h;
		}
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		HudModule hit = at(event.x(), event.y());
		if (hit != null && event.button() == 0) {
			dragging = hit;
			dx = (int) event.x() - hit.x;
			dy = (int) event.y() - hit.y;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
		if (dragging == null) return super.mouseDragged(event, deltaX, deltaY);
		int x = (int) event.x() - dx, y = (int) event.y() - dy;
		guideX = null;
		guideY = null;

		// Candidate lines: screen edges (with margin), centre, and other elements' edges.
		int m = HudModule.MARGIN;
		int[] xs = {m, width - m, width / 2};
		int[] ys = {m, height - m, height / 2};
		int[] sx = snap(x, dragging.w, xs), sy = snap(y, dragging.h, ys);
		for (HudModule o : DaClient.modules().hud()) {
			if (o == dragging || !o.enabled()) continue;
			if (sx == null) sx = snap(x, dragging.w, new int[] {o.x, o.x + o.w});
			if (sy == null) sy = snap(y, dragging.h, new int[] {o.y, o.y + o.h});
		}
		if (sx != null) {
			x = sx[0];
			guideX = sx[1];
		}
		if (sy != null) {
			y = sy[0];
			guideY = sy[1];
		}
		dragging.moveTo(x, y, width, height);
		dragging.x = x;
		dragging.y = y;
		return true;
	}

	/** {snapped start, guide line} if the element's start, centre or end is near a line. */
	private static int[] snap(int pos, int size, int[] lines) {
		for (int line : lines) {
			if (Math.abs(pos - line) <= SNAP) return new int[] {line, line};
			if (Math.abs(pos + size / 2 - line) <= SNAP) return new int[] {line - size / 2, line};
			if (Math.abs(pos + size - line) <= SNAP) return new int[] {line - size, line};
		}
		return null;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = null;
		guideX = null;
		guideY = null;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		HudModule hit = at(mx, my);
		if (hit == null) return false;
		hit.scale.set(hit.scale.get() + (scrollY > 0 ? 0.1 : -0.1));
		return true;
	}
}
