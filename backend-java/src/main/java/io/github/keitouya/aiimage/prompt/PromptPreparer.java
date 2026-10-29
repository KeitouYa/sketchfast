package io.github.keitouya.aiimage.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Turns what the user typed into what the image model gets: English, with the style text first. */
@Component
public class PromptPreparer {

	private static final Logger log = LoggerFactory.getLogger(PromptPreparer.class);

	private final Translator translator;

	PromptPreparer(Translator translator) {
		this.translator = translator;
	}

	/** Runs before the user is charged. A failed translation falls back to the original text, never blocks. */
	public String forImageModel(String prompt, String styleId) {
		String english = prompt;
		if (Translator.needsTranslation(prompt)) {
			try {
				english = translator.toEnglish(prompt);
			}
			catch (RuntimeException ex) {
				log.warn("Translation failed, sending the original text: {}", ex.toString());
			}
		}
		return StylePresets.apply(english.isBlank() ? prompt : english, styleId);
	}
}
