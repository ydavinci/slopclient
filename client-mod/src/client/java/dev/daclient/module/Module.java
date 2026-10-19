package dev.daclient.module;

import dev.daclient.setting.Setting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * A toggleable feature. Only legit features belong here: HUD, visuals and comfort,
 * nothing that gives a combat or movement advantage.
 */
public abstract class Module {
	public final String name;
	public final String description;
	public final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final boolean defaultEnabled;
	private boolean enabled;

	protected Module(String name, String description, Category category, boolean enabled) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.defaultEnabled = enabled;
		this.enabled = enabled;
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public List<Setting<?>> settings() {
		return settings;
	}

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		this.enabled = enabled;
		if (enabled) onEnable();
		else onDisable();
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public boolean defaultEnabled() {
		return defaultEnabled;
	}

	/** Short value shown next to the name in the ArrayList, e.g. "4x". */
	public String suffix() {
		return "";
	}

	/** Hidden from the ArrayList (the overlays themselves). */
	public boolean listed() {
		return true;
	}

	protected void onEnable() {
	}

	protected void onDisable() {
	}

	/** Called every client tick while enabled. */
	public void tick(Minecraft mc) {
	}
}
