package dev.slopclient.render;

/**
 * Colours of the SlopClient look (same tokens as the launcher, see launcher/DESIGN.md).
 *
 * <p>The launcher's accent colour arrives as {@code -Dslopclient.accent} (an OKLCH hue in
 * degrees). Every tinted token is then turned by the difference to the original violet,
 * keeping lightness and chroma, exactly like launcher/src/lib/accent.ts.
 */
public final class Theme {
	/** Hue (OKLCH degrees) of the original violet accent. */
	public static final double DEFAULT_HUE = 293;

	public static int BG = 0xFF09080E;
	public static int PANEL = 0xC814111E;
	public static int PANEL_SOLID = 0xFF15121F;
	public static int RAISED = 0xFF1D1929;
	public static final int LINE = 0x14FFFFFF;
	public static final int LINE_2 = 0x24FFFFFF;
	public static int TEXT = 0xFFEFECF7;
	public static int MUTED = 0xFFA39CB6;
	public static int DIM = 0xFF726B86;
	public static int ACCENT = 0xFF8B5CF6;
	public static int ACCENT_2 = 0xFFC084FC;
	public static int ACCENT_3 = 0xFFE879F9;
	public static final int ON = 0xFF4ADE80;
	public static final int ERR = 0xFFF87171;

	/** Hue shift in degrees (-180..180), also used by the menu shader. */
	public static final double SHIFT;

	/** One loop through the chroma gradient takes this long, like the launcher's 6 s animation. */
	private static final double PERIOD_MS = 6000;
	private static final int[] STOPS;

	static {
		double shift = 0;
		try {
			String prop = System.getProperty("slopclient.accent");
			if (prop != null && !prop.isBlank()) shift = ((Double.parseDouble(prop) - DEFAULT_HUE) % 360 + 540) % 360 - 180;
		} catch (NumberFormatException ignored) {
			// A broken value keeps the violet.
		}
		SHIFT = shift;
		if (Math.abs(shift) > 0.5) {
			BG = turn(BG, shift);
			PANEL = turn(PANEL, shift);
			PANEL_SOLID = turn(PANEL_SOLID, shift);
			RAISED = turn(RAISED, shift);
			TEXT = turn(TEXT, shift);
			MUTED = turn(MUTED, shift);
			DIM = turn(DIM, shift);
			ACCENT = turn(ACCENT, shift);
			ACCENT_2 = turn(ACCENT_2, shift);
			ACCENT_3 = turn(ACCENT_3, shift);
		}
		STOPS = new int[] {ACCENT, ACCENT_2, ACCENT_3, ACCENT_2, ACCENT};
	}

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

	// ---------- OKLCH ----------

	/** ARGB colour with its OKLCH hue turned by `shift` degrees. */
	static int turn(int argb, double shift) {
		double r = linear((argb >> 16 & 255) / 255.0), g = linear((argb >> 8 & 255) / 255.0), b = linear((argb & 255) / 255.0);
		double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
		double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
		double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
		double L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
		double A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
		double B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
		double c = Math.hypot(A, B), h = Math.atan2(B, A) + Math.toRadians(shift);
		A = c * Math.cos(h);
		B = c * Math.sin(h);
		l = Math.pow(L + 0.3963377774 * A + 0.2158037573 * B, 3);
		m = Math.pow(L - 0.1055613458 * A - 0.0638541728 * B, 3);
		s = Math.pow(L - 0.0894841775 * A - 1.291485548 * B, 3);
		int nr = channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
		int ng = channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
		int nb = channel(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s);
		return argb & 0xFF000000 | nr << 16 | ng << 8 | nb;
	}

	private static double linear(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	private static int channel(double c) {
		double v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(Math.max(0, c), 1 / 2.4) - 0.055;
		return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
	}
}
