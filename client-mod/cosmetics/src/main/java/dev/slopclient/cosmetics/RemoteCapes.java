package dev.slopclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

/**
 * Capes, hats and pets of SlopClient players from the cosmetics server in the settings
 * (from the launcher's `-Dslopclient.cosmetics` or the standalone config). Players in view are looked up in batches; every image is
 * downloaded once and shared by everyone wearing it. Textures of players out of view
 * are freed again, and a memory budget limits how many HD animations play at once.
 */
public final class RemoteCapes {
	private static final long TTL_MS = 5 * 60_000, FORGET_MS = 2 * 60_000;
	/** Texture memory for other players' capes; beyond it new capes show their first frame. */
	private static final long BUDGET_BYTES = 256L * 1024 * 1024;
	private static final int LOOKUP_MAX = 100;

	private record Entry(String hash, String url, JsonObject meta, long fetchedAt) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	/** uuid -> cape (null hash: no cape). */
	private static final Map<String, Entry> players = new ConcurrentHashMap<>();
	private static final Map<String, Long> lastSeen = new HashMap<>();
	/** uuid -> {hat, pet} ids from the same lookup. */
	private static final Map<String, String[]> extras = new ConcurrentHashMap<>();
	private static final Set<String> pending = ConcurrentHashMap.newKeySet();
	/** hash -> loaded animation (render thread only). */
	private static final Map<String, CapeAnimation> animations = new HashMap<>();
	private static final Set<String> downloading = ConcurrentHashMap.newKeySet();
	private static long usedBytes;
	private static int ticks;

	private RemoteCapes() {
	}

	private static String server() {
		return Cosmetics.settings().server();
	}

	public static boolean enabled() {
		return !server().isEmpty();
	}

	public static String key(UUID uuid) {
		return uuid.toString().replace("-", "");
	}

	/** Cape of a player if it is known and loaded. */
	public static CapeAnimation get(UUID uuid) {
		Entry e = players.get(key(uuid));
		return e == null || e.hash == null ? null : animations.get(e.hash);
	}

	public static String hat(UUID uuid) {
		String[] e = extras.get(key(uuid));
		return e == null ? null : e[0];
	}

	public static String pet(UUID uuid) {
		String[] e = extras.get(key(uuid));
		return e == null ? null : e[1];
	}

	private static String text(JsonObject o, String key) {
		JsonElement v = o.get(key);
		return v == null || v.isJsonNull() ? null : v.getAsString();
	}

	public static void tick(Minecraft mc) {
		if (!enabled() || mc.level == null) return;
		if (++ticks % 40 != 0) return;
		long now = System.currentTimeMillis();
		List<String> stale = new ArrayList<>();
		for (AbstractClientPlayer p : mc.level.players()) {
			String id = key(p.getUUID());
			lastSeen.put(id, now);
			Entry e = players.get(id);
			if ((e == null || now - e.fetchedAt > TTL_MS) && !pending.contains(id)) stale.add(id);
		}
		for (int i = 0; i < stale.size(); i += LOOKUP_MAX) lookup(stale.subList(i, Math.min(stale.size(), i + LOOKUP_MAX)));
		// Make sure every cape in use is loaded.
		for (Entry e : players.values()) {
			if (e.hash != null && !animations.containsKey(e.hash)) download(e);
		}
		if (ticks % 1200 == 0) forget(now);
	}

	private static void lookup(List<String> uuids) {
		List<String> batch = new ArrayList<>(uuids);
		pending.addAll(batch);
		JsonObject body = new JsonObject();
		JsonArray arr = new JsonArray();
		batch.forEach(arr::add);
		body.add("uuids", arr);
		HttpRequest req = HttpRequest.newBuilder(URI.create(server() + "/v1/cosmetics/lookup"))
			.timeout(Duration.ofSeconds(10))
			.header("content-type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
			.build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			batch.forEach(pending::remove);
			if (err != null || resp.statusCode() != 200) return;
			long now = System.currentTimeMillis();
			try {
				for (JsonElement el : JsonParser.parseString(resp.body()).getAsJsonArray()) {
					JsonObject o = el.getAsJsonObject();
					extras.put(o.get("uuid").getAsString(), new String[] {text(o, "hat"), text(o, "pet")});
					JsonElement cape = o.get("cape");
					if (cape == null || cape.isJsonNull()) {
						players.put(o.get("uuid").getAsString(), new Entry(null, null, null, now));
					} else {
						JsonObject c = cape.getAsJsonObject();
						players.put(o.get("uuid").getAsString(), new Entry(c.get("hash").getAsString(), c.get("url").getAsString(), c.getAsJsonObject("meta"), now));
					}
				}
			} catch (RuntimeException e) {
				Cosmetics.LOG.warn("Unexpected cosmetics lookup answer", e);
			}
		});
	}

	private static void download(Entry e) {
		if (!downloading.add(e.hash)) return;
		HttpRequest req = HttpRequest.newBuilder(URI.create(e.url)).timeout(Duration.ofSeconds(20)).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((resp, err) -> {
			if (err != null || resp.statusCode() != 200) {
				downloading.remove(e.hash);
				return;
			}
			byte[] bytes = resp.body();
			// Textures are created on the render thread.
			Minecraft.getInstance().execute(() -> {
				downloading.remove(e.hash);
				try (NativeImage sheet = NativeImage.read(bytes)) {
					int frames = e.meta.get("frames").getAsInt(), scale = e.meta.get("scale").getAsInt();
					long full = (long) 64 * scale * 32 * scale * 4 * frames;
					boolean firstOnly = usedBytes + full > BUDGET_BYTES;
					CapeAnimation anim = CapeAnimation.fromSheet(sheet, e.hash.substring(0, 16), frames, scale,
						e.meta.has("frameTimeMs") ? e.meta.get("frameTimeMs").getAsInt() : 100,
						!e.meta.has("elytra") || e.meta.get("elytra").getAsBoolean(), firstOnly);
					usedBytes += anim.bytes;
					animations.put(e.hash, anim);
				} catch (Exception ex) {
					Cosmetics.LOG.warn("Could not load cape {}", e.url, ex);
				}
			});
		});
	}

	/** Drops players out of view and frees capes nobody in view wears any more. */
	private static void forget(long now) {
		lastSeen.entrySet().removeIf(en -> {
			if (now - en.getValue() <= FORGET_MS) return false;
			players.remove(en.getKey());
			return true;
		});
		Set<String> inUse = new HashSet<>();
		for (Entry e : players.values()) if (e.hash != null) inUse.add(e.hash);
		animations.entrySet().removeIf(en -> {
			if (inUse.contains(en.getKey())) return false;
			usedBytes -= en.getValue().bytes;
			en.getValue().release();
			return true;
		});
	}

	/** Leaving a world: everything is looked up again next time. */
	public static void clear() {
		players.clear();
		lastSeen.clear();
		Minecraft.getInstance().execute(() -> {
			animations.values().forEach(CapeAnimation::release);
			animations.clear();
			usedBytes = 0;
		});
	}
}
