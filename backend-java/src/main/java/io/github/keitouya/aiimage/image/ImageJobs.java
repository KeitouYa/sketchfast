package io.github.keitouya.aiimage.image;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The background half of ADR-001 R3: poll Replicate for results the webhook hasn't delivered, then fail and refund
 * anything past its deadline. Running it on two instances at once is safe (every change is a conditional update);
 * scaling out would add ShedLock only to avoid the wasted work. Tests turn it off and call the service directly.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.images.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class ImageJobs {

	private final ImageService images;

	ImageJobs(ImageService images) {
		this.images = images;
	}

	@Scheduled(initialDelayString = "${app.images.poll-interval}", fixedDelayString = "${app.images.poll-interval}")
	void pollAndSweep() {
		images.pollRunning();
		images.sweepExpired();
	}
}
