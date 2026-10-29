package io.github.keitouya.aiimage.prompt;

import org.springframework.stereotype.Component;

/** Turns what the user typed into what the image model gets. */
@Component
public class PromptPreparer {

	public String forImageModel(String prompt, String styleId) {
		return StylePresets.apply(prompt, styleId);
	}
}
