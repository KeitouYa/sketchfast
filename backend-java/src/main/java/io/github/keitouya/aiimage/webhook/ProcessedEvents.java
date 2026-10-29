package io.github.keitouya.aiimage.webhook;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Service;

/**
 * Webhook event ids already handled, so a redelivered event is skipped (Stripe and Replicate both retry).
 *
 * <p>Handlers record an event only after processing it. If processing fails the event stays unrecorded and the
 * provider's retry runs it again; the handlers themselves are idempotent, so this is a shortcut, not the guard.
 */
@Service
public class ProcessedEvents {

	private final MongoTemplate mongo;

	ProcessedEvents(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	public boolean alreadyProcessed(String eventId) {
		return mongo.exists(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(eventId)), ProcessedEvent.class);
	}

	public void markProcessed(String eventId, String source) {
		try {
			mongo.insert(new ProcessedEvent(eventId, source, Instant.now()));
		}
		catch (DuplicateKeyException concurrentDelivery) {
			// the same event was handled in parallel; both runs were idempotent
		}
	}

	/** Kept 30 days, longer than any provider retries a delivery. */
	@Document("processed_events")
	record ProcessedEvent(@Id String id, String source, @Indexed(expireAfter = "30d") Instant receivedAt) {
	}
}
