package io.github.keitouya.aiimage.image.provider;

/** A text-to-image model run asynchronously: submit, then learn the result from a webhook or by polling. */
public interface ImageGenerator {

	/**
	 * Starts a generation.
	 *
	 * @param webhookUrl where the provider reports completion, or null to rely on polling
	 */
	Job submit(String prompt, String webhookUrl);

	Job fetch(String jobId);

	/**
	 * Verifies a webhook delivery's signature and reads the job from it.
	 *
	 * @throws io.github.keitouya.aiimage.common.ApiException INVALID_SIGNATURE
	 */
	Job parseWebhook(String deliveryId, String timestamp, String signature, String body);

	record Job(String id, State state, String outputUrl, String error) {
	}

	enum State {
		RUNNING, SUCCEEDED, FAILED
	}
}
