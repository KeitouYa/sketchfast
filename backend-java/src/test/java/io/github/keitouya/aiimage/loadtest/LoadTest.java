package io.github.keitouya.aiimage.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import io.github.keitouya.aiimage.TestcontainersConfiguration;
import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.MongoTx;
import io.github.keitouya.aiimage.credit.CreditAccount;
import io.github.keitouya.aiimage.credit.CreditService;
import io.github.keitouya.aiimage.credit.LedgerEntry;
import io.github.keitouya.aiimage.image.Image;
import io.github.keitouya.aiimage.image.ImageService;
import io.github.keitouya.aiimage.image.provider.ImageGenerator;
import io.github.keitouya.aiimage.image.provider.ImageStorage;
import io.github.keitouya.aiimage.payment.PaymentGateway;
import io.github.keitouya.aiimage.payment.PaymentOrder;
import io.github.keitouya.aiimage.payment.PaymentService;
import io.github.keitouya.aiimage.security.CurrentUser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The TypeScript load scenarios rerun on the Java backend, against a real MongoDB replica set with simulated
 * providers. Each scenario checks the money invariants (no overdraft, credited once, balance = sum of ledger) and
 * records latency and transaction retries in target/loadtest-results.json.
 *
 * <p>Run: {@code ./mvnw test -Dgroups=load -Dtest.excluded.groups=}
 */
