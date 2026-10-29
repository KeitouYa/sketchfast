package io.github.keitouya.aiimage.payment;

import java.util.Map;

import io.github.keitouya.aiimage.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentController {

	private final PaymentService payments;

	PaymentController(PaymentService payments) {
		this.payments = payments;
	}

	@PostMapping("/api/payments/checkout")
	@ResponseStatus(HttpStatus.CREATED)
	PaymentService.StartedCheckout checkout(CurrentUser user, @Valid @RequestBody CheckoutRequest request) {
		return payments.startCheckout(user, request.packageId());
	}

	@PostMapping("/api/payments/{orderId}/confirm")
	OrderStatus confirm(CurrentUser user, @PathVariable String orderId) {
		PaymentOrder order = payments.confirm(user, orderId);
		return new OrderStatus(order.id(), order.status(), order.credits());
	}

	/** Raw body: the signature is computed over the exact bytes Stripe sent. */
	@PostMapping("/api/webhooks/stripe")
	Map<String, Boolean> stripeWebhook(@RequestBody String payload,
			@RequestHeader(name = "Stripe-Signature", required = false) String signature) {
		payments.handleWebhook(payload, signature);
		return Map.of("received", true);
	}

	record CheckoutRequest(@NotBlank String packageId) {
	}

	record OrderStatus(String orderId, PaymentOrder.Status status, int credits) {
	}
}
