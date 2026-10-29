package io.github.keitouya.aiimage.common;

import java.util.List;

/** A page of results. Pages are numbered from 1, the way the frontend's ?page= parameter works. */
public record Page<T>(List<T> items, int page, int size, long total, int totalPages) {

	public static final int MAX_SIZE = 100;

	public static Request request(int page, int size) {
		if (page < 1 || size < 1 || size > MAX_SIZE) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED,
					"page must be 1 or more and size between 1 and " + MAX_SIZE + ".");
		}
		return new Request(page, size);
	}

	public static <T> Page<T> of(List<T> items, Request request, long total) {
		int totalPages = (int) ((total + request.size() - 1) / request.size());
		return new Page<>(items, request.page(), request.size(), total, totalPages);
	}

	public record Request(int page, int size) {
	}
}
