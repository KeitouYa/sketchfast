package io.github.keitouya.aiimage.image;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param model Replicate model, owner/name
 * @param publicBaseUrl this backend's public URL; when blank Replicate gets no webhook and polling does the work
 * @param timeout how long an image may stay unfinished before the sweep fails and refunds it
 * @param pollAfter poll Replicate only for images older than this, to leave room for the webhook
 */
@ConfigurationProperties("app.images")
public record ImageProperties(String model, String replicateApiToken, String replicateWebhookSecret,
		String publicBaseUrl, Duration timeout, Duration pollAfter, Duration pollInterval, boolean schedulerEnabled,
		Cloudinary cloudinary) {

	public record Cloudinary(String cloudName, String apiKey, String apiSecret, String folder) {

		public boolean configured() {
			return cloudName != null && !cloudName.isBlank() && apiKey != null && !apiKey.isBlank();
		}
	}

	public String webhookUrl() {
		if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
			return null;
		}
		return publicBaseUrl.replaceAll("/+$", "") + "/api/webhooks/replicate";
	}
}
