package dev.slopclient.gui;

import dev.slopclient.SlopClient;
import dev.slopclient.anvil.AnvilPlanner;
import dev.slopclient.render.Draw;
import dev.slopclient.render.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The pinned anvil plan, as a glass card to the left of the anvil window: the item, each
 * step with its cost, and the total. Drawn by the anvil screen while a plan is pinned.
 */
public final class PinnedPlanCard {
	private static final int LINE = 10, STEP = 21, PAD = 7;

	private PinnedPlanCard() {
	}

	/** `left`/`top` are the anvil window's corner (176 × 166). */
	public static void draw(GuiGraphicsExtractor g, int left, int top, int screenW, int screenH) {
		if (!SlopClient.ready() || !SlopClient.modules().anvilPlanner.active()) return;
		var pinned = SlopClient.modules().anvilPlanner.pinned();
		if (pinned == null) return;
		int w = Math.min(190, left - 12);
		if (w < 110) return; // too narrow beside the anvil at this GUI scale
		AnvilPlanner.Plan plan = pinned.plan();
		int h = PAD + LINE + 6 + plan.steps().size() * STEP + 4 + LINE + PAD;
		int x = left - w - 6, y = Math.max(4, Math.min(top, screenH - h - 4));
		Draw.glow(g, x, y, w, h, 5, 0xFF000000, 0.35);
		Draw.round(g, x, y, w, h, 7, Theme.alpha(Theme.PANEL_SOLID, 0.9));
		Draw.roundOutline(g, x, y, w, h, 7, Theme.LINE_2);
		g.fill(x + 7, y, x + w - 7, y + 2, Theme.chroma(0));
		Draw.shadowText(g, fit(pinned.item(), w - 2 * PAD), x + PAD, y + PAD, Theme.TEXT);

		int ry = y + PAD + LINE + 6;
		int n = 1;
		for (var step : plan.steps()) {
			boolean tooMuch = step.levels() >= AnvilPlanner.TOO_EXPENSIVE;
			String cost = String.valueOf(step.levels());
			int cw = Draw.width(cost) + 8;
			Draw.round(g, x + 4, ry, w - 8, STEP - 3, 4, step.item() ? Theme.alpha(Theme.ACCENT, 0.16) : 0x12FFFFFF);
			Draw.scaledText(g, n + ".", x + 8, ry + 6, 0.85f, Theme.DIM, false);
			int tx = x + 20, tw = w - 20 - cw - 10;
			Draw.scaledText(g, fit(step.left(), Math.round(tw / 0.85f)), tx, ry + 2, 0.85f, Theme.TEXT, false);
			String right = "+ " + step.right() + (step.item() ? "" : " → " + step.result());
			Draw.scaledText(g, fit(right, Math.round(tw / 0.8f)), tx, ry + 10, 0.8f, Theme.ACCENT_2, false);
			Draw.round(g, x + w - 8 - cw, ry + 3, cw, 12, 4, tooMuch ? Theme.alpha(Theme.ERR, 0.3) : 0x18FFFFFF);
			Draw.text(g, cost, x + w - 8 - cw + 4, ry + 5, tooMuch ? Theme.ERR : Theme.TEXT);
			ry += STEP;
			n++;
		}
		Draw.scaledText(g, "Gesamt " + plan.levels() + " Level · " + plan.xp() + " XP", x + PAD, ry + 4, 0.85f, Theme.MUTED, false);
	}

	private static String fit(String s, int maxWidth) {
		if (Draw.width(s) <= maxWidth) return s;
		int end = s.length();
		while (end > 0 && Draw.width(s.substring(0, end) + "…") > maxWidth) end--;
		return s.substring(0, end) + "…";
	}
}
