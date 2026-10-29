package io.github.keitouya.aiimage.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param stripeSecretKey sk_test_... or sk_live_...
 * @param stripeWebhookSecret whsec_... from the Stripe dashboard endpoint, or from {@code stripe listen} locally
 * @param frontendUrl where Stripe sends the buyer back after paying or cancelling
 */
@ConfigurationProperties("app.payments")
public record PaymentProperties(String stripeSecretKey, String stripeWebhookSecret, String frontendUrl) {

	public boolean stripeConfigured() {
		return stripeSecretKey != null && !stripeSecretKey.isBlank();
	}
}
