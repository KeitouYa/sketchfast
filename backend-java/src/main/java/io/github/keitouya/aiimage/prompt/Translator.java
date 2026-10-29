package io.github.keitouya.aiimage.prompt;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * Translates image descriptions into English, which image models follow far better: FLUX drew the wrong thing for
 * the Chinese word for "motorcycle". Uses the same LLMs as Enhance, so it needs no extra provider; a dedicated
 * translation API (Google Cloud Translation, DeepL) could replace this class without touching callers.
 */
@Component
public class Translator {

	static final String INSTRUCTIONS = String.join(" ",
			"Translate the user's description of an image into natural English for a text-to-image model.",
			"Keep every detail and add nothing.", "If it is already English, return it unchanged.",
			"Output only the translation, with no quotes or preamble.");

	private final Llm llm;

	Translator(Llm llm) {
		this.llm = llm;
	}

	@Cacheable(cacheNames = "translations", key = "T(io.github.keitouya.aiimage.prompt.EnhanceService).hash('tr1', #text)")
	public String toEnglish(String text) {
		return EnhanceService.clean(llm.complete(INSTRUCTIONS, text), Integer.MAX_VALUE);
	}

	/** Letters beyond Latin Extended (CJK, Cyrillic, Arabic...) mean the text needs translating. */
	public static boolean needsTranslation(String text) {
		return text.codePoints().anyMatch(cp -> Character.isLetter(cp) && cp > 0x024F);
	}
}
