package io.github.keitouya.aiimage.image;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.common.Page;
import io.github.keitouya.aiimage.image.provider.ImageGenerator;
import io.github.keitouya.aiimage.security.CurrentUser;
import io.github.keitouya.aiimage.webhook.ProcessedEvents;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ImageController {

	private final ImageService images;
	private final ImageGenerator generator;
	private final ProcessedEvents processedEvents;

	ImageController(ImageService images, ImageGenerator generator, ProcessedEvents processedEvents) {
		this.images = images;
		this.generator = generator;
		this.processedEvents = processedEvents;
	}

	/** Starts a generation; the client then polls GET /api/images/{id} until it is SUCCEEDED or FAILED. */
	@PostMapping("/api/images")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ImageService.Submitted generate(CurrentUser user, @RequestBody GenerateRequest request) {
		return images.submit(user, request.prompt(), request.style());
	}

	/** The signed-in user's finished images, newest first. */
	@GetMapping("/api/me/images")
	Page<ImageView> gallery(CurrentUser user, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "12") int size) {
		Page.Request request = Page.request(page, size);
		List<ImageView> items = images.gallery(user.email(), request.page(), request.size())
			.stream()
			.map(ImageView::of)
			.toList();
		return Page.of(items, request, images.galleryCount(user.email()));
	}

	@GetMapping("/api/images/{id}")
	ImageView get(@PathVariable String id) {
		return images.findVisible(id, CurrentUser.fromSecurityContext())
			.map(ImageView::of)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such image."));
	}

	/** Replicate's completion callback (Standard Webhooks signature). Redeliveries are skipped by webhook-id. */
	@PostMapping("/api/webhooks/replicate")
	Map<String, Boolean> replicateWebhook(@RequestBody String body,
			@RequestHeader(name = "webhook-id", required = false) String deliveryId,
			@RequestHeader(name = "webhook-timestamp", required = false) String timestamp,
			@RequestHeader(name = "webhook-signature", required = false) String signature) {
		ImageGenerator.Job job = generator.parseWebhook(deliveryId, timestamp, signature, body);
		if (!processedEvents.alreadyProcessed(deliveryId)) {
			images.onJobUpdate(job);
			processedEvents.markProcessed(deliveryId, "replicate");
		}
		return Map.of("received", true);
	}

	record GenerateRequest(String prompt, String style) {
	}

	/** What clients see of an image: no email, and a failure code instead of provider error text. */
	record ImageView(String id, Image.Status status, String prompt, String style, String url, String failureCode,
			String userName, Instant createdAt) {

		static ImageView of(Image image) {
			return new ImageView(image.id(), image.status(), image.prompt(), image.style(), image.url(),
					image.failureCode(), image.userName(), image.createdAt());
		}
	}
}
