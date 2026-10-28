package io.github.keitouya.aiimage.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class SecurityConfigTest {

	private final ClerkProperties clerk = new ClerkProperties("https://demo.clerk.accounts.dev",
			List.of("http://localhost:3000"));

	@Test
	void tokenForOurFrontendPassesTheAzpCheck() {
		OAuth2TokenValidator<Jwt> validator = SecurityConfig.authorizedParty(clerk);
		assertThat(validator.validate(token("http://localhost:3000")).hasErrors()).isFalse();
		assertThat(validator.validate(token(null)).hasErrors()).isFalse();
	}

	@Test
	void tokenIssuedForAnotherOriginIsRejected() {
		assertThat(SecurityConfig.authorizedParty(clerk).validate(token("https://evil.example")).hasErrors()).isTrue();
	}

	@Test
	void withoutIssuerEveryTokenIsRejected() {
		var decoder = new SecurityConfig().jwtDecoder(new ClerkProperties("", List.of()));
		assertThatThrownBy(() -> decoder.decode("anything")).isInstanceOf(JwtException.class);
	}

	private static Jwt token(String azp) {
		Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("user_1")
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (azp != null) {
			builder.claim("azp", azp);
		}
		return builder.build();
	}
}
