package io.github.keitouya.aiimage.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.keitouya.aiimage.TestcontainersConfiguration;
import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.MongoTx;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** ADR-001 R1 (no overdraft), R3 (refund once) and R5 (balance = sum of ledger) against a real replica set. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CreditServiceTest {

	@Autowired
	CreditService credits;

	@Autowired
	MongoTx tx;

	@Autowired
	MongoTemplate mongo;

	private final String email = "u-" + UUID.randomUUID() + "@test.dev";

	/** Image ids are unique in real use; ledger keys are globally unique, so tests must not share ids. */
	private final String img = "img-" + UUID.randomUUID();

	@Test
	void newAccountStartsWithTheSignupBonusAndOneLedgerEntry() {
		assertThat(credits.balance(email)).isEqualTo(20);
		assertThat(credits.balance(email)).isEqualTo(20);
		assertThat(ledger(email)).singleElement().satisfies(e -> {
			assertThat(e.type()).isEqualTo(LedgerEntry.Type.SIGNUP_BONUS);
			assertThat(e.balanceAfter()).isEqualTo(20);
		});
	}

	@Test
	void concurrentFirstRequestsOpenOneAccountAndGiveTheBonusOnce() throws Exception {
		List<Integer> balances = concurrently(32, i -> credits.balance(email));

		assertThat(balances).containsOnly(20);
		assertThat(mongo.count(query(where("userEmail").is(email)), CreditAccount.class)).isEqualTo(1);
		assertThat(ledger(email)).hasSize(1);
	}

	@Test
	void chargeTakesCreditsAndRecordsTheCharge() {
		credits.ensureAccount(email);
		int after = tx.run(() -> credits.chargeInTransaction(email, 3, "IMAGE", img));

		assertThat(after).isEqualTo(17);
		assertThat(ledger(email)).anySatisfy(e -> {
			assertThat(e.type()).isEqualTo(LedgerEntry.Type.CHARGE);
			assertThat(e.delta()).isEqualTo(-3);
			assertThat(e.idempotencyKey()).isEqualTo("image:" + img + ":charge");
		});
		assertBalanceMatchesLedger(email);
	}

	@Test
	void chargeAboveTheBalanceFailsAndChangesNothing() {
		credits.ensureAccount(email);

		assertThatThrownBy(() -> tx.run(() -> credits.chargeInTransaction(email, 21, "IMAGE", img)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_CREDITS));
		assertThat(credits.balance(email)).isEqualTo(20);
		assertThat(ledger(email)).hasSize(1);
	}

	@Test
	void concurrentChargesNeverOverdraw() throws Exception {
		credits.ensureAccount(email);

		List<Boolean> charged = concurrently(60, i -> {
			try {
				tx.run(() -> credits.chargeInTransaction(email, 1, "IMAGE", img + "-" + i));
				return true;
			}
			catch (ApiException ex) {
				assertThat(ex.code()).isEqualTo(ErrorCode.INSUFFICIENT_CREDITS);
				return false;
			}
		});

		assertThat(charged.stream().filter(ok -> ok)).hasSize(20);
		assertThat(credits.balance(email)).isZero();
		assertBalanceMatchesLedger(email);
	}

	@Test
	void theSameChargeIsRefundedOnlyOnce() {
		credits.ensureAccount(email);
		tx.run(() -> credits.chargeInTransaction(email, 1, "IMAGE", img));

		tx.run(() -> credits.refundInTransaction(email, 1, "IMAGE", img));
		assertThatThrownBy(() -> tx.run(() -> credits.refundInTransaction(email, 1, "IMAGE", img)))
			.isInstanceOf(DuplicateKeyException.class);

		assertThat(credits.balance(email)).isEqualTo(20);
		assertBalanceMatchesLedger(email);
	}

	@Test
	void purchaseIsCreditedOncePerOrder() {
		credits.ensureAccount(email);
		tx.run(() -> credits.addPurchaseInTransaction(email, 30, "order-" + img));
		assertThatThrownBy(() -> tx.run(() -> credits.addPurchaseInTransaction(email, 30, "order-" + img)))
			.isInstanceOf(DuplicateKeyException.class);

		assertThat(credits.balance(email)).isEqualTo(50);
		assertBalanceMatchesLedger(email);
	}

	@Test
	void balanceChangesOutsideATransactionAreRefused() {
		credits.ensureAccount(email);
		assertThatThrownBy(() -> credits.chargeInTransaction(email, 1, "IMAGE", img))
			.isInstanceOf(IllegalStateException.class);
	}

	private List<LedgerEntry> ledger(String email) {
		return mongo.find(query(where("userEmail").is(email)), LedgerEntry.class);
	}

	private void assertBalanceMatchesLedger(String email) {
		int sum = ledger(email).stream().mapToInt(LedgerEntry::delta).sum();
		assertThat(credits.balance(email)).isEqualTo(sum);
	}

	interface Task<T> {
		T call(int index) throws Exception;
	}

	/** Starts all tasks at the same moment so they really race. */
	static <T> List<T> concurrently(int count, Task<T> task) throws Exception {
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
