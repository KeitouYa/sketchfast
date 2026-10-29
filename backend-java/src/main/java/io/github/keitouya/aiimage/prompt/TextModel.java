package io.github.keitouya.aiimage.prompt;

/** One LLM, reduced to the single call this app needs. */
public interface TextModel {

	/**
	 * @param instructions the system prompt
	 * @param input the user's text
	 */
	String complete(String instructions, String input);
}
