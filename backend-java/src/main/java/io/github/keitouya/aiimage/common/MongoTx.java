package io.github.keitouya.aiimage.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.mongodb.MongoException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a MongoDB transaction and retries it when MongoDB says the failure is transient.
 *
 * <p>Two transactions touching the same document don't wait for each other in MongoDB: the second
 * fails at once with a WriteConflict labelled TransientTransactionError, and the driver expects the
 * caller to retry. Spring doesn't, so this does, with jittered backoff so retries don't collide again.
 */
@Component
public class MongoTx {

	static final int MAX_ATTEMPTS = 8;

	private final TransactionTemplate template;
	private final Counter attempts;
	private final Counter retries;
	private final ReentrantLock[] stripes = new ReentrantLock[256];

	MongoTx(MongoTransactionManager transactionManager, ObjectProvider<MeterRegistry> registry) {
		this.template = new TransactionTemplate(transactionManager);
		for (int i = 0; i < stripes.length; i++) {
			stripes[i] = new ReentrantLock();
		}
		MeterRegistry meters = registry.getIfAvailable(SimpleMeterRegistry::new);
		this.attempts = meters.counter("mongo.tx.attempts");
		this.retries = meters.counter("mongo.tx.retries");
	}

	public <T> T run(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			attempts.increment();
			try {
				return template.execute(status -> work.get());
			}
			catch (RuntimeException ex) {
				if (attempt >= MAX_ATTEMPTS || !isTransient(ex)) {
					throw ex;
				}
				retries.increment();
				sleepBeforeRetry(attempt);
			}
		}
	}

	/**
	 * Like {@link #run} but first queues behind other transactions on the same account in this JVM.
	 *
	 * <p>MongoDB transactions are optimistic: when two touch the same document, the later one is aborted at once
	 * instead of waiting. Dozens of concurrent charges on one account then abort each other until retries run out.
	 * Serializing per account turns those aborts into a short wait (cheap on virtual threads). Across instances the
	 * transient-error retry above is still the backstop. Accounts share 256 lock stripes; a transaction only ever
	 * takes one, so there is no lock ordering to get wrong.
	 */
	public <T> T runForAccount(String accountKey, Supplier<T> work) {
		ReentrantLock lock = stripes[Math.floorMod(accountKey.hashCode(), stripes.length)];
		lock.lock();
		try {
			return run(work);
		}
		finally {
			lock.unlock();
		}
	}

	public void runForAccount(String accountKey, Runnable work) {
		runForAccount(accountKey, () -> {
			work.run();
			return null;
		});
	}

	/** Transaction attempts and retries so far, for load tests and dashboards (mongo.tx.* metrics). */
	public long attemptCount() {
		return (long) attempts.count();
	}

	public long retryCount() {
		return (long) retries.count();
	}

	public void run(Runnable work) {
		run(() -> {
			work.run();
			return null;
		});
	}

	static boolean isTransient(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof MongoException mongo
					&& (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) || mongo.getCode() == 112)) {
				return true;
			}
		}
		return false;
	}

	private static void sleepBeforeRetry(int attempt) {
		long maxMillis = Math.min(200, 5L << attempt);
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(1, maxMillis + 1));
		}
		catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while retrying a transaction", ie);
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Config {

		@Bean
		MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
			return new MongoTransactionManager(factory);
		}
	}
}
