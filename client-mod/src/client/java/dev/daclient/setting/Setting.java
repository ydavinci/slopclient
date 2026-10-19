package dev.daclient.setting;

import com.google.gson.JsonElement;

/** A module option shown in the ClickGUI and stored in the config. */
public abstract sealed class Setting<T> permits BoolSetting, NumberSetting, ModeSetting {
	private final String name;
	protected T value;
	private final T initial;

	protected Setting(String name, T value) {
		this.name = name;
		this.value = value;
		this.initial = value;
	}

	/** Back to the built-in default (new profiles start from here). */
	public void reset() {
		value = initial;
	}

	public String name() {
		return name;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	/** Value as shown next to the name. */
	public abstract String display();

	public abstract JsonElement save();

	public abstract void load(JsonElement json);
}
