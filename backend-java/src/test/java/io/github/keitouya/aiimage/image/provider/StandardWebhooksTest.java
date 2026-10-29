package io.github.keitouya.aiimage.image.provider;

import static io.github.keitouya.aiimage.image.provider.WebhookSigner.TEST_SECRET;
import static io.github.keitouya.aiimage.image.provider.WebhookSigner.sign;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class StandardWebhooksTest {

	private static final String BODY = "{\"id\":\"p1\",\"status\":\"succeeded\"}";

	private final Instant now = Instant.now();

	private final String ts = String.valueOf(now.getEpochSecond());

	@Test
	void correctSignatureVerifies() {
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_1", ts, BODY, sign(TEST_SECRET, "msg_1", ts, BODY), now))
			.isTrue();
	}

	@Test
	void anyMatchingSignatureInTheListVerifies() {
		String header = "v1,bm90LWl0 " + sign(TEST_SECRET, "msg_1", ts, BODY);
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_1", ts, BODY, header, now)).isTrue();
	}

	@Test
	void changedBodyFails() {
		String header = sign(TEST_SECRET, "msg_1", ts, BODY);
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_1", ts, BODY.replace("succeeded", "failed"), header, now))
			.isFalse();
	}

	@Test
	void otherDeliveryIdFails() {
		String header = sign(TEST_SECRET, "msg_1", ts, BODY);
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_2", ts, BODY, header, now)).isFalse();
	}

	@Test
	void oldDeliveryIsRejectedAsReplay() {
		String old = String.valueOf(now.minusSeconds(600).getEpochSecond());
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_1", old, BODY, sign(TEST_SECRET, "msg_1", old, BODY), now))
			.isFalse();
	}

	@Test
	void wrongSecretOrMissingHeadersFail() {
		String header = sign("whsec_b3RoZXItc2VjcmV0", "msg_1", ts, BODY);
		assertThat(StandardWebhooks.verify(TEST_SECRET, "msg_1", ts, BODY, header, now)).isFalse();
		assertThat(StandardWebhooks.verify(TEST_SECRET, null, ts, BODY, header, now)).isFalse();
		assertThat(StandardWebhooks.verify("", "msg_1", ts, BODY, header, now)).isFalse();
	}
}
