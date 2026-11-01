package dev.slopclient.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/**
 * What other players share (cosmetics server, `POST /v1/listening/lookup`): looked up every
 * ten seconds for the nearest players, shown above their nametags.
 */
public final class Listening {
	public record Song(String title, String artist) {
	}

	private static final long EVERY_MS = 10_000;
	private static final int MAX = 100;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	/** By uuid without dashes, as the server writes them. */
	private static volatile Map<String, Song> songs = Map.of();
	private static long last;
	private static boolean busy;

	private Listening() {
	}

	private static String server() {
		String s = System.getProperty("slopclient.cosmetics", "").trim().replaceAll("/+$", "");
		return s.isEmpty() ? null : s;
	}

	public static Song of(UUID uuid) {
		return songs.get(uuid.toString().replace("-", ""));
	}

	public static void clear() {
		songs = Map.of();
	}

	/** Self-test: a song for a player, as if the server had it. */
	public static void putForTest(UUID uuid, Song song) {
		Map<String, Song> next = new HashMap<>(songs);
		next.put(uuid.toString().replace("-", ""), song);
		songs = next;
	}

	public static void tick(Minecraft mc) {
		long now = System.currentTimeMillis();
		String server = server();
		if (server == null || mc.level == null || mc.player == null || busy || now - last < EVERY_MS) return;
		last = now;
		JsonArray uuids = new JsonArray();
		mc.level.players().stream()
			.filter(p -> p != mc.player)
			.sorted(Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)))
			.limit(MAX)
			.forEach(p -> uuids.add(p.getUUID().toString()));
		// Alone: nothing to look up (old songs show on nobody and expire with the next lookup).
		if (uuids.isEmpty()) return;
		JsonObject body = new JsonObject();
		body.add("uuids", uuids);
		busy = true;
		HttpRequest req = HttpRequest.newBuilder(URI.create(server + "/v1/listening/lookup"))
			.timeout(Duration.ofSeconds(10))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
			.build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			busy = false;
			if (err != null || res.statusCode() != 200) return;
			try {
				Map<String, Song> found = new HashMap<>();
				for (JsonElement e : JsonParser.parseString(res.body()).getAsJsonArray()) {
					JsonObject o = e.getAsJsonObject();
					found.put(o.get("uuid").getAsString().replace("-", ""), new Song(o.get("title").getAsString(), o.get("artist").getAsString()));
				}
				songs = found;
			} catch (RuntimeException ignored) {
				// An unexpected answer keeps the last songs.
			}
		});
	}
}
