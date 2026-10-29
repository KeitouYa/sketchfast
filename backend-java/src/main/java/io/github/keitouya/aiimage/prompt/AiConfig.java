package io.github.keitouya.aiimage.prompt;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gemini answers first and OpenAI is the backup, the same order as the TypeScript version. Spring AI provides
 * both clients; each becomes a {@link TextModel} so the rest of the app doesn't depend on Spring AI's types.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class AiConfig implements CachingConfigurer {

	@Bean
	TextModel primaryTextModel(GoogleGenAiChatModel gemini) {
		return textModel(gemini);
	}

	@Bean
	TextModel backupTextModel(OpenAiChatModel openAi) {
		return textModel(openAi);
	}

	/** A cache outage is logged and skipped: the LLM is called instead of failing the request. */
	@Override
	public CacheErrorHandler errorHandler() {
		return new LoggingCacheErrorHandler();
	}

	private static TextModel textModel(ChatModel model) {
		ChatClient client = ChatClient.create(model);
		return (instructions, input) -> client.prompt().system(instructions).user(input).call().content();
	}
}
