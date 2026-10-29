package io.github.keitouya.aiimage.payment;

import static io.github.keitouya.aiimage.Concurrency.concurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jayway.jsonpath.JsonPath;
import io.github.keitouya.aiimage.TestcontainersConfiguration;
import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.credit.CreditService;
import io.github.keitouya.aiimage.credit.LedgerEntry;
import io.github.keitouya.aiimage.payment.PaymentOrder.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The purchase flow (ADR-002) with a fake gateway in place of Stripe: an order is credited exactly once whichever
 * of the webhook and the success-page confirmation arrives first, or both at once, or the webhook twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, PaymentFlowTest.FakeGatewayConfig.class })
class PaymentFlowTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	FakeGateway gateway;

	@Autowired
	PaymentService payments;

	@Autowired
	CreditService credits;

	@Autowired
	MongoTemplate mongo;

	private final String email = "p-" + UUID.randomUUID() + "@test.dev";

	private final RequestPostProcessor user = jwt().jwt(j -> j.claim("email", email));

	@Test
	void checkoutPricesThePackageOnTheServer() throws Exception {
		String orderId = checkout("credits_30");

		PaymentOrder order = order(orderId);
		assertThat(order.status()).isEqualTo(Status.PENDING);
		assertThat(order.amountCents()).isEqualTo(250);
		assertThat(order.credits()).isEqualTo(30);
		assertThat(order.stripeSessionId()).isEqualTo("cs_" + orderId);
	}

	@Test
	void unknownPackageIsRejected() throws Exception {
		mvc.perform(post("/api/payments/checkout").with(user)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"packageId\":\"credits_999\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void confirmAfterPaymentCreditsTheOrderOnce() throws Exception {
		String orderId = checkout("credits_30");
		gateway.pay(orderId, 250);

		confirm(orderId).andExpect(jsonPath("$.status").value("PAID"));
		confirm(orderId).andExpect(jsonPath("$.status").value("PAID"));

		assertThat(credits.balance(email)).isEqualTo(50);
		assertThat(purchases()).isEqualTo(1);
	}

	@Test
	void unpaidCheckoutStaysPending() throws Exception {
		String orderId = checkout("credits_10");

		confirm(orderId).andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void webhookAndConfirmationsRacingCreditOnce() throws Exception {
		String orderId = checkout("credits_60");
		gateway.pay(orderId, 400);

		concurrently(20, i -> {
			if (i % 2 == 0) {
				payments.handleWebhook(gateway.event("evt-" + i, "checkout.session.completed", orderId), "valid");
			}
			else {
				payments.confirm(new io.github.keitouya.aiimage.security.CurrentUser(email, "P"), orderId);
			}
			return null;
		});

		assertThat(credits.balance(email)).isEqualTo(80);
		assertThat(purchases()).isEqualTo(1);
		assertThat(order(orderId).status()).isEqualTo(Status.PAID);
	}

	@Test
	void redeliveredWebhookIsAppliedOnce() throws Exception {
		String orderId = checkout("credits_10");
		gateway.pay(orderId, 100);
		String payload = gateway.event("evt-same-" + orderId, "checkout.session.completed", orderId);

		webhook(payload, "valid").andExpect(status().isOk());
		webhook(payload, "valid").andExpect(status().isOk());

		assertThat(credits.balance(email)).isEqualTo(30);
		assertThat(purchases()).isEqualTo(1);
	}

	@Test
	void amountThatDoesNotMatchThePriceIsNotCredited() throws Exception {
		String orderId = checkout("credits_60");
		gateway.pay(orderId, 1);

		confirm(orderId).andExpect(jsonPath("$.status").value("PENDING"));
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void badSignatureIsRejectedAndNothingIsCredited() throws Exception {
		String orderId = checkout("credits_10");
		gateway.pay(orderId, 100);

		webhook(gateway.event("evt-x", "checkout.session.completed", orderId), "forged")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void expiredCheckoutMarksTheOrderExpired() throws Exception {
		String orderId = checkout("credits_10");

		webhook(gateway.event("evt-exp-" + orderId, "checkout.session.expired", orderId), "valid");

		assertThat(order(orderId).status()).isEqualTo(Status.EXPIRED);
	}

	@Test
	void someoneElsesOrderIsNotFound() throws Exception {
		String orderId = checkout("credits_10");

		mvc.perform(post("/api/payments/" + orderId + "/confirm").with(jwt().jwt(j -> j.claim("email", "other@test.dev"))))
			.andExpect(status().isNotFound());
	}

	private String checkout(String packageId) throws Exception {
		String body = mvc.perform(post("/api/payments/checkout").with(user)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"packageId\":\"" + packageId + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.checkoutUrl").value(org.hamcrest.Matchers.startsWith("https://checkout.test/")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.orderId");
	}

	private org.springframework.test.web.servlet.ResultActions confirm(String orderId) throws Exception {
		return mvc.perform(post("/api/payments/" + orderId + "/confirm").with(user)).andExpect(status().isOk());
	}

	private org.springframework.test.web.servlet.ResultActions webhook(String payload, String signature)
			throws Exception {
		return mvc.perform(post("/api/webhooks/stripe").header("Stripe-Signature", signature)
			.contentType(MediaType.APPLICATION_JSON)
			.content(payload));
	}

	private PaymentOrder order(String id) {
		return mongo.findOne(query(where("_id").is(id)), PaymentOrder.class);
	}

	private long purchases() {
		return mongo.count(query(where("userEmail").is(email).and("type").is(LedgerEntry.Type.PURCHASE)),
				LedgerEntry.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeGatewayConfig {

		@Bean
		@Primary
		FakeGateway fakeGateway() {
			return new FakeGateway();
		}
	}

	/** Stands in for Stripe. Webhook payloads are "eventId|type|orderId"; the signature "valid" verifies. */
	static class FakeGateway implements PaymentGateway {

		private final Map<String, Checkout> sessions = new ConcurrentHashMap<>();

		@Override
		public CheckoutPage createCheckout(PaymentOrder order, String customerEmail) {
			String sessionId = "cs_" + order.id();
			sessions.put(sessionId, new Checkout(sessionId, order.id(), false, order.amountCents(), order.currency()));
			return new CheckoutPage(sessionId, "https://checkout.test/" + sessionId);
		}

		@Override
		public Checkout retrieveCheckout(String sessionId) {
			return sessions.get(sessionId);
		}

		@Override
		public Event parseWebhook(String payload, String signatureHeader) {
			if (!"valid".equals(signatureHeader)) {
				throw new ApiException(ErrorCode.INVALID_SIGNATURE, "bad signature");
			}
			String[] parts = payload.split("\\|");
			return new Event(parts[0], parts[1], sessions.get("cs_" + parts[2]));
		}

		void pay(String orderId, long amountCents) {
			sessions.computeIfPresent("cs_" + orderId,
					(id, c) -> new Checkout(id, c.orderId(), true, amountCents, c.currency()));
		}

		String event(String eventId, String type, String orderId) {
			return eventId + "|" + type + "|" + orderId;
		}
	}
}
