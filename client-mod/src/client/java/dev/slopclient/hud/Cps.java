package dev.slopclient.hud;

import java.util.ArrayDeque;

/** Clicks per second for the left and right mouse button, fed by MouseHandlerMixin. */
public final class Cps {
	private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
	private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

	private Cps() {
	}

	public static synchronized void click(int button) {
		// Minecraft 26.3 with SDL3 backend: 1 = left, 3 = right. 0 = legacy GLFW left.
		if (button == 0 || button == 1) LEFT.add(System.currentTimeMillis());
		else if (button == 3) RIGHT.add(System.currentTimeMillis());
	}

	public static synchronized int left() {
		return count(LEFT);
	}

	public static synchronized int right() {
		return count(RIGHT);
	}

	private static int count(ArrayDeque<Long> clicks) {
		long cutoff = System.currentTimeMillis() - 1000;
		while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
		return clicks.size();
	}
}
