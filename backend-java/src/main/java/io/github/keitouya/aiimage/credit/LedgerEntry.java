package io.github.keitouya.aiimage.credit;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Append-only record of every balance change (ADR-001 R5). The balance always equals the sum of deltas.
 *
 * @param idempotencyKey unique, so the same business event (one refund of one image) can only be recorded once
 * @param balanceAfter the account balance right after this change, for support and reconciliation
 */
@Document("credit_ledger")
@CompoundIndex(name = "user_newest_first", def = "{'userEmail': 1, 'createdAt': -1}")
public record LedgerEntry(@Id String id, String userEmail, int delta, Type type, String refType, String refId,
		@Indexed(unique = true) String idempotencyKey, int balanceAfter, Instant createdAt) {

	public enum Type {
		SIGNUP_BONUS, PURCHASE, CHARGE, REFUND
	}

	static LedgerEntry of(String userEmail, int delta, Type type, String refType, String refId, String key,
			int balanceAfter) {
		return new LedgerEntry(null, userEmail, delta, type, refType, refId, key, balanceAfter, Instant.now());
	}
}
