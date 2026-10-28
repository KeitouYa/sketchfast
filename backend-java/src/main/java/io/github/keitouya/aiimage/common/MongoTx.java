package io.github.keitouya.aiimage.common;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.mongodb.MongoException;
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

	MongoTx(MongoTransactionManager transactionManager) {
		this.template = new TransactionTemplate(transactionManager);
	}

	public <T> T run(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return template.execute(status -> work.get());
			}
			catch (RuntimeException ex) {
				if (attempt >= MAX_ATTEMPTS || !isTransient(ex)) {
					throw ex;
				}
				sleepBeforeRetry(attempt);
			}
		}
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
