package io.github.keitouya.aiimage.security;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless API secured by Clerk session tokens. Tokens are verified locally against Clerk's JWKS
 * (cached by Nimbus), so a request never calls Clerk. Role checks are not needed yet: there is no admin API.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint) throws Exception {
		return http.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/credit-packages", "/api/styles", "/api/images/*").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/webhooks/**").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
			.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(ClerkProperties clerk) {
		if (!clerk.configured()) {
			// Lets the app start (health checks, webhooks) before Clerk is configured; every token is rejected.
			// BadJwtException, not JwtException: Spring Security turns the latter into a 500.
			return token -> {
				throw new BadJwtException("CLERK_ISSUER is not configured");
			};
		}
		String issuer = clerk.issuer().replaceAll("/+$", "");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json").build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				// Clerk tokens live 60s; tolerate clock skew so a slightly wrong PC clock doesn't fail "nbf"
				new JwtTimestampValidator(Duration.ofSeconds(60)), new JwtIssuerValidator(issuer),
				authorizedParty(clerk)));
		return decoder;
	}

	/** Clerk's recommended check: if the token names the origin it was issued for, it must be ours. */
	static OAuth2TokenValidator<Jwt> authorizedParty(ClerkProperties clerk) {
		return jwt -> {
			String azp = jwt.getClaimAsString("azp");
			if (azp == null || clerk.authorizedParties().contains(azp)) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected azp " + azp, null));
		};
	}
}
