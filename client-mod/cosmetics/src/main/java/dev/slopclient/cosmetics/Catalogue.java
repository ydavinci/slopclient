package dev.slopclient.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Where hats and pets come from: the cosmetics server is their source, so new ones need no
 * mod update. A copy of the server's catalogue and pet textures is kept in
 * {@code config/slopclient-cosmetics-cache/} for the next start and offline play; the
 * catalogue shipped with the mod is the fallback. Same limits as slopclient-core extras.rs.
 */
final class Catalogue {
	static final int MAX_ITEMS = 256, MAX_BOXES = 512, MAX_PARTS = 64, MAX_TEXTURE_SIZE = 512, MAX_TEXTURE_BYTES = 512 * 1024;
	static final double MAX_COORD = 64;
	private static final int MAX_CATALOGUE_BYTES = 4 * 1024 * 1024;
	private static final long REFRESH_MS = 6 * 60 * 60_000L;
	private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");
	private static final Set<String> TIERS = Set.of("shoulder", "flying", "walking");
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	private static long nextRefresh;
	private static String refreshedServer = "";
	private static volatile boolean fetching;
	/** Textures of the installed server catalogue, released when the next one replaces it. */
	private static final List<Identifier> registered = new ArrayList<>();
	private static int generation;

	private Catalogue() {
	}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("slopclient-cosmetics-cache");
	}

	static boolean textureNameOk(String name) {
		return name.endsWith(".png") && ID.matcher(name.substring(0, name.length() - 4)).matches();
	}

	// ---------- checking ----------

	private static void require(boolean ok, String what) {
		if (!ok) throw new IllegalArgumentException("cosmetics catalogue: " + what);
	}

	private static boolean coord(JsonElement e) {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
		double v = e.getAsDouble();
		return Double.isFinite(v) && Math.abs(v) <= MAX_COORD;
	}

	private static boolean coords(JsonElement e, int n) {
		if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) return false;
		for (JsonElement v : e.getAsJsonArray()) if (!coord(v)) return false;
		return true;
	}

	private static boolean shortString(JsonElement e, int max) {
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && !e.getAsString().isEmpty() && e.getAsString().length() <= max;
	}

	/** Checks a catalogue against the limits and returns the texture files it names. */
	static Set<String> validate(JsonObject root) {
		JsonArray hats = root.getAsJsonArray("hats"), pets = root.getAsJsonArray("pets");
		JsonArray accessories = root.has("accessories") ? root.getAsJsonArray("accessories") : new JsonArray();
		require(hats != null && pets != null, "hats or pets missing");
		require(hats.size() <= MAX_ITEMS && pets.size() <= MAX_ITEMS && accessories.size() <= MAX_ITEMS, "too many hats, pets or accessories");
		Set<String> ids = new HashSet<>();
		Set<String> textures = new LinkedHashSet<>();
		List<JsonElement> all = new ArrayList<>();
		hats.forEach(all::add);
		pets.forEach(all::add);
		accessories.forEach(all::add);
		for (JsonElement el : all) {
			JsonObject o = el.getAsJsonObject();
			require(shortString(o.get("id"), 40) && ID.matcher(o.get("id").getAsString()).matches(), "invalid id");
			require(ids.add(o.get("id").getAsString()), "id twice");
			require(shortString(o.get("name"), 40), "invalid name");
		}
		for (JsonElement el : hats) {
			JsonArray boxes = el.getAsJsonObject().getAsJsonArray("boxes");
			require(boxes != null && boxes.size() <= MAX_BOXES, "invalid hat boxes");
			for (JsonElement b : boxes) {
				JsonArray a = b.getAsJsonArray();
				require(a.size() >= 7 && a.size() <= 8 && shortString(a.get(6), 16), "invalid hat box");
				for (int i = 0; i < 6; i++) require(coord(a.get(i)), "hat box out of range");
			}
		}
		for (JsonElement el : pets) {
			JsonObject p = el.getAsJsonObject();
			require(!p.has("tier") || TIERS.contains(p.get("tier").getAsString()), "unknown tier");
			if (p.has("texture")) {
				String t = p.get("texture").getAsString();
				require(textureNameOk(t), "invalid texture name");
				textures.add(t);
			}
			if (p.has("textureSize")) {
				JsonArray size = p.getAsJsonArray("textureSize");
				require(size.size() == 2, "invalid texture size");
				for (JsonElement v : size) require(v.getAsInt() >= 1 && v.getAsInt() <= MAX_TEXTURE_SIZE, "invalid texture size");
			}
			if (p.has("scale")) require(p.get("scale").getAsDouble() > 0 && p.get("scale").getAsDouble() <= 4, "invalid scale");
			JsonArray parts = p.has("parts") ? p.getAsJsonArray("parts") : new JsonArray();
			require(parts.size() <= MAX_PARTS, "too many parts");
			int total = 0;
			for (JsonElement pe : parts) {
				JsonObject part = pe.getAsJsonObject();
				require(coords(part.get("pivot"), 3), "invalid pivot");
				JsonArray boxes = part.getAsJsonArray("boxes");
				require(boxes != null, "part without boxes");
				total += boxes.size();
				for (JsonElement b : boxes) {
					JsonObject box = b.getAsJsonObject();
					require(coords(box.get("from"), 3) && coords(box.get("size"), 3), "pet box out of range");
					require(!box.has("color") || shortString(box.get("color"), 16), "invalid colour");
				}
			}
			require(total <= MAX_BOXES, "too many pet boxes");
		}
		for (JsonElement el : accessories) textures.add(validateAccessory(el.getAsJsonObject()));
		return textures;
	}

	private static final Set<String> FACES = Set.of("north", "south", "east", "west", "up", "down");

	/** Same rules as validate_accessory in slopclient-core extras.rs; returns the texture file. */
	private static String validateAccessory(JsonObject a) {
		require(a.has("attach") && Set.of("body", "head").contains(a.get("attach").getAsString()), "attach must be body or head");
		String texture = a.has("texture") ? a.get("texture").getAsString() : "";
		require(textureNameOk(texture), "invalid accessory texture");
		double uvMax = 16;
		if (a.has("uvSize")) {
			JsonArray size = a.getAsJsonArray("uvSize");
			require(size.size() == 2, "invalid uvSize");
			for (JsonElement v : size) require(v.getAsDouble() >= 1 && v.getAsDouble() <= MAX_TEXTURE_SIZE, "invalid uvSize");
			uvMax = Math.max(size.get(0).getAsDouble(), size.get(1).getAsDouble());
		}
		for (String key : List.of("pivot", "position")) require(!a.has(key) || coords(a.get(key), 3), "invalid pivot or position");
		if (a.has("rotation")) {
			JsonArray r = a.getAsJsonArray("rotation");
			require(r.size() == 3, "invalid rotation");
			for (JsonElement d : r) require(Double.isFinite(d.getAsDouble()) && Math.abs(d.getAsDouble()) <= 360, "invalid rotation");
		}
		if (a.has("scale")) require(a.get("scale").getAsDouble() > 0 && a.get("scale").getAsDouble() <= 4, "invalid scale");
		JsonArray elements = a.getAsJsonArray("elements");
		require(elements != null && elements.size() <= MAX_BOXES, "invalid elements");
		for (JsonElement el : elements) {
			JsonObject e = el.getAsJsonObject();
			require(coords(e.get("from"), 3) && coords(e.get("to"), 3), "element out of range");
			if (e.has("rotation")) {
				JsonObject r = e.getAsJsonObject("rotation");
				require(coords(r.get("origin"), 3) && Set.of("x", "y", "z").contains(r.get("axis").getAsString()) && Math.abs(r.get("angle").getAsDouble()) <= 45,
					"invalid element rotation");
			}
			JsonObject faces = e.getAsJsonObject("faces");
			require(faces != null, "element without faces");
			for (var face : faces.entrySet()) {
				require(FACES.contains(face.getKey()), "unknown face");
				JsonObject f = face.getValue().getAsJsonObject();
				JsonArray uv = f.getAsJsonArray("uv");
				require(uv != null && uv.size() == 4, "invalid uv");
				for (JsonElement u : uv) require(u.getAsDouble() >= 0 && u.getAsDouble() <= uvMax, "uv out of range");
				require(!f.has("rotation") || Set.of(0, 90, 180, 270).contains(f.get("rotation").getAsInt()), "invalid uv rotation");
			}
		}
		return texture;
	}

	/** Width and height from a PNG header, without decoding; null if it is no PNG. */
	private static int[] pngSize(byte[] png) {
		byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
		if (png.length < 24) return null;
		for (int i = 0; i < 8; i++) if (png[i] != sig[i]) return null;
		int w = (png[16] & 255) << 24 | (png[17] & 255) << 16 | (png[18] & 255) << 8 | png[19] & 255;
		int h = (png[20] & 255) << 24 | (png[21] & 255) << 16 | (png[22] & 255) << 8 | png[23] & 255;
		return new int[] {w, h};
	}

	private static boolean textureOk(byte[] png) {
		int[] size = pngSize(png);
		return png.length <= MAX_TEXTURE_BYTES && size != null && size[0] >= 1 && size[1] >= 1 && size[0] <= MAX_TEXTURE_SIZE && size[1] <= MAX_TEXTURE_SIZE;
	}

	// ---------- installing ----------

	/** Makes a checked catalogue the active one (render thread): textures first, then the models. */
	private static void install(JsonObject root, Map<String, byte[]> pngs) {
		int gen = ++generation;
		Map<String, Identifier> ids = new HashMap<>();
		List<Identifier> fresh = new ArrayList<>();
		var textures = Minecraft.getInstance().getTextureManager();
		for (var e : pngs.entrySet()) {
			try {
				NativeImage image = NativeImage.read(e.getValue());
				Identifier id = Identifier.fromNamespaceAndPath(Cosmetics.NS, "catalogue/" + gen + "/" + e.getKey());
				textures.register(id, new DynamicTexture(() -> "slopclient pet " + id, image));
				ids.put(e.getKey(), id);
				fresh.add(id);
			} catch (IOException | RuntimeException ex) {
				Cosmetics.LOG.warn("Could not load pet texture {}", e.getKey(), ex);
			}
		}
		// Pets whose texture failed fall back to the shipped one of the same name.
		Extras.install(root, name -> ids.getOrDefault(name, Extras.shippedTexture(name)));
		registered.forEach(textures::release);
		registered.clear();
		registered.addAll(fresh);
	}

	/** The copy on disk, if it belongs to the server in use (render thread). */
	static boolean loadCached() {
		String server = Cosmetics.settings().server();
		Path dir = dir();
		try {
			if (server.isEmpty() || !server.equals(Files.readString(dir.resolve("server")).trim())) return false;
			JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("extras.json"))).getAsJsonObject();
			Map<String, byte[]> pngs = new HashMap<>();
			for (String name : validate(root)) {
				Path file = dir.resolve("pets").resolve(name);
				if (Files.isRegularFile(file)) {
					byte[] png = Files.readAllBytes(file);
					if (textureOk(png)) pngs.put(name, png);
				}
			}
			install(root, pngs);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	// ---------- fetching ----------

	/** Fetches the server's catalogue at start, after a server change and every few hours. */
	static void tick(Minecraft mc) {
		String server = Cosmetics.settings().server();
		if (server.isEmpty() || fetching) return;
		long now = System.currentTimeMillis();
		if (server.equals(refreshedServer) && now < nextRefresh) return;
		refreshedServer = server;
		nextRefresh = now + REFRESH_MS;
		fetching = true;
		Thread.ofVirtual().name("slopclient-catalogue").start(() -> {
			try {
				refresh(mc, server);
			} catch (Exception e) {
				Cosmetics.LOG.warn("Could not update hats and pets from {}: {}", server, e.toString());
				// Try again soon rather than in hours (server down, no network yet).
				nextRefresh = System.currentTimeMillis() + 5 * 60_000L;
			} finally {
				fetching = false;
			}
		});
	}

	private static byte[] get(URI uri, String etag, int max, int[] status, String[] newEtag) throws IOException, InterruptedException {
		HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET();
		if (etag != null) req.header("If-None-Match", etag);
		HttpResponse<byte[]> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
		status[0] = resp.statusCode();
		if (newEtag != null) newEtag[0] = resp.headers().firstValue("etag").orElse("");
		byte[] body = resp.body();
		if (body.length > max) throw new IOException("answer too large");
		return body;
	}

	private static void refresh(Minecraft mc, String server) throws IOException, InterruptedException {
		Path dir = dir();
		boolean same = Files.isRegularFile(dir.resolve("extras.json")) && Files.isRegularFile(dir.resolve("server"))
			&& server.equals(Files.readString(dir.resolve("server")).trim());
		String etag = same && Files.isRegularFile(dir.resolve("etag")) ? Files.readString(dir.resolve("etag")).trim() : null;
		int[] status = new int[1];
		String[] newEtag = new String[1];
		byte[] json = get(URI.create(server + "/v1/extras/catalogue"), etag, MAX_CATALOGUE_BYTES, status, newEtag);
		if (status[0] == 304) return;
		if (status[0] != 200) throw new IOException("HTTP " + status[0]);
		String text = new String(json, StandardCharsets.UTF_8);
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		Map<String, byte[]> pngs = new HashMap<>();
		for (String name : validate(root)) {
			byte[] png = get(URI.create(server + "/v1/extras/textures/" + URLEncoder.encode(name, StandardCharsets.UTF_8)), null, MAX_TEXTURE_BYTES, status, null);
			if (status[0] != 200 || !textureOk(png)) throw new IOException("invalid texture " + name);
			pngs.put(name, png);
		}
		// Replace the copy on disk in one step.
		Path fresh = dir.resolveSibling(dir.getFileName() + ".new");
		deleteTree(fresh);
		Files.createDirectories(fresh.resolve("pets"));
		for (var e : pngs.entrySet()) Files.write(fresh.resolve("pets").resolve(e.getKey()), e.getValue());
		Files.writeString(fresh.resolve("extras.json"), text);
		Files.writeString(fresh.resolve("etag"), newEtag[0]);
		Files.writeString(fresh.resolve("server"), server);
		deleteTree(dir);
		Files.move(fresh, dir);
		mc.execute(() -> install(root, pngs));
		Cosmetics.LOG.info("Hats and pets updated from {} ({} textures)", server, pngs.size());
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) return;
		try (var walk = Files.walk(path)) {
			for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
		}
	}
}
