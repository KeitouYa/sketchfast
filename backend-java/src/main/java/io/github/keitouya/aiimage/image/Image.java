package io.github.keitouya.aiimage.image;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One generation request, and once it succeeds, a gallery image. Created PENDING when the credit is charged;
 * every status change is a conditional update on this one document, so concurrent webhook, poll and sweep
 * runs can't both win (ADR-001 R3).
 *
 * @param prompt the user's own words, shown in the gallery; the model gets the styled (and translated) version
 * @param providerJobId the Replicate prediction id; webhooks find the image by it
 * @param deadlineAt still unfinished after this, the sweep fails it and refunds
 * @param checkedAt when an owner's status poll last asked the provider; throttles those checks across instances
 */
@Document("images")
@CompoundIndexes({ @CompoundIndex(name = "gallery", def = "{'userEmail': 1, 'status': 1, 'createdAt': -1}"),
		@CompoundIndex(name = "unfinished_by_deadline", def = "{'status': 1, 'deadlineAt': 1}") })
public record Image(@Id String id, String userEmail, String userName, String prompt, String style, Status status,
		String failureCode, int cost, @Indexed(unique = true, sparse = true) String providerJobId, String url,
		Instant deadlineAt, Instant createdAt, Instant updatedAt, Instant checkedAt) {

	public enum Status {
		/** Charged and sent to the model. */
		PENDING,
		/** The model finished; one worker has claimed the upload. */
		SAVING,
		SUCCEEDED,
		/** Refunded in the same transaction that set this status. */
		FAILED;

		public boolean finished() {
			return this == SUCCEEDED || this == FAILED;
		}
	}
}
