package io.github.keitouya.aiimage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs tasks that start at the same moment, so race conditions actually race. */
public final class Concurrency {

	private Concurrency() {
	}

	public interface Task<T> {
		T call(int index) throws Exception;
	}

	public static <T> List<T> concurrently(int count, Task<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 16));
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				Callable<T> call = () -> {
					start.await();
					return task.call(index);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get());
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}
}
