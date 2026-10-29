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

	/*
	 * Comic styles that look clearly different from each other. "no text" and the like are there because the model
	 * otherwise adds speech bubbles and signs with garbled lettering. FLUX has no negative prompt, so naming an
	 * unwanted thing can bring it in: "Chinese ... rice paper" plus "no seal stamps" still drew seal stamps.
	 */
	private static final List<Style> ALL = List.of(new Style(NONE, "None", ""),
			new Style("manga", "Manga",
					"black and white manga panel, screentone shading, speed lines, crisp ink line art, high contrast, "
							+ "no text, no speech bubbles"),
			new Style("american-comic", "American comic",
					"American comic book art, bold black ink outlines, visible halftone dot shading, flat saturated "
							+ "colors, dramatic composition, no text, no speech bubbles"),
			new Style("webtoon", "Webtoon",
					"Korean webtoon illustration, clean digital line art, soft gradient shading, glossy highlights, "
							+ "pastel colors, polished modern look, no text"),
			new Style("anime-film", "Anime film",
					"hand-drawn 2D anime film still, soft painterly sky with big fluffy clouds, lush greenery, warm "
							+ "gentle colors, painted backgrounds, nostalgic mood, no text"),
			new Style("chibi", "Chibi",
					"chibi illustration, super-deformed characters with big heads and small bodies, round cute shapes, "
							+ "bright cheerful colors, simple background, no text"),
			new Style("pop-art", "Pop art",
					"pop art comic panel, large Ben-Day dots, bold black outlines, primary red yellow and blue colors, "
							+ "retro 1960s print look, no text, no lettering, no signs, no speech bubbles"),
			new Style("ink-wash", "Ink wash",
					"ink wash comic illustration, expressive black brush strokes, soft grey washes, light watercolor "
							+ "tints, generous empty sky, clean plain margins, no text"),
			new Style("picture-book", "Picture book",
					"children's picture book illustration, crayon and watercolor texture, soft simple shapes, warm cozy "
							+ "colors, no text"));

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
