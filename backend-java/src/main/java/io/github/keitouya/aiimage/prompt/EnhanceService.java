package io.github.keitouya.aiimage.prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Enhance: rewrites a short idea into a detailed English image prompt. Free for the user; cached for a week by a
 * hash of the idea (never the raw text, which may be personal) and the instructions version.
 */
@Service
public class EnhanceService {

	public static final int MAX_PROMPT_LENGTH = 600;

	/**
	 * Bump when INSTRUCTIONS change, so rewrites made with the old wording aren't served from cache.
	 * Public because the cache key's SpEL expression reads it.
	 */
	public static final String INSTRUCTIONS_VERSION = "v2";

	static final String INSTRUCTIONS = String.join(" ",
			"You rewrite a user's short idea into a prompt for a text-to-image model.",
			"Keep the user's subject and intent; never add events, weather or moods they did not imply.",
			"Add concrete visual detail: the subject, setting, lighting, and composition or camera angle.",
			"Do not name an art style, medium or artist; the user picks the style separately.",
			"If the idea is not in English, translate it.",
			"Write one paragraph in English, under 60 words, with no lists, quotes, labels or preamble.",
			"Output only the prompt.");

	private final Llm llm;

	EnhanceService(Llm llm) {
		this.llm = llm;
	}

	@Cacheable(cacheNames = "enhance",
			key = "T(io.github.keitouya.aiimage.prompt.EnhanceService).hash(T(io.github.keitouya.aiimage.prompt.EnhanceService).INSTRUCTIONS_VERSION, #idea)")
	public String enhance(String idea) {
		String prompt = clean(llm.complete(INSTRUCTIONS, idea), MAX_PROMPT_LENGTH);
		if (prompt.isEmpty()) {
			throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, "The AI model returned an empty prompt. Try again.");
		}
		return prompt;
	}

	/** Models sometimes wrap the answer in quotes or prefix "Prompt:"; never return more than the image form takes. */
	static String clean(String raw, int maxLength) {
		String text = raw == null ? "" : raw.trim().replaceFirst("(?i)^prompt\\s*:\\s*", "");
		text = text.replaceAll("^[\"'`“”]+|[\"'`“”]+$", "").replaceAll("\\s+", " ").trim();
		if (text.length() > maxLength) {
			String cut = text.substring(0, maxLength);
			int lastSpace = cut.lastIndexOf(' ');
			text = (lastSpace > 0 ? cut.substring(0, lastSpace) : cut).replaceAll("[,;:\\s]+$", "");
		}
		return text;
	}

	/** Cache key: version plus a hash of the normalized text. */
	public static String hash(String version, String text) {
		String normalized = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
			return version + ":" + HexFormat.of().formatHex(digest, 0, 8);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
