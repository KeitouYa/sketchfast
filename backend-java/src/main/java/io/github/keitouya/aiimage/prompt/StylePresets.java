package io.github.keitouya.aiimage.prompt;

import java.util.List;
import java.util.Optional;

/**
 * Style presets. The client sends only an id; the server adds the style text, so a style always renders the same.
 * The style text goes first: image models weigh the start of a prompt most.
 */
public final class StylePresets {

	public record Style(String id, String label, String text) {
	}

	public static final String NONE = "none";

	private static final List<Style> ALL = List.of(new Style(NONE, "None", ""),
			new Style("photo", "Photo", "realistic photograph, natural lighting, sharp focus, 35mm lens, high detail"),
			new Style("anime", "Anime",
					"anime key visual, clean line art, cel shading, vibrant colors, detailed background"),
			new Style("oil", "Oil painting",
					"oil painting on canvas, visible brushstrokes, rich colors, classical composition"),
			new Style("watercolor", "Watercolor",
					"watercolor painting, soft washes of color, visible paper texture, gentle edges"),
			new Style("3d", "3D", "Disney Pixar style 3D render, soft studio lighting, smooth stylized materials"),
			new Style("comic", "Comic",
					"comic book illustration, bold ink outlines, halftone shading, flat vivid colors, dynamic composition"));

	private StylePresets() {
	}

	public static List<Style> all() {
		return ALL;
	}

	public static Optional<Style> find(String id) {
		return ALL.stream().filter(s -> s.id().equals(id)).findFirst();
	}

	/** Puts the style text in front of the user's description. */
	public static String apply(String prompt, String styleId) {
		String text = find(styleId).map(Style::text).orElse("");
		return text.isEmpty() ? prompt : text + ", " + prompt;
	}
}
