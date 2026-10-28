package io.github.keitouya.aiimage.security;

import java.util.Locale;
import java.util.Optional;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in user, read from the Clerk session token. Users are keyed by email, which the
 * Clerk instance must put in the token (Sessions -> Customize session token: "email", "name").
 * Declare it as a controller parameter to require sign-in.
 */
public record CurrentUser(String email, String name) {

	public static Optional<CurrentUser> fromSecurityContext() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
			return Optional.empty();
		}
		return Optional.of(fromJwt(jwt));
	}

	public static CurrentUser require() {
		return fromSecurityContext()
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in and send a valid Clerk session token."));
	}

	static CurrentUser fromJwt(Jwt jwt) {
		String email = jwt.getClaimAsString("email");
		if (email == null || email.isBlank()) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED,
					"The session token has no email claim. Add it in Clerk: Sessions -> Customize session token.");
		}
		String normalized = email.trim().toLowerCase(Locale.ROOT);
		String name = jwt.getClaimAsString("name");
		return new CurrentUser(normalized, name == null || name.isBlank() ? normalized.split("@")[0] : name.trim());
	}
}
