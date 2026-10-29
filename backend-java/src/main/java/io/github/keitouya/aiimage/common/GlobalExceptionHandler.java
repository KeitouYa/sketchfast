package io.github.keitouya.aiimage.common;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 9457 problem details plus two fields:
 * "code" (an {@link ErrorCode} the frontend translates) and "requestId" (to find the logs).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
		if (ex.code().status().is5xxServerError()) {
			log.warn("{}: {}", ex.code(), ex.getMessage(), ex.getCause());
		}
		return respond(ex.code(), ex.getMessage(), new HttpHeaders());
	}

	/** Reached from {@link io.github.keitouya.aiimage.security.ProblemAuthenticationEntryPoint}. */
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		return respond(ErrorCode.UNAUTHENTICATED, "Sign in and send a valid Clerk session token.", headers);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled error", ex);
		return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong. Quote the requestId if you report it.",
				new HttpHeaders());
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
			.toList();
		ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED, "Some fields are invalid.");
		body.setProperty("errors", errors);
		return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).headers(headers).body(body);
	}

	/**
	 * Framework errors (404, 405, unreadable JSON...) keep their status but gain a code and request id.
	 * Hooked here rather than in handleExceptionInternal: the problem body is only built after that call.
	 */
	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
			WebRequest request) {
		if (body instanceof ProblemDetail detail
				&& (detail.getProperties() == null || !detail.getProperties().containsKey("code"))) {
			detail.setProperty("code", codeFor(statusCode).name());
			detail.setProperty("requestId", RequestIdFilter.current());
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private static ErrorCode codeFor(HttpStatusCode status) {
		if (status.value() == HttpStatus.NOT_FOUND.value()) {
			return ErrorCode.NOT_FOUND;
		}
		if (status.value() == HttpStatus.UNAUTHORIZED.value()) {
			return ErrorCode.UNAUTHENTICATED;
		}
		if (status.is5xxServerError()) {
			return ErrorCode.INTERNAL_ERROR;
		}
		if (status.value() == HttpStatus.BAD_REQUEST.value()) {
			return ErrorCode.VALIDATION_FAILED;
		}
		return ErrorCode.UNSUPPORTED_REQUEST;
	}

	private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, HttpHeaders headers) {
		return ResponseEntity.status(code.status()).headers(headers).body(problem(code, detail));
	}

	static ProblemDetail problem(ErrorCode code, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
		body.setTitle(code.title());
		body.setProperty("code", code.name());
		body.setProperty("requestId", RequestIdFilter.current());
		return body;
	}
}
