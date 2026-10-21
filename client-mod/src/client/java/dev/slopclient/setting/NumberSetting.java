package dev.slopclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.function.DoubleFunction;

/** Slider between `min` and `max`, snapped to `step`. */
public final class NumberSetting extends Setting<Double> {
	public final double min;
	public final double max;
	public final double step;
	private final DoubleFunction<String> format;

	public NumberSetting(String name, double value, double min, double max, double step, DoubleFunction<String> format) {
		super(name, value);
		this.min = min;
		this.max = max;
		this.step = step;
		this.format = format;
	}

	@Override
	public void set(Double v) {
		double snapped = Math.round((v - min) / step) * step + min;
		value = Math.max(min, Math.min(max, snapped));
	}

	/** Position on the slider, 0..1. */
	public double fraction() {
		return (value - min) / (max - min);
	}

	public void setFraction(double f) {
		set(min + Math.max(0, Math.min(1, f)) * (max - min));
	}

	@Override
	public String display() {
		return format.apply(value);
	}

	@Override
	public JsonElement save() {
		return new JsonPrimitive(value);
	}

	@Override
	public void load(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
	}
}
