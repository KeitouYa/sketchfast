package io.github.keitouya.aiimage.credit;

import java.time.Instant;
import java.util.List;

import io.github.keitouya.aiimage.common.Page;
import io.github.keitouya.aiimage.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CreditController {

	private final CreditService credits;
	private final CreditProperties properties;

	CreditController(CreditService credits, CreditProperties properties) {
		this.credits = credits;
		this.properties = properties;
	}

	@GetMapping("/api/credit-packages")
	List<CreditProperties.CreditPackage> packages() {
		return properties.packages();
	}

	@GetMapping("/api/me/credits")
	Balance balance(CurrentUser user) {
		return new Balance(credits.balance(user.email()));
	}

	@GetMapping("/api/me/credit-ledger")
	Page<Entry> ledger(CurrentUser user, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size) {
		Page.Request request = Page.request(page, size);
		credits.ensureAccount(user.email());
		List<Entry> items = credits.history(user.email(), request.page(), request.size())
			.stream()
			.map(e -> new Entry(e.delta(), e.type(), e.refType(), e.refId(), e.balanceAfter(), e.createdAt()))
			.toList();
		return Page.of(items, request, credits.historyCount(user.email()));
	}

	record Balance(int balance) {
	}

	record Entry(int delta, LedgerEntry.Type type, String refType, String refId, int balanceAfter, Instant createdAt) {
	}
}
