package io.github.keitouya.aiimage.prompt;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Calls the primary model and, if it fails, the backup ("llm" in application.yml). */
@Component
public class Llm {

	private static final Logger log = LoggerFactory.getLogger(Llm.class);

	private final TextModel primary;
	private final TextModel backup;

	Llm(@Qualifier("primaryTextModel") TextModel primary, @Qualifier("backupTextModel") TextModel backup) {
		this.primary = primary;
		this.backup = backup;
	}

	@Retry(name = "llm", fallbackMethod = "useBackup")
	public String complete(String instructions, String input) {
		return primary.complete(instructions, input);
	}

	String useBackup(String instructions, String input, Throwable primaryFailure) {
		log.warn("Primary model failed ({}); using the backup", primaryFailure.toString());
		try {
			return backup.complete(instructions, input);
		}
		catch (RuntimeException ex) {
			throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, "The AI models are unavailable right now.", ex);
		}
	}
}
