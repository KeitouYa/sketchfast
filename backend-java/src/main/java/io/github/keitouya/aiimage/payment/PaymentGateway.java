package io.github.keitouya.aiimage.payment;

/** The payment provider as the rest of the app sees it. Stripe is the implementation (ADR-002). */
public interface PaymentGateway {

	/** Creates the hosted payment page for an order. */
	CheckoutPage createCheckout(PaymentOrder order, String customerEmail);

	/** Reads the current state of a checkout, for the "back from the payment page" confirmation. */
	Checkout retrieveCheckout(String sessionId);

	/**
	 * Verifies the signature and parses a webhook delivery.
	 *
	 * @throws io.github.keitouya.aiimage.common.ApiException INVALID_SIGNATURE when the payload wasn't signed by the provider
	 */
	Event parseWebhook(String payload, String signatureHeader);

	record CheckoutPage(String sessionId, String url) {
	}

	/**
	 * @param orderId our order id, from client_reference_id
	 * @param paid true when the money was collected (Stripe payment_status "paid")
	 */
	record Checkout(String sessionId, String orderId, boolean paid, Long amountTotal, String currency) {
	}

	/** @param checkout the checkout the event is about, or null for other event types */
	record Event(String id, String type, Checkout checkout) {
	}
}
