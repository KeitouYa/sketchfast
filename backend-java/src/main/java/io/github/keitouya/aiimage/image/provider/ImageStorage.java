package io.github.keitouya.aiimage.image.provider;

/** Long-term image storage. Provider output URLs expire (Replicate keeps outputs for an hour). */
public interface ImageStorage {

	/**
	 * Copies the image at {@code sourceUrl} into storage under {@code key}. Storing the same key twice overwrites,
	 * so a retried upload never leaves two copies.
	 *
	 * @return the permanent public URL
	 */
	String storeFromUrl(String sourceUrl, String key);
}
