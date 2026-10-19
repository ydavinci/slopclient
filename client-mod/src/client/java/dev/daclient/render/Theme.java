package dev.daclient.render;

/** Colours of the DaClient look (same tokens as the launcher, see launcher/DESIGN.md). */
public final class Theme {
	public static final int BG = 0xFF09080E;
	public static final int PANEL = 0xC814111E;
	public static final int PANEL_SOLID = 0xFF15121F;
	public static final int RAISED = 0xFF1D1929;
	public static final int LINE = 0x14FFFFFF;
	public static final int LINE_2 = 0x24FFFFFF;
	public static final int TEXT = 0xFFEFECF7;
	public static final int MUTED = 0xFFA39CB6;
	public static final int DIM = 0xFF726B86;
	public static final int ACCENT = 0xFF8B5CF6;
	public static final int ACCENT_2 = 0xFFC084FC;
	public static final int ACCENT_3 = 0xFFE879F9;
	public static final int ON = 0xFF4ADE80;
	public static final int ERR = 0xFFF87171;

	/** One loop through the chroma gradient takes this long, like the launcher's 6 s animation. */
	private static final double PERIOD_MS = 6000;
	private static final int[] STOPS = {ACCENT, ACCENT_2, ACCENT_3, ACCENT_2, ACCENT};

	private Theme() {
	}

	/** Animated chroma colour; `phase` shifts it (rows and letters use small steps). */
	public static int chroma(double phase) {
		double t = (System.currentTimeMillis() / PERIOD_MS + phase) % 1.0;
		if (t < 0) t += 1;
		double pos = t * (STOPS.length - 1);
		int i = (int) Math.floor(pos);
		return lerp(STOPS[i], STOPS[Math.min(i + 1, STOPS.length - 1)], pos - i);
	}

	public static int lerp(int a, int b, double t) {
		int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
		int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
		return (int) (aa + (ba - aa) * t) << 24
			| (int) (ar + (br - ar) * t) << 16
			| (int) (ag + (bg - ag) * t) << 8
			| (int) (ab + (bb - ab) * t);
	}

	/** Same colour with alpha 0..1. */
	public static int alpha(int color, double alpha) {
		return (int) (Math.max(0, Math.min(1, alpha)) * 255) << 24 | color & 0xFFFFFF;
	}

	/** Ease-out exponential, matches the launcher's motion curve closely enough. */
	public static double ease(double t) {
		t = Math.max(0, Math.min(1, t));
		return t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
	}
}
