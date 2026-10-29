package io.github.keitouya.aiimage.common;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes returned in the "code" field of every error response.
 * The frontend translates by code, so renaming one is a breaking API change.
 */
public enum ErrorCode {
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
	INVALID_SIGNATURE(HttpStatus.BAD_REQUEST, "Invalid webhook signature"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
	INSUFFICIENT_CREDITS(HttpStatus.PAYMENT_REQUIRED, "Insufficient credits"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
	UNSUPPORTED_REQUEST(HttpStatus.METHOD_NOT_ALLOWED, "Unsupported request"),
	PAYMENT_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "Payment provider error"),
	PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}
}
