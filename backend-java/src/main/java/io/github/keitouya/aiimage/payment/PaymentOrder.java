package io.github.keitouya.aiimage.payment;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A credit purchase. Its id is sent to Stripe as the Checkout Session's client_reference_id, and the
 * PENDING -> PAID transition is what makes crediting happen once.
 */
@Document("payment_orders")
public record PaymentOrder(@Id String id, @Indexed String userEmail, String packageId, int credits, long amountCents,
		String currency, Status status, @Indexed(unique = true, sparse = true) String stripeSessionId, Instant createdAt,
		Instant paidAt) {

	public enum Status {
		PENDING, PAID, EXPIRED
	}
}
