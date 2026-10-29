package io.github.keitouya.aiimage.image.provider;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature check from the Standard Webhooks spec, which Replicate uses: HMAC-SHA256 over
 * "{webhook-id}.{webhook-timestamp}.{body}" with the base64 key after "whsec_", sent as one or more
 * space-separated "v1,{base64}" values. The timestamp check stops old deliveries being replayed.
 */
final class StandardWebhooks {

	static final Duration TOLERANCE = Duration.ofMinutes(5);

	private StandardWebhooks() {
	}

	static boolean verify(String secret, String id, String timestamp, String body, String signatures, Instant now) {
		if (secret == null || secret.isBlank() || id == null || timestamp == null || signatures == null) {
			return false;
		}
		long sentAt;
		try {
			sentAt = Long.parseLong(timestamp);
		}
		catch (NumberFormatException ex) {
			return false;
		}
		if (Math.abs(now.getEpochSecond() - sentAt) > TOLERANCE.toSeconds()) {
			return false;
		}
		byte[] expected = sign(secret, id + "." + timestamp + "." + body);
		for (String candidate : signatures.split(" ")) {
			String[] versionAndSig = candidate.split(",", 2);
			if (versionAndSig.length == 2 && "v1".equals(versionAndSig[0])
					&& MessageDigest.isEqual(expected, decode(versionAndSig[1]))) {
				return true;
			}
		}
		return false;
	}

	static byte[] sign(String secret, String content) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(decode(secret.startsWith("whsec_") ? secret.substring(6) : secret), "HmacSHA256"));
			return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] decode(String base64) {
		try {
			return Base64.getDecoder().decode(base64);
		}
		catch (IllegalArgumentException ex) {
			return new byte[0];
		}
	}
}
