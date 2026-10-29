package io.github.keitouya.aiimage.image;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.MongoTx;
import io.github.keitouya.aiimage.credit.CreditProperties;
import io.github.keitouya.aiimage.credit.CreditService;
import io.github.keitouya.aiimage.image.Image.Status;
import io.github.keitouya.aiimage.image.provider.ImageGenerator;
import io.github.keitouya.aiimage.image.provider.ImageGenerator.Job;
import io.github.keitouya.aiimage.image.provider.ImageStorage;
import io.github.keitouya.aiimage.prompt.PromptPreparer;
import io.github.keitouya.aiimage.prompt.StylePresets;
import io.github.keitouya.aiimage.security.CurrentUser;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Asynchronous image generation with the billing rules of ADR-001.
 *
 * <pre>
 * submit: charge + PENDING record (one transaction) -> start the Replicate prediction -> 202
 * PENDING --result, claimed--> SAVING --stored--> SUCCEEDED
 * PENDING or SAVING --model failed / upload failed / past deadline--> FAILED + refund (one transaction)
 * </pre>
 *
 * Results arrive by webhook, by polling, or not at all (the deadline sweep). All three paths go through the
 * same conditional updates, so whichever runs first wins and the others change nothing.
 */
@Service
public class ImageService {

	private static final Logger log = LoggerFactory.getLogger(ImageService.class);

	static final String REF_TYPE = "IMAGE";

	private final MongoTemplate mongo;
	private final MongoTx tx;
	private final CreditService credits;
	private final CreditProperties creditProperties;
	private final ImageProperties properties;
	private final ImageGenerator generator;
	private final ImageStorage storage;
	private final PromptPreparer prompts;

	ImageService(MongoTemplate mongo, MongoTx tx, CreditService credits, CreditProperties creditProperties,
			ImageProperties properties, ImageGenerator generator, ImageStorage storage, PromptPreparer prompts) {
		this.mongo = mongo;
		this.tx = tx;
		this.credits = credits;
		this.creditProperties = creditProperties;
		this.properties = properties;
		this.generator = generator;
		this.storage = storage;
		this.prompts = prompts;
	}

	/** Validates before charging (ADR-001 R2), charges, and starts the generation. Returns at once. */
	public Submitted submit(CurrentUser user, String rawPrompt, String rawStyle) {
		String prompt = rawPrompt == null ? "" : rawPrompt.trim();
		String style = rawStyle == null || rawStyle.isBlank() ? StylePresets.NONE : rawStyle;
		if (prompt.length() < 3 || prompt.length() > 600) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Describe the image in 3 to 600 characters.");
		}
		if (StylePresets.find(style).isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown style " + style + ".");
		}
		String modelPrompt = prompts.forImageModel(prompt, style);
		credits.ensureAccount(user.email());

		String id = new ObjectId().toHexString();
		int cost = creditProperties.imageCost();
		Instant now = Instant.now();
		int balance = tx.run(() -> {
			int after = credits.chargeInTransaction(user.email(), cost, REF_TYPE, id);
			mongo.insert(new Image(id, user.email(), user.name(), prompt, style, Status.PENDING, null, cost, null, null,
					now.plus(properties.timeout()), now, now));
			return after;
		});

