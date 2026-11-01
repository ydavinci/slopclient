package dev.slopclient.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.slopclient.SlopClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The launcher's music helper (`<launcher> --media-helper`, slopclient_core::media): a child
 * process that reports what a music app plays and runs play/pause and skip. It also shares
 * the song with the cosmetics server itself, so the launcher's session never reaches the game.
 */
public final class MusicHelper {
	/** What plays; `cover` is a PNG, only set when the track changed (keep the last one). */
	public record Track(String app, String title, String artist, String album, boolean playing, long positionMs, long durationMs,
		long receivedAt, byte[] cover) {
		/** The position now, counting on while it plays. */
		public long position() {
			long p = positionMs + (playing ? System.currentTimeMillis() - receivedAt : 0);
			return durationMs > 0 ? Math.min(p, durationMs) : p;
		}
	}

	private static final long RESTART_MS = 10_000;
	private static Process process;
	private static Writer input;
	private static volatile Track track;
	private static volatile String error;
	private static long lastStart;
	private static Boolean shared;

	private MusicHelper() {
	}

	/** The helper's program, passed by the launcher; null when started some other way. */
	private static String program() {
		String p = System.getProperty("slopclient.helper", "").trim();
		return p.isEmpty() ? null : p;
	}

	public static boolean available() {
		return program() != null;
	}

	public static Track track() {
		return track;
	}

	public static String error() {
		return error;
	}

	/** Keeps the helper running (restarting it after a crash) and tells it whether to share. */
	public static void keepRunning(boolean share) {
		if (program() == null) return;
		if (process == null || !process.isAlive()) {
			long now = System.currentTimeMillis();
			if (now - lastStart < RESTART_MS) return;
			lastStart = now;
			start();
			shared = null;
		}
		if (shared == null || shared != share) {
			send(share ? "share on" : "share off");
			shared = share;
		}
	}

	private static void start() {
		try {
			process = new ProcessBuilder(program(), "--media-helper").redirectError(ProcessBuilder.Redirect.DISCARD).start();
			input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
			Process p = process;
			Thread reader = new Thread(() -> read(p), "slopclient-music");
			reader.setDaemon(true);
			reader.start();
		} catch (Exception e) {
			SlopClient.LOG.warn("Could not start the music helper", e);
			process = null;
		}
	}

	private static void read(Process p) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				try {
					JsonObject o = JsonParser.parseString(line).getAsJsonObject();
					if (o.has("error")) {
						error = o.get("error").getAsString();
					} else if (o.has("none")) {
						track = null;
						error = null;
					} else {
						Track last = track;
						byte[] cover = o.has("cover") ? Base64.getDecoder().decode(o.get("cover").getAsString()) : null;
						String title = text(o, "title"), artist = text(o, "artist");
						// A new track without its own cover must not keep the old one.
						boolean same = last != null && last.title().equals(title) && last.artist().equals(artist);
						if (cover == null && same) cover = last.cover();
						track = new Track(text(o, "app"), title, artist, text(o, "album"), o.has("playing") && o.get("playing").getAsBoolean(),
							o.has("positionMs") ? o.get("positionMs").getAsLong() : 0, o.has("durationMs") ? o.get("durationMs").getAsLong() : 0,
							System.currentTimeMillis(), cover);
						error = null;
					}
				} catch (RuntimeException ignored) {
					// A broken line is skipped; the next one comes within a second.
				}
			}
		} catch (Exception ignored) {
			// The helper ended; keepRunning starts it again.
		}
		track = null;
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** `toggle`, `next` or `previous`. */
	public static void send(String command) {
		if (input == null) return;
		try {
			input.write(command + "\n");
			input.flush();
		} catch (Exception ignored) {
			// The helper is gone; it restarts on the next tick.
		}
	}

	/** Ends the helper: closing its input makes it clear the shared song and quit. */
	public static void stop() {
		try {
			if (input != null) input.close();
		} catch (Exception ignored) {
			// Already closed.
		}
		input = null;
		process = null;
		track = null;
		shared = null;
	}
}
