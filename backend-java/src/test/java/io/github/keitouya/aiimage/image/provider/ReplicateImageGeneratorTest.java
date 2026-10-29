package io.github.keitouya.aiimage.image.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.image.ImageProperties;
import io.github.keitouya.aiimage.image.provider.ImageGenerator.Job;
import io.github.keitouya.aiimage.image.provider.ImageGenerator.State;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ReplicateImageGeneratorTest {

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private ReplicateImageGenerator generator(String token) {
		var props = new ImageProperties("black-forest-labs/flux-schnell", token, WebhookSigner.TEST_SECRET, null,
				Duration.ofMinutes(5), Duration.ofSeconds(15), Duration.ofSeconds(10), false, null);
		return new ReplicateImageGenerator(props, builder, JsonMapper.builder().build());
	}

	@Test
	void submitRunsTheOfficialModelByNameWithTheWebhook() {
		ReplicateImageGenerator generator = generator("r8_token");
		server.expect(requestTo("https://api.replicate.com/v1/models/black-forest-labs/flux-schnell/predictions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer r8_token"))
			.andExpect(jsonPath("$.input.prompt").value("anime key visual, a cat"))
			.andExpect(jsonPath("$.input.output_format").value("webp"))
			.andExpect(jsonPath("$.webhook").value("https://api.example.com/api/webhooks/replicate"))
			.andExpect(jsonPath("$.webhook_events_filter[0]").value("completed"))
			.andRespond(withSuccess("{\"id\":\"p1\",\"status\":\"starting\"}", MediaType.APPLICATION_JSON));

		Job job = generator.submit("anime key visual, a cat", "https://api.example.com/api/webhooks/replicate");

		assertThat(job).isEqualTo(new Job("p1", State.RUNNING, null, null));
		server.verify();
	}

	@Test
	void withoutWebhookUrlNoWebhookIsSent() {
		ReplicateImageGenerator generator = generator("r8_token");
		server.expect(requestTo("https://api.replicate.com/v1/models/black-forest-labs/flux-schnell/predictions"))
			.andExpect(jsonPath("$.webhook").doesNotExist())
			.andRespond(withSuccess("{\"id\":\"p1\",\"status\":\"starting\"}", MediaType.APPLICATION_JSON));

		generator.submit("a cat", null);
		server.verify();
	}

	@Test
	void fetchReadsTheFirstOutputUrl() {
		ReplicateImageGenerator generator = generator("r8_token");
		server.expect(requestTo("https://api.replicate.com/v1/predictions/p1"))
			.andRespond(withSuccess("{\"id\":\"p1\",\"status\":\"succeeded\",\"output\":[\"https://replicate.delivery/a.webp\"]}",
					MediaType.APPLICATION_JSON));

		assertThat(generator.fetch("p1")).isEqualTo(new Job("p1", State.SUCCEEDED, "https://replicate.delivery/a.webp", null));
	}

	@Test
	void failedAndCanceledPredictionsAreFailures() {
		assertThat(ReplicateImageGenerator.toJob(new ReplicateImageGenerator.Prediction("p1", "failed", null, "NSFW")).state())
			.isEqualTo(State.FAILED);
		assertThat(ReplicateImageGenerator.toJob(new ReplicateImageGenerator.Prediction("p1", "canceled", null, null)).state())
			.isEqualTo(State.FAILED);
		assertThat(ReplicateImageGenerator.toJob(new ReplicateImageGenerator.Prediction("p1", "processing", null, null)).state())
			.isEqualTo(State.RUNNING);
	}

	@Test
	void missingTokenIsProviderUnavailable() {
		assertThatThrownBy(() -> generator("").submit("a cat", null)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.code()).isEqualTo(ErrorCode.PROVIDER_UNAVAILABLE));
	}
}
