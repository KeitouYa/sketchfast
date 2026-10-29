package io.github.keitouya.aiimage.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer the Clerk instance URL, also the "iss" claim; its JWKS is at {@code issuer/.well-known/jwks.json}
 * @param authorizedParties origins accepted in the "azp" claim (the frontend URLs)
 */
@ConfigurationProperties("app.clerk")
public record ClerkProperties(String issuer, List<String> authorizedParties) {

	public ClerkProperties {
		authorizedParties = authorizedParties == null ? List.of() : List.copyOf(authorizedParties);
	}

	public boolean configured() {
		return issuer != null && !issuer.isBlank();
	}
}
