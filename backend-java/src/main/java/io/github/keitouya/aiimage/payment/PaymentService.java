package io.github.keitouya.aiimage.payment;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Instant;
import java.util.Objects;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.MongoTx;
import io.github.keitouya.aiimage.credit.CreditProperties;
import io.github.keitouya.aiimage.credit.CreditProperties.CreditPackage;
import io.github.keitouya.aiimage.credit.CreditService;
import io.github.keitouya.aiimage.payment.PaymentGateway.Checkout;
import io.github.keitouya.aiimage.payment.PaymentOrder.Status;
import io.github.keitouya.aiimage.security.CurrentUser;
import io.github.keitouya.aiimage.webhook.ProcessedEvents;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Credit purchases (ADR-002). Two paths credit an order, the Stripe webhook and the buyer's return to our
 * success page, and both call {@link #fulfill}. It may run several times at once for one order; the
 * PENDING -> PAID conditional update lets exactly one of them add the credits.
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final MongoTemplate mongo;
	private final MongoTx tx;
	private final CreditService credits;
	private final CreditProperties creditProperties;
	private final PaymentGateway gateway;
	private final ProcessedEvents processedEvents;

	PaymentService(MongoTemplate mongo, MongoTx tx, CreditService credits, CreditProperties creditProperties,
			PaymentGateway gateway, ProcessedEvents processedEvents) {
		this.mongo = mongo;
		this.tx = tx;
		this.credits = credits;
		this.creditProperties = creditProperties;
		this.gateway = gateway;
		this.processedEvents = processedEvents;
	}

	/** Prices the package on the server, records the order, and opens Stripe's payment page. */
	public StartedCheckout startCheckout(CurrentUser user, String packageId) {
		CreditPackage pkg = creditProperties.findPackage(packageId)
			.orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown credit package " + packageId + "."));
		credits.ensureAccount(user.email());
		PaymentOrder order = mongo.insert(new PaymentOrder(new ObjectId().toHexString(), user.email(), pkg.id(),
				pkg.credits(), pkg.priceCents(), pkg.currency(), Status.PENDING, null, Instant.now(), null));
		PaymentGateway.CheckoutPage page;
		try {
			page = gateway.createCheckout(order, user.email());
		}
		catch (ApiException ex) {
			setStatus(order.id(), Status.PENDING, Status.EXPIRED);
			throw ex;
		}
		mongo.updateFirst(query(where("_id").is(order.id())), Update.update("stripeSessionId", page.sessionId()),
				PaymentOrder.class);
		return new StartedCheckout(order.id(), page.url());
	}

	/** The buyer is back from Stripe: credit now if the webhook hasn't yet, so they see their credits at once. */
	public PaymentOrder confirm(CurrentUser user, String orderId) {
		PaymentOrder order = mongo.findOne(query(where("_id").is(orderId).and("userEmail").is(user.email())),
				PaymentOrder.class);
		if (order == null) {
			throw new ApiException(ErrorCode.NOT_FOUND, "No such order.");
		}
		if (order.status() == Status.PENDING && order.stripeSessionId() != null) {
			return fulfill(gateway.retrieveCheckout(order.stripeSessionId()));
		}
		return order;
	}

	/** Verifies, de-duplicates and applies a Stripe webhook delivery. */
	public void handleWebhook(String payload, String signatureHeader) {
		PaymentGateway.Event event = gateway.parseWebhook(payload, signatureHeader);
		if (processedEvents.alreadyProcessed(event.id())) {
			return;
		}
		switch (event.type()) {
			case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> fulfill(event.checkout());
			case "checkout.session.expired" -> expire(event.checkout());
			default -> log.debug("Ignoring Stripe event {}", event.type());
		}
		processedEvents.markProcessed(event.id(), "stripe");
	}

	/**
	 * Credits the order if Stripe says it is paid and the amount is what we priced. Safe to call any number of
	 * times, concurrently: only the call that moves PENDING to PAID adds credits, in the same transaction.
	 */
	PaymentOrder fulfill(Checkout checkout) {
		if (checkout == null || checkout.orderId() == null) {
			log.warn("Stripe checkout without our order id; ignored");
			return null;
		}
		PaymentOrder order = mongo.findOne(query(where("_id").is(checkout.orderId())), PaymentOrder.class);
		if (order == null) {
			log.warn("Stripe checkout {} names unknown order {}", checkout.sessionId(), checkout.orderId());
			return null;
		}
		if (!checkout.paid()) {
			return order;
		}
		if (!Objects.equals(checkout.amountTotal(), order.amountCents())
				|| !order.currency().equalsIgnoreCase(String.valueOf(checkout.currency()))) {
			log.error("Amount mismatch on order {}: Stripe {} {}, expected {} {}; not credited", order.id(),
					checkout.amountTotal(), checkout.currency(), order.amountCents(), order.currency());
			return order;
		}
		return tx.run(() -> {
			PaymentOrder paid = mongo.findAndModify(query(where("_id").is(order.id()).and("status").is(Status.PENDING)),
					new Update().set("status", Status.PAID).set("paidAt", Instant.now()),
					FindAndModifyOptions.options().returnNew(true), PaymentOrder.class);
			if (paid == null) {
				return mongo.findOne(query(where("_id").is(order.id())), PaymentOrder.class);
			}
			credits.addPurchaseInTransaction(paid.userEmail(), paid.credits(), paid.id());
			log.info("Order {} paid: +{} credits", paid.id(), paid.credits());
			return paid;
		});
	}

	private void expire(Checkout checkout) {
		if (checkout != null && checkout.orderId() != null) {
			setStatus(checkout.orderId(), Status.PENDING, Status.EXPIRED);
		}
	}

	private void setStatus(String orderId, Status from, Status to) {
		mongo.updateFirst(query(where("_id").is(orderId).and("status").is(from)), Update.update("status", to),
				PaymentOrder.class);
	}

	public record StartedCheckout(String orderId, String checkoutUrl) {
	}
}