@Tag("load")
@SpringBootTest
@Import({ TestcontainersConfiguration.class, LoadTest.Fakes.class })
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LoadTest {

	static final Map<String, Object> RESULTS = Collections.synchronizedMap(new LinkedHashMap<>());

	@Autowired
	CreditService credits;

	@Autowired
	MongoTx tx;

	@Autowired
	MongoTemplate mongo;

	@Autowired
	PaymentService payments;

	@Autowired
	ImageService images;

	@Autowired
	SimGenerator generator;

	@Autowired
	SimStorage storage;

	@Autowired
	SimGateway gateway;

	@Test
	@Order(1)
	void spendAll() throws Exception {
		String email = newEmail();
		fund(email, 10_000);
		TxCounts tx0 = txCounts();

		Run run = Run.concurrently(10_000, i -> () -> {
			tx.runForAccount(email, () -> credits.chargeInTransaction(email, 1, "LOAD", email + ":" + i));
			return "charged";
		});

		assertThat(run.errors).isZero();
		assertThat(run.count("charged")).isEqualTo(10_000);
		assertThat(credits.balance(email)).isZero();
		assertBalanceMatchesLedger(email);
		record("spendAll", run.summary(Map.of("startBalance", 10_000, "finalBalance", credits.balance(email)), tx0));
	}

	@Test
	@Order(2)
	void overdraw() throws Exception {
		String email = newEmail();
		fund(email, 1_000);
		TxCounts tx0 = txCounts();

		Run run = Run.concurrently(10_000, i -> () -> {
			try {
				tx.runForAccount(email, () -> credits.chargeInTransaction(email, 1, "LOAD", email + ":" + i));
				return "charged";
			}
			catch (ApiException ex) {
				assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_CREDITS);
				return "rejected";
			}
		});

		assertThat(run.errors).isZero();
		assertThat(run.count("charged")).isEqualTo(1_000);
		assertThat(credits.balance(email)).isZero();
		assertBalanceMatchesLedger(email);
		record("overdraw", run.summary(Map.of("startBalance", 1_000, "finalBalance", 0), tx0));
	}

	@Test
	@Order(3)
	void newUserFirstRequests() throws Exception {
		String email = newEmail();

		Run run = Run.concurrently(1_000, i -> () -> String.valueOf(credits.balance(email)));

		assertThat(run.errors).isZero();
		assertThat(run.count("20")).isEqualTo(1_000);
		assertThat(mongo.count(query(where("userEmail").is(email)), CreditAccount.class)).isEqualTo(1);
		assertThat(mongo.count(query(where("userEmail").is(email)), LedgerEntry.class)).isEqualTo(1);
		record("newUser", run.summary(Map.of("accounts", 1, "freeCreditsGranted", 20), null));
	}

	@Test
	@Order(4)
	void manyNewUsers() throws Exception {
		List<String> users = emails(1_000);

		Run run = Run.concurrently(5_000, i -> () -> String.valueOf(credits.balance(users.get(i % users.size()))));

		assertThat(run.errors).isZero();
		long accounts = mongo.count(query(where("userEmail").in(users)), CreditAccount.class);
		long granted = mongo.find(query(where("userEmail").in(users)), LedgerEntry.class)
			.stream()
			.mapToLong(LedgerEntry::delta)
			.sum();
		assertThat(accounts).isEqualTo(1_000);
		assertThat(granted).isEqualTo(20_000);
		record("newUsers", run.summary(Map.of("users", 1_000, "accounts", accounts, "freeCreditsGranted", granted), null));
	}

	@Test
	@Order(5)
	void paymentsCreditedOnce() throws Exception {
		List<String> users = emails(1_000);
		List<String> orders = new ArrayList<>();
		for (String email : users) {
			String orderId = payments.startCheckout(new CurrentUser(email, "Load"), "credits_30").orderId();
			gateway.pay(orderId);
			orders.add(orderId);
		}
		TxCounts tx0 = txCounts();

		// Per order: the webhook, the same webhook redelivered, a second event, and two returns to the success page
		Run run = Run.concurrently(5_000, i -> () -> {
			String orderId = orders.get(i / 5);
			String email = users.get(i / 5);
			switch (i % 5) {
				case 0, 1 -> payments.handleWebhook(gateway.event("evt-" + orderId, orderId), "sig");
				case 2 -> payments.handleWebhook(gateway.event("evt-async-" + orderId, orderId), "sig");
				default -> payments.confirm(new CurrentUser(email, "Load"), orderId);
			}
			return "ok";
		});

		assertThat(run.errors).isZero();
		long paid = mongo.count(query(where("_id").in(orders).and("status").is(PaymentOrder.Status.PAID)), PaymentOrder.class);
		long purchases = mongo.count(query(where("userEmail").in(users).and("type").is(LedgerEntry.Type.PURCHASE)),
				LedgerEntry.class);
		assertThat(paid).isEqualTo(1_000);
		assertThat(purchases).isEqualTo(1_000);
		users.forEach(this::assertBalanceMatchesLedger);
		record("payments", run.summary(Map.of("orders", 1_000, "timesCredited", purchases, "creditsAdded", purchases * 30), tx0));
	}

	@Test
	@Order(6)
	void generationCrowd() throws Exception {
		List<String> users = emails(2_000);
		users.forEach(credits::ensureAccount);
		generator.reset();
		storage.reset();
		TxCounts tx0 = txCounts();

		Run submits = Run.concurrently(2_000, i -> () -> {
			try {
				images.submit(new CurrentUser(users.get(i), "Load"), "a lighthouse at dusk #" + i, "none");
				return "accepted";
			}
			catch (ApiException ex) {
				return ex.code().name();
			}
		});
		Run results = deliverResultsTwice();

		assertThat(submits.errors).isZero();
		assertThat(results.errors).isZero();
		Map<String, Object> outcome = assertGenerationInvariants(users);
		Map<String, Object> extra = new LinkedHashMap<>(outcome);
		extra.put("injectedFailureRates", Map.of("submit", 0.03, "model", 0.03, "upload", 0.01));
		extra.put("resultDeliveries", results.summary(Map.of(), null));
		record("generationCrowd", submits.summary(extra, tx0));
	}

	@Test
	@Order(7)
	void generationBurst() throws Exception {
		List<String> users = emails(100);
		for (String email : users) {
			credits.ensureAccount(email);
			tx.runForAccount(email, () -> credits.chargeInTransaction(email, 10, "LOAD", "seed:" + email));
		}
		generator.reset();
		storage.reset();
		TxCounts tx0 = txCounts();

		Run submits = Run.concurrently(2_000, i -> () -> {
			try {
				images.submit(new CurrentUser(users.get(i % 100), "Load"), "a lighthouse #" + i, "none");
				return "accepted";
			}
			catch (ApiException ex) {
				return ex.code().name();
			}
		});
		deliverResultsTwice();

		assertThat(submits.errors).isZero();
		Map<String, Object> outcome = assertGenerationInvariants(users);
		record("generationBurst", submits.summary(outcome, tx0));
	}

	/** Every finished job is reported twice at once, like a webhook racing the poller. */
	private Run deliverResultsTwice() throws Exception {
		List<String> jobs = new ArrayList<>(generator.jobs.keySet());
		jobs.forEach(generator::finish);
		return Run.concurrently(jobs.size() * 2, i -> () -> {
			images.onJobUpdate(generator.fetch(jobs.get(i / 2)));
			return "delivered";
		});
	}

	private Map<String, Object> assertGenerationInvariants(List<String> users) {
		List<Image> all = mongo.find(query(where("userEmail").in(users)), Image.class);
		long succeeded = all.stream().filter(i -> i.status() == Image.Status.SUCCEEDED).count();
		long failed = all.stream().filter(i -> i.status() == Image.Status.FAILED).count();
		long unfinished = all.size() - succeeded - failed;
		long refunds = mongo.count(query(where("userEmail").in(users).and("type").is(LedgerEntry.Type.REFUND)),
				LedgerEntry.class);
		long negative = mongo.count(query(where("userEmail").in(users).and("balance").lt(0)), CreditAccount.class);

		assertThat(unfinished).isZero();
		assertThat(refunds).isEqualTo(failed);
		assertThat(negative).isZero();
		assertThat(storage.uploads.get()).isEqualTo(succeeded + storage.failures.get());
		users.forEach(this::assertBalanceMatchesLedger);

		Map<String, Object> outcome = new LinkedHashMap<>();
		outcome.put("images", all.size());
		outcome.put("succeeded", succeeded);
		outcome.put("failedAndRefunded", failed);
		outcome.put("refundEntries", refunds);
		outcome.put("uploadsPerSuccess", succeeded == 0 ? 0 : (double) (storage.uploads.get() - storage.failures.get()) / succeeded);
		outcome.put("negativeBalances", negative);
		outcome.put("usersWithUnbalancedCredits", 0);
		return outcome;
	}

	private void fund(String email, int target) {
		credits.ensureAccount(email);
		tx.runForAccount(email, () -> credits.addPurchaseInTransaction(email, target - 20, "seed-" + UUID.randomUUID()));
	}

	private void assertBalanceMatchesLedger(String email) {
		int sum = mongo.find(query(where("userEmail").is(email)), LedgerEntry.class).stream().mapToInt(LedgerEntry::delta).sum();
		assertThat(credits.balance(email)).as("balance = sum of ledger for %s", email).isEqualTo(sum);
	}

	private TxCounts txCounts() {
		return new TxCounts(tx.attemptCount(), tx.retryCount(), tx);
	}

	private static String newEmail() {
		return "load-" + UUID.randomUUID() + "@test.dev";
	}

	private static List<String> emails(int n) {
		List<String> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(newEmail());
		}
		return list;
	}

	private static void record(String scenario, Map<String, Object> summary) {
		RESULTS.put(scenario, summary);
		System.out.println("[load] " + scenario + " " + summary);
	}

	@AfterAll
	static void writeResults() throws Exception {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("startedAt", Instant.now().toString());
		out.put("machine", Map.of("cores", Runtime.getRuntime().availableProcessors(), "jvm",
				System.getProperty("java.version"), "maxHeapMb", Runtime.getRuntime().maxMemory() / 1_048_576, "os",
				ManagementFactory.getOperatingSystemMXBean().getName(), "mongo", "7 (Testcontainers, single-node replica set)"));
		out.putAll(RESULTS);
		File file = new File("target/loadtest-results.json");
		JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValue(file, out);
		System.out.println("[load] results written to " + file.getAbsolutePath());
	}

	record TxCounts(long attempts, long retries, MongoTx tx) {

		Map<String, Object> delta() {
			return Map.of("txAttempts", tx.attemptCount() - attempts, "txRetries", tx.retryCount() - retries);
		}
	}

	/** Runs n tasks at once on virtual threads and keeps each one's latency and outcome. */
	static final class Run {

		final List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
		final Map<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
		int errors;
		long wallMillis;
		final List<String> errorSamples = new ArrayList<>();

		interface Task {
			String call() throws Exception;
		}

		static Run concurrently(int n, IntFunction<Task> tasks) throws Exception {
			Run run = new Run();
			long start = System.nanoTime();
			List<Future<String>> futures = new ArrayList<>(n);
			try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
				for (int i = 0; i < n; i++) {
					Task task = tasks.apply(i);
					futures.add(pool.submit(() -> {
						long t0 = System.nanoTime();
						try {
							return task.call();
						}
						finally {
							run.latenciesMicros.add((System.nanoTime() - t0) / 1_000);
						}
					}));
				}
				for (Future<String> f : futures) {
					try {
						run.outcomes.computeIfAbsent(f.get(), k -> new AtomicInteger()).incrementAndGet();
					}
					catch (Exception ex) {
						run.errors++;
						if (run.errorSamples.size() < 3) {
							run.errorSamples.add(String.valueOf(ex.getCause()));
							Throwable root = ex.getCause();
							while (root != null && root.getCause() != null) {
								root = root.getCause();
							}
							System.out.println("[load] error sample: " + ex.getCause() + " | root: " + root);
						}
					}
				}
			}
			run.wallMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
			return run;
		}

		int count(String outcome) {
			AtomicInteger c = outcomes.get(outcome);
			return c == null ? 0 : c.get();
		}

		Map<String, Object> summary(Map<String, Object> extra, TxCounts txBefore) {
			List<Long> sorted = new ArrayList<>(latenciesMicros);
			Collections.sort(sorted);
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("requests", sorted.size());
			Map<String, Integer> byOutcome = new LinkedHashMap<>();
			outcomes.forEach((k, v) -> byOutcome.put(k, v.get()));
			s.put("outcomes", byOutcome);
			s.put("errors", errors);
			if (!errorSamples.isEmpty()) {
				s.put("errorSamples", errorSamples);
			}
			s.put("wallSeconds", wallMillis / 1000.0);
			s.put("requestsPerSecond", wallMillis == 0 ? 0 : Math.round(sorted.size() * 1000.0 / wallMillis));
			s.put("p50Ms", percentile(sorted, 0.50));
			s.put("p99Ms", percentile(sorted, 0.99));
			s.put("maxMs", sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1) / 1000.0);
			if (txBefore != null) {
				s.putAll(txBefore.delta());
			}
			s.putAll(extra);
			return s;
		}

		private static double percentile(List<Long> sortedMicros, double p) {
			if (sortedMicros.isEmpty()) {
				return 0;
			}
			int index = (int) Math.ceil(p * sortedMicros.size()) - 1;
			return Math.round(sortedMicros.get(Math.max(0, index)) / 100.0) / 10.0;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Fakes {

		@Bean
		@Primary
		SimGenerator simGenerator() {
			return new SimGenerator();
		}

		@Bean
		@Primary
		SimStorage simStorage() {
			return new SimStorage();
		}

		@Bean
		@Primary
		SimGateway simGateway() {
			return new SimGateway();
		}
	}

	/** Replicate stand-in: 50 ms to accept a job; every 33rd submit and every 33rd result fails (~3%). */
	static class SimGenerator implements ImageGenerator {

		final Map<String, Job> jobs = new ConcurrentHashMap<>();
		final AtomicInteger submits = new AtomicInteger();
		final AtomicInteger finished = new AtomicInteger();

		void reset() {
			jobs.clear();
			submits.set(0);
			finished.set(0);
		}

		@Override
		public Job submit(String prompt, String webhookUrl) {
			sleep(50);
			if (submits.incrementAndGet() % 33 == 0) {
				throw new IllegalStateException("simulated Replicate outage");
			}
			Job job = new Job("job-" + UUID.randomUUID(), State.RUNNING, null, null);
			jobs.put(job.id(), job);
			return job;
		}

		void finish(String jobId) {
			boolean fails = finished.incrementAndGet() % 33 == 0;
			jobs.put(jobId, new Job(jobId, fails ? State.FAILED : State.SUCCEEDED, fails ? null : "https://sim/" + jobId,
					fails ? "simulated model error" : null));
		}

		@Override
		public Job fetch(String jobId) {
			return jobs.get(jobId);
		}

		@Override
		public Job parseWebhook(String deliveryId, String timestamp, String signature, String body) {
			throw new UnsupportedOperationException();
		}
	}

	/** Cloudinary stand-in: 100 ms per upload; every 100th fails (1%). */
	static class SimStorage implements ImageStorage {

		final AtomicInteger uploads = new AtomicInteger();
		final AtomicInteger failures = new AtomicInteger();

		void reset() {
			uploads.set(0);
			failures.set(0);
		}

		@Override
		public String storeFromUrl(String sourceUrl, String key) {
			sleep(100);
			if (uploads.incrementAndGet() % 100 == 0) {
				failures.incrementAndGet();
				throw new IllegalStateException("simulated Cloudinary outage");
			}
			return "https://cdn.sim/" + key + ".webp";
		}
	}

	/** Stripe stand-in. Events are "eventId|orderId"; every signature verifies. */
	static class SimGateway implements PaymentGateway {

		private final Map<String, Checkout> sessions = new ConcurrentHashMap<>();

		@Override
		public CheckoutPage createCheckout(PaymentOrder order, String customerEmail) {
			String sessionId = "cs_" + order.id();
			sessions.put(sessionId, new Checkout(sessionId, order.id(), false, order.amountCents(), order.currency()));
			return new CheckoutPage(sessionId, "https://checkout.sim/" + sessionId);
		}

		void pay(String orderId) {
			sessions.computeIfPresent("cs_" + orderId, (id, c) -> new Checkout(id, c.orderId(), true, c.amountTotal(), c.currency()));
		}

		String event(String eventId, String orderId) {
			return eventId + "|" + orderId;
		}

		@Override
		public Checkout retrieveCheckout(String sessionId) {
			return sessions.get(sessionId);
		}

		@Override
		public Event parseWebhook(String payload, String signatureHeader) {
			String[] parts = payload.split("\\|");
			return new Event(parts[0], "checkout.session.completed", sessions.get("cs_" + parts[1]));
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
