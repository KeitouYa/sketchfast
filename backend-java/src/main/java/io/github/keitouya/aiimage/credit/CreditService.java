package io.github.keitouya.aiimage.credit;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Instant;
import java.util.List;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.MongoTx;
import io.github.keitouya.aiimage.credit.LedgerEntry.Type;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Credit balances and the ledger (ADR-001).
 *
 * <p>Methods named {@code *InTransaction} change the balance and must run inside {@link MongoTx#runForAccount}, so
 * the caller can put its own writes (an image record, a payment status) in the same transaction. Every balance
 * change writes a ledger entry whose unique idempotency key stops the same event being applied twice.
 */
@Service
public class CreditService {

	private final MongoTemplate mongo;
	private final CreditProperties properties;

	CreditService(MongoTemplate mongo, CreditProperties properties) {
		this.mongo = mongo;
		this.properties = properties;
	}

	public int balance(String email) {
		return ensureAccount(email).balance();
	}

	/**
	 * Opens the account with the signup bonus on first use, without a transaction: the ledger entry is written
	 * first, then the account is upserted. Both writes are single-document and repeatable (unique keys), so
	 * concurrent first requests, or a crash between the two writes, still end with one account whose balance
	 * equals its ledger. A transaction here aborted under a burst of first requests (MongoDB WriteConflict).
	 */
	public CreditAccount ensureAccount(String email) {
		CreditAccount existing = findAccount(email);
		if (existing != null) {
			return existing;
		}
		int bonus = properties.signupBonus();
		try {
			mongo.insert(LedgerEntry.of(email, bonus, Type.SIGNUP_BONUS, "ACCOUNT", email, "signup:" + email, bonus));
		}
		catch (DuplicateKeyException alreadyGranted) {
			// a concurrent first request wrote it
		}
		Instant now = Instant.now();
		try {
			mongo.upsert(query(where("userEmail").is(email)),
					new Update().setOnInsert("balance", bonus).setOnInsert("createdAt", now).setOnInsert("updatedAt", now),
					CreditAccount.class);
		}
		catch (DuplicateKeyException alreadyOpened) {
			// two upserts raced on the unique index; the other one inserted
		}
		return findAccount(email);
	}

	/**
	 * Takes {@code cost} credits only if the balance covers it: one conditional update, so concurrent
	 * charges can never overdraw (ADR-001 R1).
	 *
	 * @return the balance after the charge
	 * @throws ApiException INSUFFICIENT_CREDITS, leaving the balance untouched
	 */
	public int chargeInTransaction(String email, int cost, String refType, String refId) {
		requireTransaction();
		CreditAccount after = mongo.findAndModify(query(where("userEmail").is(email).and("balance").gte(cost)),
				new Update().inc("balance", -cost).set("updatedAt", Instant.now()),
				FindAndModifyOptions.options().returnNew(true), CreditAccount.class);
		if (after == null) {
			throw new ApiException(ErrorCode.INSUFFICIENT_CREDITS,
					"This costs " + cost + " credits and your balance is too low. Buy more credits to continue.");
		}
		mongo.insert(LedgerEntry.of(email, -cost, Type.CHARGE, refType, refId, key(refType, refId, "charge"),
				after.balance()));
		return after.balance();
	}

	/** Gives back a charge. The ledger key makes a second refund of the same charge fail with a duplicate key. */
	public int refundInTransaction(String email, int amount, String refType, String refId) {
		return add(email, amount, Type.REFUND, refType, refId, key(refType, refId, "refund"));
	}

	/** Adds purchased credits once per order. */
	public int addPurchaseInTransaction(String email, int amount, String orderId) {
		return add(email, amount, Type.PURCHASE, "PAYMENT", orderId, "payment:" + orderId);
	}

	public List<LedgerEntry> history(String email, int page, int size) {
		Query q = query(where("userEmail").is(email)).with(Sort.by(Sort.Direction.DESC, "createdAt"))
			.skip((long) (page - 1) * size)
			.limit(size);
		return mongo.find(q, LedgerEntry.class);
	}

	public long historyCount(String email) {
		return mongo.count(query(where("userEmail").is(email)), LedgerEntry.class);
	}

	private int add(String email, int amount, Type type, String refType, String refId, String key) {
		requireTransaction();
		CreditAccount after = mongo.findAndModify(query(where("userEmail").is(email)),
				new Update().inc("balance", amount).set("updatedAt", Instant.now()),
				FindAndModifyOptions.options().returnNew(true), CreditAccount.class);
		if (after == null) {
			throw new IllegalStateException("No credit account for " + email);
		}
		mongo.insert(LedgerEntry.of(email, amount, type, refType, refId, key, after.balance()));
		return after.balance();
	}

	private CreditAccount findAccount(String email) {
		return mongo.findOne(query(where("userEmail").is(email)), CreditAccount.class);
	}

	private static String key(String refType, String refId, String action) {
		return refType.toLowerCase() + ":" + refId + ":" + action;
	}

	private static void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Balance changes must run inside MongoTx.run");
		}
	}
}
