package io.github.keitouya.aiimage.credit;

import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Prices live only on the server; the client sends a package id and never an amount.
 *
 * @param signupBonus credits a new account starts with
 * @param imageCost credits charged per generated image
 */
@ConfigurationProperties("app.credits")
public record CreditProperties(int signupBonus, int imageCost, List<CreditPackage> packages) {

	public CreditProperties {
		packages = packages == null ? List.of() : List.copyOf(packages);
	}

	public Optional<CreditPackage> findPackage(String id) {
		return packages.stream().filter(p -> p.id().equals(id)).findFirst();
	}

	/** @param priceCents price in the currency's minor unit, e.g. 250 = $2.50 */
	public record CreditPackage(String id, int credits, long priceCents, String currency) {
	}
}
