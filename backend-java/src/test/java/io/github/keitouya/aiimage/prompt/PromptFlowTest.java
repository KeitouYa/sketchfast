package io.github.keitouya.aiimage.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.keitouya.aiimage.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Enhance, translation and the Gemini -> OpenAI fallback, with both models mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PromptFlowTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	PromptPreparer preparer;

	@MockitoBean(name = "primaryTextModel")
	TextModel primary;

	@MockitoBean(name = "backupTextModel")
	TextModel backup;

	@BeforeEach
	void resetModels() {
		reset(primary, backup);
	}

	@Test
	void stylesArePublic() throws Exception {
		mvc.perform(get("/api/styles"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(9))
			.andExpect(jsonPath("$[0].id").value("none"))
			.andExpect(jsonPath("$[0].text").doesNotExist());
	}

	@Test
	void enhanceUsesThePrimaryModelAndCleansTheAnswer() throws Exception {
		when(primary.complete(eq(EnhanceService.INSTRUCTIONS), eq("a cat on a roof")))
			.thenReturn("Prompt: \"A tabby cat sitting on a red tiled roof at sunset\"");

		enhance("{\"idea\":\"  a cat on a roof \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.prompt").value("A tabby cat sitting on a red tiled roof at sunset"));
		verify(backup, never()).complete(anyString(), anyString());
	}

	@Test
	void primaryFailureFallsBackToTheBackupModel() throws Exception {
		when(primary.complete(anyString(), anyString())).thenThrow(new IllegalStateException("429 quota"));
		when(backup.complete(anyString(), anyString())).thenReturn("A tabby cat on a roof");

		enhance("{\"idea\":\"a cat\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.prompt").value("A tabby cat on a roof"));
	}

	@Test
	void bothModelsDownIsServiceUnavailable() throws Exception {
		when(primary.complete(anyString(), anyString())).thenThrow(new IllegalStateException("down"));
		when(backup.complete(anyString(), anyString())).thenThrow(new IllegalStateException("down too"));

		enhance("{\"idea\":\"a cat\"}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
	}

	@Test
	void enhanceValidatesTheIdeaAndNeedsSignIn() throws Exception {
		enhance("{\"idea\":\"  \"}").andExpect(status().isBadRequest());
		enhance("{\"idea\":\"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
		mvc.perform(post("/api/prompts/enhance").contentType(MediaType.APPLICATION_JSON).content("{\"idea\":\"a cat\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void nonEnglishPromptIsTranslatedBeforeTheStyleIsAdded() {
		when(primary.complete(eq(Translator.INSTRUCTIONS), eq("一辆红色的摩托车"))).thenReturn("A red motorcycle");

		assertThat(preparer.forImageModel("一辆红色的摩托车", "manga")).isEqualTo(
				"black and white manga panel, screentone shading, speed lines, crisp ink line art, high contrast, "
						+ "no text, no speech bubbles, A red motorcycle");
	}

	@Test
	void englishPromptSkipsTheModel() {
		assertThat(preparer.forImageModel("a café on a rainy street", "none")).isEqualTo("a café on a rainy street");
		verify(primary, never()).complete(anyString(), anyString());
	}

	@Test
	void failedTranslationKeepsTheOriginalText() {
		when(primary.complete(anyString(), anyString())).thenThrow(new IllegalStateException("down"));
		when(backup.complete(anyString(), anyString())).thenThrow(new IllegalStateException("down too"));

		assertThat(preparer.forImageModel("一辆红色的摩托车", "none")).isEqualTo("一辆红色的摩托车");
	}

	@Test
	void languageDetectionLooksBeyondLatinLetters() {
		assertThat(Translator.needsTranslation("摩托车")).isTrue();
		assertThat(Translator.needsTranslation("кошка на крыше")).isTrue();
		assertThat(Translator.needsTranslation("crème brûlée, 3 cats")).isFalse();
	}

	@Test
	void longAnswersAreCutAtAWordBoundary() {
		String cleaned = EnhanceService.clean("word, ".repeat(200), EnhanceService.MAX_PROMPT_LENGTH);
		assertThat(cleaned.length()).isLessThanOrEqualTo(600);
		assertThat(cleaned).endsWith("word");
	}

	private ResultActions enhance(String json) throws Exception {
		return mvc.perform(post("/api/prompts/enhance").with(jwt().jwt(j -> j.claim("email", "e@test.dev")))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}
}
