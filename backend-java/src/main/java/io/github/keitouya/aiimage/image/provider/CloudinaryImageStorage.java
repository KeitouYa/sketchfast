package io.github.keitouya.aiimage.image.provider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import com.cloudinary.Cloudinary;
import io.github.keitouya.aiimage.common.ApiException;
import io.github.keitouya.aiimage.common.ErrorCode;
import io.github.keitouya.aiimage.image.ImageProperties;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Component;

/**
 * Cloudinary fetches the image from the provider's URL itself, so the bytes never pass through this server,
 * and stores it as WebP. The image id is the public id, which makes a retried upload overwrite, not duplicate.
 */
@Component
public class CloudinaryImageStorage implements ImageStorage {

	private final ImageProperties.Cloudinary settings;
	private final Cloudinary cloudinary;

	public CloudinaryImageStorage(ImageProperties properties) {
		this.settings = properties.cloudinary();
		this.cloudinary = settings != null && settings.configured()
				? new Cloudinary(Map.of("cloud_name", settings.cloudName(), "api_key", settings.apiKey(), "api_secret",
						settings.apiSecret(), "secure", true))
				: null;
	}

	@Override
	@Retry(name = "cloudinary")
	public String storeFromUrl(String sourceUrl, String key) {
		if (cloudinary == null) {
			throw new ApiException(ErrorCode.PROVIDER_UNAVAILABLE, "Image storage is not configured (CLOUDINARY_*).");
		}
		try {
			Map<?, ?> result = cloudinary.uploader()
				.upload(sourceUrl, Map.of("folder", settings.folder(), "public_id", key, "overwrite", true, "format", "webp"));
			return String.valueOf(result.get("secure_url"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cloudinary upload failed", ex);
		}
	}
}
