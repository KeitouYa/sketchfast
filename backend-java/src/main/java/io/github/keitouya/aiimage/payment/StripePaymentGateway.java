package io.github.keitouya.aiimage.payment;

import com.stripe.StripeClient;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import org.springframework.stereotype.Component;

/** Stripe Checkout in payment mode: a hosted page, one line item priced from our package table. */
@Component
class StripePaymentGateway implements PaymentGateway {

	private final PaymentProperties properties;
	private final StripeClient client;

	StripePaymentGateway(PaymentProperties properties) {
		this.properties = properties;
		this.client = properties.stripeConfigured() ? new StripeClient(properties.stripeSecretKey()) : null;
	}

	@Override
	public CheckoutPage createCheckout(PaymentOrder order, String customerEmail) {
		String frontend = properties.frontendUrl().replaceAll("/+$", "");
		SessionCreateParams params = SessionCreateParams.builder()
			.setMode(SessionCreateParams.Mode.PAYMENT)
			.setClientReferenceId(order.id())
			.putMetadata("orderId", order.id())
			.setCustomerEmail(customerEmail)
			.setSuccessUrl(frontend + "/buy-credits/success?order=" + order.id())
			.setCancelUrl(frontend + "/buy-credits?canceled=1")
			.addLineItem(SessionCreateParams.LineItem.builder()
				.setQuantity(1L)
				.setPriceData(SessionCreateParams.LineItem.PriceData.builder()
					.setCurrency(order.currency())
					.setUnitAmount(order.amountCents())
					.setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
						.setName(order.credits() + " credits")
						.build())
					.build())
				.build())
			.build();
		// Same key for a retried request, so a network retry can't open two sessions for one order
		RequestOptions options = RequestOptions.builder().setIdempotencyKey("checkout:" + order.id()).build();
		try {
			Session session = stripe().v1().checkout().sessions().create(params, options);
			return new CheckoutPage(session.getId(), session.getUrl());
		}
		catch (StripeException ex) {
			throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Stripe could not create the payment page.", ex);
		}
	}

	@Override
	public Checkout retrieveCheckout(String sessionId) {
		try {
			return toCheckout(stripe().v1().checkout().sessions().retrieve(sessionId, (RequestOptions) null));
		}
		catch (StripeException ex) {
			throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Stripe could not be reached.", ex);
		}
	}

	@Override
	public Event parseWebhook(String payload, String signatureHeader) {
		if (properties.stripeWebhookSecret() == null || properties.stripeWebhookSecret().isBlank()) {
			throw new ApiException(ErrorCode.INVALID_SIGNATURE, "STRIPE_WEBHOOK_SECRET is not configured.");
		}
		// Stripe's own Event type; the unqualified name here is PaymentGateway.Event
		com.stripe.model.Event event;
		try {
			event = Webhook.constructEvent(payload, signatureHeader, properties.stripeWebhookSecret());
		}
		catch (SignatureVerificationException | RuntimeException ex) {
			throw new ApiException(ErrorCode.INVALID_SIGNATURE, "The Stripe signature did not verify.");
		}
		Checkout checkout = null;
		if (event.getType().startsWith("checkout.session.") && dataObject(event) instanceof Session session) {
			checkout = toCheckout(session);
		}
		return new Event(event.getId(), event.getType(), checkout);
	}

	/** Falls back to unsafe deserialization when the event's API version differs from the SDK's. */
	private static StripeObject dataObject(com.stripe.model.Event event) {
		var deserializer = event.getDataObjectDeserializer();
		return deserializer.getObject().orElseGet(() -> {
			try {
				return deserializer.deserializeUnsafe();
			}
			catch (EventDataObjectDeserializationException ex) {
				return null;
			}
		});
	}

	private static Checkout toCheckout(Session session) {
		return new Checkout(session.getId(), session.getClientReferenceId(), "paid".equals(session.getPaymentStatus()),
				session.getAmountTotal(), session.getCurrency());
	}

	private StripeClient stripe() {
		if (client == null) {
			throw new ApiException(ErrorCode.PAYMENT_PROVIDER_ERROR, "Payments are not configured (STRIPE_SECRET_KEY).");
		}
		return client;
	}
}
