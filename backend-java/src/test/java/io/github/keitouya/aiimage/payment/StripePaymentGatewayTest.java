package io.github.keitouya.aiimage.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import org.junit.jupiter.api.Test;

/** Webhook verification with real Stripe-style signatures (HMAC-SHA256 over "timestamp.payload"). */
class StripePaymentGatewayTest {

	private static final String SECRET = "whsec_test_secret";

	private final StripePaymentGateway gateway = new StripePaymentGateway(
			new PaymentProperties(null, SECRET, "http://localhost:3000"));

	// An older api_version than the SDK's, so parsing has to use the fallback deserializer
	private static final String COMPLETED = """
			{"id":"evt_1","object":"event","api_version":"2024-06-20","created":1700000000,
			 "type":"checkout.session.completed",
			 "data":{"object":{"id":"cs_test_1","object":"checkout.session","client_reference_id":"order-1",
			   "payment_status":"paid","status":"complete","amount_total":250,"currency":"usd"}}}""";

	@Test
	void signedCompletedEventIsParsed() throws Exception {
		PaymentGateway.Event event = gateway.parseWebhook(COMPLETED, sign(COMPLETED, now()));

		assertThat(event.id()).isEqualTo("evt_1");
		assertThat(event.type()).isEqualTo("checkout.session.completed");
		assertThat(event.checkout()).isEqualTo(new PaymentGateway.Checkout("cs_test_1", "order-1", true, 250L, "usd"));
	}

	@Test
	void tamperedPayloadIsRejected() throws Exception {
		String header = sign(COMPLETED, now());
		String tampered = COMPLETED.replace("\"amount_total\":250", "\"amount_total\":1");

		assertInvalid(() -> gateway.parseWebhook(tampered, header));
	}

	@Test
	void staleSignatureIsRejected() throws Exception {
		assertInvalid(() -> gateway.parseWebhook(COMPLETED, sign(COMPLETED, now() - 3600)));
	}

	@Test
	void missingSignatureIsRejected() {
		assertInvalid(() -> gateway.parseWebhook(COMPLETED, null));
	}

	private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INVALID_SIGNATURE));
	}

	private static long now() {
		return System.currentTimeMillis() / 1000;
	}

	static String sign(String payload, long timestamp) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
		return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
	}
}
