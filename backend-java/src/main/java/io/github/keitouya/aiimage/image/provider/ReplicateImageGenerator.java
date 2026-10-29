package io.github.keitouya.aiimage.image.provider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.image.ImageProperties;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Replicate's HTTP API (it has no Java SDK). Official models are run by name, so there's no version hash to pin.
 * Calls retry on network errors, 5xx and 429 through Resilience4j ("replicate" in application.yml).
 */
@Component
public class ReplicateImageGenerator implements ImageGenerator {

	private final ImageProperties properties;
	private final RestClient http;
	private final ObjectMapper json;

	/** Timeouts come from spring.http.clients.* in application.yml, applied to the injected builder. */
	public ReplicateImageGenerator(ImageProperties properties, RestClient.Builder builder, ObjectMapper json) {
		this.properties = properties;
		this.json = json;
		this.http = builder.baseUrl("https://api.replicate.com/v1")
			.defaultHeaders(h -> h.setBearerAuth(properties.replicateApiToken() == null ? "" : properties.replicateApiToken()))
			.build();
	}

	@Override
	@Retry(name = "replicate")
	public Job submit(String prompt, String webhookUrl) {
		requireToken();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("input", Map.of("prompt", prompt, "aspect_ratio", "16:9", "output_format", "webp", "output_quality", 80));
		if (webhookUrl != null) {
			body.put("webhook", webhookUrl);
			body.put("webhook_events_filter", List.of("completed"));
		}
		// Two variables: a single {model} variable would encode the slash in "owner/name" as %2F
		String[] ownerAndName = properties.model().split("/", 2);
		Prediction created = http.post().uri("/models/{owner}/{name}/predictions", ownerAndName[0], ownerAndName[1])
			.body(body)
			.retrieve()
			.body(Prediction.class);
		return toJob(created);
	}

	@Override
	@Retry(name = "replicate")
	public Job fetch(String jobId) {
		requireToken();
		return toJob(http.get().uri("/predictions/{id}", jobId).retrieve().body(Prediction.class));
	}

	@Override
	public Job parseWebhook(String deliveryId, String timestamp, String signature, String body) {
		if (!StandardWebhooks.verify(properties.replicateWebhookSecret(), deliveryId, timestamp, body, signature,
				Instant.now())) {
			throw new ApiException(ErrorCode.INVALID_SIGNATURE, "The Replicate signature did not verify.");
		}
		return toJob(json.readValue(body, Prediction.class));
	}

	static Job toJob(Prediction p) {
		if (p == null || p.id() == null) {
			throw new IllegalStateException("Replicate returned no prediction");
		}
		return switch (p.status() == null ? "" : p.status()) {
			case "succeeded" -> new Job(p.id(), State.SUCCEEDED, firstUrl(p.output()), null);
			// FLUX's safety checker reports rejections as "NSFW content detected", sometimes for harmless prompts
			case "failed", "canceled", "aborted" -> new Job(p.id(), State.FAILED, null, String.valueOf(p.error()),
					String.valueOf(p.error()).toLowerCase(Locale.ROOT).contains("nsfw"));
			default -> new Job(p.id(), State.RUNNING, null, null);
		};
	}

	/** flux-schnell returns a list of URLs; some models return a single string. */
	private static String firstUrl(Object output) {
		if (output instanceof List<?> list && !list.isEmpty()) {
			return String.valueOf(list.get(0));
		}
		return output instanceof String s ? s : null;
	}

	private void requireToken() {
		if (properties.replicateApiToken() == null || properties.replicateApiToken().isBlank()) {
			throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, "Image generation is not configured (REPLICATE_API_TOKEN).");
		}
	}

	record Prediction(String id, String status, Object output, Object error) {
	}
}