		Job job;
		try {
			job = generator.submit(modelPrompt, properties.webhookUrl());
		}
		catch (RuntimeException ex) {
			log.warn("Could not start generation for image {}: {}", id, ex.toString());
			fail(id, "PROVIDER_FAILED");
			throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE,
					"Image generation is unavailable right now. Your credit has been refunded.", ex);
		}
		mongo.updateFirst(query(where("_id").is(id).and("status").is(Status.PENDING)),
				new Update().set("providerJobId", job.id()).set("updatedAt", Instant.now()), Image.class);
		if (job.state() != ImageGenerator.State.RUNNING) {
			onJobUpdate(job);
		}
		return new Submitted(id, Status.PENDING, balance);
	}

	/** Applies a result from the webhook or the poller. Safe to call repeatedly and concurrently for one job. */
	public void onJobUpdate(Job job) {
		Image image = mongo.findOne(query(where("providerJobId").is(job.id())), Image.class);
		if (image == null || image.status().finished()) {
			return;
		}
		switch (job.state()) {
			case RUNNING -> {
			}
			case FAILED -> {
				log.info("Generation failed for image {}: {}", image.id(), job.error());
				fail(image.id(), "PROVIDER_FAILED");
			}
			case SUCCEEDED -> save(image.id(), job.outputUrl());
		}
	}

	/**
	 * Fails an unfinished image and refunds it in the same transaction. Only the call whose conditional update
	 * matches refunds; any later call finds the image already FAILED and does nothing.
	 *
	 * @return whether this call failed (and refunded) the image
	 */
	public boolean fail(String imageId, String failureCode) {
		return tx.run(() -> {
			Image failed = mongo.findAndModify(
					query(where("_id").is(imageId).and("status").in(Status.PENDING, Status.SAVING)),
					new Update().set("status", Status.FAILED).set("failureCode", failureCode).set("updatedAt", Instant.now()),
					FindAndModifyOptions.options().returnNew(true), Image.class);
			if (failed == null) {
				return false;
			}
			credits.refundInTransaction(failed.userEmail(), failed.cost(), REF_TYPE, failed.id());
			return true;
		});
	}

	/** Asks Replicate about images the webhook hasn't settled yet. Covers local dev, where no webhook can arrive. */
	public int pollRunning() {
		Query running = query(where("status").is(Status.PENDING)
			.and("providerJobId").ne(null)
			.and("createdAt").lt(Instant.now().minus(properties.pollAfter()))).limit(50);
		int polled = 0;
		for (Image image : mongo.find(running, Image.class)) {
			try {
				onJobUpdate(generator.fetch(image.providerJobId()));
				polled++;
			}
			catch (RuntimeException ex) {
				log.warn("Polling image {} failed: {}", image.id(), ex.toString());
			}
		}
		return polled;
	}

	/** Fails and refunds images past their deadline, including ones whose worker crashed mid-way. */
	public int sweepExpired() {
		Query expired = query(where("status").in(Status.PENDING, Status.SAVING).and("deadlineAt").lt(Instant.now()))
			.limit(100);
		int swept = 0;
		for (Image image : mongo.find(expired, Image.class)) {
			if (fail(image.id(), "TIMEOUT")) {
				log.info("Image {} timed out in {} and was refunded", image.id(), image.status());
				swept++;
			}
		}
		return swept;
	}

	/** The owner sees any state; everyone else only finished images, which is what a share link shows. */
	public Optional<Image> findVisible(String imageId, Optional<CurrentUser> viewer) {
		if (!ObjectId.isValid(imageId)) {
			return Optional.empty();
		}
		Image image = mongo.findOne(query(where("_id").is(imageId)), Image.class);
		if (image == null) {
			return Optional.empty();
		}
		boolean owner = viewer.map(u -> u.email().equals(image.userEmail())).orElse(false);
		return owner || image.status() == Status.SUCCEEDED ? Optional.of(image) : Optional.empty();
	}

	public List<Image> gallery(String email, int page, int size) {
		Query q = query(where("userEmail").is(email).and("status").is(Status.SUCCEEDED))
			.with(Sort.by(Sort.Direction.DESC, "createdAt"))
			.skip((long) (page - 1) * size)
			.limit(size);
		return mongo.find(q, Image.class);
	}

	public long galleryCount(String email) {
		return mongo.count(query(where("userEmail").is(email).and("status").is(Status.SUCCEEDED)), Image.class);
	}

	/** Claims the upload (PENDING -> SAVING) so concurrent webhook and poll runs don't both upload. */
	private void save(String imageId, String outputUrl) {
		Image claimed = mongo.findAndModify(query(where("_id").is(imageId).and("status").is(Status.PENDING)),
				new Update().set("status", Status.SAVING).set("updatedAt", Instant.now()),
				FindAndModifyOptions.options().returnNew(true), Image.class);
		if (claimed == null) {
			return;
		}
		try {
			if (outputUrl == null) {
				throw new IllegalStateException("The model reported success without an image");
			}
			String url = storage.storeFromUrl(outputUrl, imageId);
			mongo.updateFirst(query(where("_id").is(imageId).and("status").is(Status.SAVING)),
					new Update().set("status", Status.SUCCEEDED).set("url", url).set("updatedAt", Instant.now()),
					Image.class);
		}
		catch (RuntimeException ex) {
			log.warn("Storing image {} failed: {}", imageId, ex.toString());
			fail(imageId, "UPLOAD_FAILED");
		}
	}

	public record Submitted(String id, Status status, int balance) {
	}
}
