package dev.daclient.module;

public enum Category {
	HUD("HUD"),
	RENDER("Render"),
	COMFORT("Komfort");

	public final String label;

	Category(String label) {
		this.label = label;
	}
}
