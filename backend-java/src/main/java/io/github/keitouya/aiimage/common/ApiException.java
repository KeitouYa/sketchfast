package io.github.keitouya.aiimage.common;

/**
 * An expected failure that maps straight to an error response. Throw it from services;
 * {@link GlobalExceptionHandler} turns it into problem details with the code.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode code;

	public ApiException(ErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ApiException(ErrorCode code, String detail, Throwable cause) {
		super(detail, cause);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
