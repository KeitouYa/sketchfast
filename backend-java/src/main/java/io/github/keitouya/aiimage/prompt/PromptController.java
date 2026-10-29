package io.github.keitouya.aiimage.prompt;

import java.util.List;

import io.github.keitouya.aiimage.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PromptController {

	private final EnhanceService enhance;

	PromptController(EnhanceService enhance) {
		this.enhance = enhance;
	}

	@GetMapping("/api/styles")
	List<StyleView> styles() {
		return StylePresets.all().stream().map(s -> new StyleView(s.id(), s.label())).toList();
	}

	/** Signed-in users only, so the LLM quota can't be drained anonymously. */
	@PostMapping("/api/prompts/enhance")
	Enhanced enhance(CurrentUser user, @Valid @RequestBody EnhanceRequest request) {
		return new Enhanced(enhance.enhance(request.idea().trim()));
	}

	record EnhanceRequest(@NotBlank @Size(max = 500) String idea) {
	}

	record Enhanced(String prompt) {
	}

	record StyleView(String id, String label) {
	}
}
