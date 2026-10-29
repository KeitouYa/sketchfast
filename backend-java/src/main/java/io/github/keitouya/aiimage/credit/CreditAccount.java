package io.github.keitouya.aiimage.credit;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One per user. The balance only changes through {@link CreditService}, always with a ledger entry. */
@Document("credit_accounts")
public record CreditAccount(@Id String id, @Indexed(unique = true) String userEmail, int balance, Instant createdAt,
		Instant updatedAt) {

	static CreditAccount open(String userEmail, int balance) {
		Instant now = Instant.now();
		return new CreditAccount(null, userEmail, balance, now, now);
	}
}
