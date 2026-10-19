package dev.daclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.List;

/** One of a fixed list of options; clicking cycles through them. */
public final class ModeSetting extends Setting<String> {
	public final List<String> modes;

	public ModeSetting(String name, String value, String... modes) {
		super(name, value);
		this.modes = List.of(modes);
	}

	public boolean is(String mode) {
		return value.equals(mode);
	}

	public int index() {
		return Math.max(0, modes.indexOf(value));
	}

	public void cycle(int direction) {
		value = modes.get(Math.floorMod(index() + direction, modes.size()));
	}

	@Override
	public String display() {
		return value;
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement json) {
		if (json.isJsonPrimitive() && modes.contains(json.getAsString())) value = json.getAsString();
	}
}
