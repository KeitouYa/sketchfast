package io.github.keitouya.aiimage.image.provider;

import java.time.Instant;
import java.util.Base64;

/** Signs test deliveries the way Replicate does (Standard Webhooks). */
public final class WebhookSigner {

	/** Matches app.images.replicate-webhook-secret in src/test/resources/config/application.yml. */
	public static final String TEST_SECRET = "whsec_dGVzdC1yZXBsaWNhdGUtc2VjcmV0";

	private WebhookSigner() {
	}

	public static String timestampNow() {
		return String.valueOf(Instant.now().getEpochSecond());
	}

	public static String sign(String secret, String id, String timestamp, String body) {
		return "v1," + Base64.getEncoder().encodeToString(StandardWebhooks.sign(secret, id + "." + timestamp + "." + body));
	}
}
