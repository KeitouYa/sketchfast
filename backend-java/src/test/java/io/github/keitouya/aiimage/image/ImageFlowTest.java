package io.github.keitouya.aiimage.image;

import static io.github.keitouya.aiimage.Concurrency.concurrently;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import io.github.keitouya.aiimage.TestcontainersConfiguration;
import io.github.keitouya.aiimage.common.MongoTx;
import io.github.keitouya.aiimage.credit.CreditAccount;
import io.github.keitouya.aiimage.credit.CreditService;
import io.github.keitouya.aiimage.credit.LedgerEntry;
import io.github.keitouya.aiimage.image.Image.Status;
import io.github.keitouya.aiimage.image.provider.ImageGenerator;
import io.github.keitouya.aiimage.image.provider.ImageStorage;
import io.github.keitouya.aiimage.image.provider.ReplicateImageGenerator;
import io.github.keitouya.aiimage.image.provider.WebhookSigner;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asynchronous generation and its billing (ADR-001 R2, R3) with fakes in place of Replicate and Cloudinary.
 * Webhook signatures are checked for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ImageFlowTest.Fakes.class })
class ImageFlowTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ImageService images;

	@Autowired
	CreditService credits;

	@Autowired
	MongoTx tx;

	@Autowired
	MongoTemplate mongo;

	@Autowired
	FakeGenerator generator;

	@Autowired
	FakeStorage storage;

	private final String email = "i-" + UUID.randomUUID() + "@test.dev";

	private final RequestPostProcessor user = jwt().jwt(j -> j.claim("email", email).claim("name", "Ivy"));

	@BeforeEach
	void reset() {
		generator.failSubmit = false;
		storage.fail = false;
	}

	@Test
	void submitChargesOneCreditAndStartsTheJobWithTheStyleFirst() throws Exception {
		String id = submit("a red bicycle", "anime");

		Image image = image(id);
		assertThat(image.status()).isEqualTo(Status.PENDING);
		assertThat(image.prompt()).isEqualTo("a red bicycle");
		assertThat(image.providerJobId()).isNotNull();
		assertThat(generator.prompts).contains("anime key visual, clean line art, cel shading, vibrant colors, "
				+ "detailed background, a red bicycle");
		assertThat(credits.balance(email)).isEqualTo(19);
	}

	@Test
	void invalidRequestIsRejectedBeforeCharging() throws Exception {
		postImage("{\"prompt\":\"hi\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		postImage("{\"prompt\":\"a red bicycle\",\"style\":\"vaporwave\"}").andExpect(status().isBadRequest());

		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void noCreditsLeftIsPaymentRequired() throws Exception {
		credits.ensureAccount(email);
		mongo.updateFirst(query(where("userEmail").is(email)), Update.update("balance", 0), CreditAccount.class);

		postImage("{\"prompt\":\"a red bicycle\"}").andExpect(status().isPaymentRequired())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDITS"));
	}

	@Test
	void providerDownIsRefundedRightAway() throws Exception {
		generator.failSubmit = true;

		postImage("{\"prompt\":\"a red bicycle\"}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));

		assertThat(credits.balance(email)).isEqualTo(20);
		assertThat(ledgerTypes()).containsExactlyInAnyOrder(LedgerEntry.Type.SIGNUP_BONUS, LedgerEntry.Type.CHARGE,
				LedgerEntry.Type.REFUND);
		assertBalanceMatchesLedger();
	}

	@Test
	void racingWebhookAndPollStoreTheImageOnce() throws Exception {
		String id = submit("a red bicycle", "none");
		String job = image(id).providerJobId();
		generator.finish(job, ImageGenerator.State.SUCCEEDED);
		int uploadsBefore = storage.uploads.get();

		concurrently(10, i -> {
			images.onJobUpdate(generator.fetch(job));
			return null;
		});

		Image done = image(id);
		assertThat(done.status()).isEqualTo(Status.SUCCEEDED);
		assertThat(done.url()).isEqualTo("https://cdn.test/" + id + ".webp");
		assertThat(storage.uploads.get() - uploadsBefore).isEqualTo(1);
		assertThat(credits.balance(email)).isEqualTo(19);
	}

	@Test
	void racingFailureReportsRefundOnce() throws Exception {
		String id = submit("a red bicycle", "none");
		String job = image(id).providerJobId();
		generator.finish(job, ImageGenerator.State.FAILED);

		concurrently(10, i -> {
			images.onJobUpdate(generator.fetch(job));
			return null;
		});

		assertThat(image(id).status()).isEqualTo(Status.FAILED);
		assertThat(image(id).failureCode()).isEqualTo("PROVIDER_FAILED");
		assertThat(credits.balance(email)).isEqualTo(20);
		assertThat(ledgerTypes().stream().filter(t -> t == LedgerEntry.Type.REFUND)).hasSize(1);
		assertBalanceMatchesLedger();
	}

	@Test
	void uploadFailureIsRefunded() throws Exception {
		String id = submit("a red bicycle", "none");
		storage.fail = true;
		generator.finish(image(id).providerJobId(), ImageGenerator.State.SUCCEEDED);

		images.onJobUpdate(generator.fetch(image(id).providerJobId()));

		assertThat(image(id).status()).isEqualTo(Status.FAILED);
		assertThat(image(id).failureCode()).isEqualTo("UPLOAD_FAILED");
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void pollingFinishesJobsTheWebhookMissed() throws Exception {
		String id = submit("a red bicycle", "none");
		generator.finish(image(id).providerJobId(), ImageGenerator.State.SUCCEEDED);
		mongo.updateFirst(query(where("_id").is(id)), Update.update("createdAt", Instant.now().minusSeconds(60)), Image.class);

		images.pollRunning();

		assertThat(image(id).status()).isEqualTo(Status.SUCCEEDED);
	}

	@Test
	void sweepFailsAndRefundsImagesPastTheirDeadlineOnce() throws Exception {
		String id = submit("a red bicycle", "none");
		expire(id);

		images.sweepExpired();
		images.sweepExpired();

		assertThat(image(id).status()).isEqualTo(Status.FAILED);
		assertThat(image(id).failureCode()).isEqualTo("TIMEOUT");
		assertThat(credits.balance(email)).isEqualTo(20);
		assertBalanceMatchesLedger();
	}

	@Test
	void sweepRefundsAChargeWhoseWorkerCrashedBeforeStartingTheJob() {
		// What the database looks like if the process died right after the charge transaction
		credits.ensureAccount(email);
		String id = new ObjectId().toHexString();
		Instant now = Instant.now();
		tx.run(() -> {
			credits.chargeInTransaction(email, 1, ImageService.REF_TYPE, id);
			mongo.insert(new Image(id, email, "Ivy", "a red bicycle", "none", Status.PENDING, null, 1, null, null,
					now.minusSeconds(1), now, now, null));
		});

		images.sweepExpired();

		assertThat(credits.balance(email)).isEqualTo(20);
		assertBalanceMatchesLedger();
	}

	@Test
	void imageStuckInSavingIsSweptToo() throws Exception {
		String id = submit("a red bicycle", "none");
		mongo.updateFirst(query(where("_id").is(id)), Update.update("status", Status.SAVING), Image.class);
		expire(id);

		images.sweepExpired();

		assertThat(image(id).status()).isEqualTo(Status.FAILED);
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void signedReplicateWebhookFinishesTheImageAndRedeliveryIsSkipped() throws Exception {
		String id = submit("a red bicycle", "none");
		String body = "{\"id\":\"" + image(id).providerJobId()
				+ "\",\"status\":\"succeeded\",\"output\":[\"https://replicate.delivery/x.webp\"]}";
		String delivery = "msg_" + UUID.randomUUID();
		int uploadsBefore = storage.uploads.get();

		replicateWebhook(delivery, body, true).andExpect(status().isOk());
		replicateWebhook(delivery, body, true).andExpect(status().isOk());

		assertThat(image(id).status()).isEqualTo(Status.SUCCEEDED);
		assertThat(storage.uploads.get() - uploadsBefore).isEqualTo(1);
	}

	@Test
	void unsignedReplicateWebhookIsRejected() throws Exception {
		String id = submit("a red bicycle", "none");
		String body = "{\"id\":\"" + image(id).providerJobId() + "\",\"status\":\"failed\"}";

		replicateWebhook("msg_" + UUID.randomUUID(), body, false).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
		assertThat(image(id).status()).isEqualTo(Status.PENDING);
	}

	@Test
	void providerCallsAreWrappedByTheRetryAspect(@Autowired org.springframework.context.ApplicationContext context) {
		// Without the proxy, @Retry would silently do nothing
		assertThat(org.springframework.aop.support.AopUtils.isAopProxy(context.getBean(ReplicateImageGenerator.class)))
			.isTrue();
		assertThat(org.springframework.aop.support.AopUtils
			.isAopProxy(context.getBean(io.github.keitouya.aiimage.image.provider.CloudinaryImageStorage.class))).isTrue();
	}

	@Test
	void ownerSeesProgressButOthersOnlySeeFinishedImages() throws Exception {
		String id = submit("a red bicycle", "none");

		mvc.perform(get("/api/images/" + id).with(user)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.userEmail").doesNotExist());
		mvc.perform(get("/api/images/" + id).with(jwt().jwt(j -> j.claim("email", "other@test.dev"))))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/images/" + id)).andExpect(status().isNotFound());

		generator.finish(image(id).providerJobId(), ImageGenerator.State.SUCCEEDED);
		images.onJobUpdate(generator.fetch(image(id).providerJobId()));

		mvc.perform(get("/api/images/" + id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.userName").value("Ivy"));
		mvc.perform(get("/api/images/not-an-id")).andExpect(status().isNotFound());
	}

	@Test
	void ownersPollPicksUpAFinishedJobWithoutWaitingForTheWebhook() throws Exception {
		String id = submit("a red bicycle", "none");
		generator.finish(image(id).providerJobId(), ImageGenerator.State.SUCCEEDED);

		mvc.perform(get("/api/images/" + id).with(user))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.url").value("https://cdn.test/" + id + ".webp"));
	}

	@Test
	void ownerChecksAreThrottledAndOthersNeverTriggerThem() throws Exception {
		String id = submit("a red bicycle", "none");
		String job = image(id).providerJobId();
		int before = generator.fetches(job);

		mvc.perform(get("/api/images/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/images/" + id).with(user)).andExpect(jsonPath("$.status").value("PENDING"));
		mvc.perform(get("/api/images/" + id).with(user)).andExpect(jsonPath("$.status").value("PENDING"));

		// The anonymous read checked nothing; of the two owner reads within 2 seconds only one asked the provider
		assertThat(generator.fetches(job) - before).isEqualTo(1);
	}

	@Test
	void safetyFilterRejectionIsReportedAsContentFilteredAndRefunded() throws Exception {
		String id = submit("a single rose, close-up", "comic");
		generator.filter(image(id).providerJobId());

		mvc.perform(get("/api/images/" + id).with(user))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.failureCode").value("CONTENT_FILTERED"));
		assertThat(credits.balance(email)).isEqualTo(20);
	}

	@Test
	void galleryListsOnlyFinishedImagesNewestFirst() throws Exception {
		String first = finishedImage("first image");
		String failed = submit("will fail", "none");
		generator.finish(image(failed).providerJobId(), ImageGenerator.State.FAILED);
		images.onJobUpdate(generator.fetch(image(failed).providerJobId()));
		submit("still running", "none");
		String second = finishedImage("second image");

		mvc.perform(get("/api/me/images?size=1").with(user))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items[0].id").value(second));
		mvc.perform(get("/api/me/images?page=2&size=1").with(user))
			.andExpect(jsonPath("$.items[0].id").value(first));
		mvc.perform(get("/api/me/images")).andExpect(status().isUnauthorized());
	}

	private String finishedImage(String prompt) throws Exception {
		String id = submit(prompt, "none");
		generator.finish(image(id).providerJobId(), ImageGenerator.State.SUCCEEDED);
		images.onJobUpdate(generator.fetch(image(id).providerJobId()));
		return id;
	}

	private String submit(String prompt, String style) throws Exception {
		String body = postImage("{\"prompt\":\"" + prompt + "\",\"style\":\"" + style + "\"}")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private org.springframework.test.web.servlet.ResultActions postImage(String json) throws Exception {
		return mvc.perform(post("/api/images").with(user).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private org.springframework.test.web.servlet.ResultActions replicateWebhook(String delivery, String body,
			boolean signed) throws Exception {
		String ts = WebhookSigner.timestampNow();
		String signature = signed ? WebhookSigner.sign(WebhookSigner.TEST_SECRET, delivery, ts, body) : "v1,AAAA";
		return mvc.perform(post("/api/webhooks/replicate").header("webhook-id", delivery)
			.header("webhook-timestamp", ts)
			.header("webhook-signature", signature)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private void expire(String id) {
		mongo.updateFirst(query(where("_id").is(id)), Update.update("deadlineAt", Instant.now().minusSeconds(1)),
				Image.class);
	}

	private Image image(String id) {
		return mongo.findOne(query(where("_id").is(id)), Image.class);
	}

	private List<LedgerEntry.Type> ledgerTypes() {
		return mongo.find(query(where("userEmail").is(email)), LedgerEntry.class).stream().map(LedgerEntry::type).toList();
	}

	private void assertBalanceMatchesLedger() {
		int sum = mongo.find(query(where("userEmail").is(email)), LedgerEntry.class)
			.stream()
			.mapToInt(LedgerEntry::delta)
			.sum();
		assertThat(credits.balance(email)).isEqualTo(sum);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fakes {

		@Bean
		@Primary
		FakeGenerator fakeGenerator(ImageProperties properties) {
			return new FakeGenerator(new ReplicateImageGenerator(properties, RestClient.builder(), JsonMapper.builder().build()));
		}

		@Bean
		@Primary
		FakeStorage fakeStorage() {
			return new FakeStorage();
		}
	}

	/** Jobs live in memory; webhook parsing (signature included) is Replicate's real code. */
	static class FakeGenerator implements ImageGenerator {

		final List<String> prompts = new CopyOnWriteArrayList<>();
		final Map<String, Job> jobs = new ConcurrentHashMap<>();
		private final ReplicateImageGenerator real;
		volatile boolean failSubmit;

		FakeGenerator(ReplicateImageGenerator real) {
			this.real = real;
		}

		@Override
		public Job submit(String prompt, String webhookUrl) {
			if (failSubmit) {
				throw new IllegalStateException("Replicate is down");
			}
			prompts.add(prompt);
			Job job = new Job("job-" + UUID.randomUUID(), State.RUNNING, null, null);
			jobs.put(job.id(), job);
			return job;
		}

		@Override
		public Job fetch(String jobId) {
			fetchCounts.computeIfAbsent(jobId, k -> new AtomicInteger()).incrementAndGet();
			return jobs.get(jobId);
		}

		@Override
		public Job parseWebhook(String deliveryId, String timestamp, String signature, String body) {
			return real.parseWebhook(deliveryId, timestamp, signature, body);
		}

		void finish(String jobId, State state) {
			jobs.put(jobId, new Job(jobId, state, state == State.SUCCEEDED ? "https://replicate.delivery/" + jobId : null,
					state == State.FAILED ? "model error" : null));
		}

		void filter(String jobId) {
			jobs.put(jobId, new Job(jobId, State.FAILED, null, "NSFW content detected", true));
		}

		int fetches(String jobId) {
			AtomicInteger count = fetchCounts.get(jobId);
			return count == null ? 0 : count.get();
		}

		private final Map<String, AtomicInteger> fetchCounts = new ConcurrentHashMap<>();
	}

	static class FakeStorage implements ImageStorage {

		final AtomicInteger uploads = new AtomicInteger();
		volatile boolean fail;

		@Override
		public String storeFromUrl(String sourceUrl, String key) {
			if (fail) {
				throw new IllegalStateException("Cloudinary is down");
			}
			uploads.incrementAndGet();
			sleep(Duration.ofMillis(20)); // widen the race window for concurrent tests
			return "https://cdn.test/" + key + ".webp";
		}

		private static void sleep(Duration d) {
			try {
				Thread.sleep(d);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
