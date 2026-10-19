package dev.daclient.module;

import dev.daclient.setting.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;

/**
 * A toggleable feature. Only legit features belong here: HUD, visuals and comfort,
 * nothing that gives a combat or movement advantage.
 *
 * <p>Servers can switch modules off for the session (see {@code ServerRules}); a
 * module then stays enabled in the menu but is not {@link #active()}.
 */
public abstract class Module {
	public final String name;
	public final String description;
	public final Category category;
	private final List<Setting<?>> settings = new ArrayList<>();
	private final boolean defaultEnabled;
	private boolean enabled;
	private boolean serverLocked;

	protected Module(String name, String description, Category category, boolean enabled) {
		this.name = name;
		this.description = description;
		this.category = category;
		this.defaultEnabled = enabled;
		this.enabled = enabled;
	}

	/** Stable id used by servers to address the module, e.g. "freelook", "toggle-sprint". */
	public String id() {
		return name.toLowerCase(Locale.ROOT)
			.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-|-$", "");
	}

	protected <S extends Setting<?>> S add(S setting) {
		settings.add(setting);
		return setting;
	}

	public List<Setting<?>> settings() {
		return settings;
	}

	/** The user's choice in the menu. */
	public boolean enabled() {
		return enabled;
	}

	/** Enabled and not switched off by the current server. */
	public boolean active() {
		return enabled && !serverLocked;
	}

	public boolean serverLocked() {
		return serverLocked;
	}

	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) return;
		boolean was = active();
		this.enabled = enabled;
		changed(was);
	}

	public void setServerLocked(boolean locked) {
		if (serverLocked == locked) return;
		boolean was = active();
		serverLocked = locked;
		changed(was);
	}

	private void changed(boolean wasActive) {
		if (active() && !wasActive) onEnable();
		else if (!active() && wasActive) onDisable();
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public boolean defaultEnabled() {
		return defaultEnabled;
	}

	/** Built-in defaults for the enabled state and all settings. */
	public void reset() {
		setEnabled(defaultEnabled);
		for (Setting<?> s : settings) s.reset();
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

	/** Called every client tick while active. */
	public void tick(Minecraft mc) {
	}
}
