// Mock dependencies BEFORE imports
jest.mock("mongoose");
jest.mock("@/src/repositories/credits.repository");
jest.mock("@/src/providers/ai/ai.factory");
jest.mock("@/src/providers/ai/openai.provider", () => ({
  OpenAIProvider: jest.fn().mockImplementation(() => ({
    chat: jest.fn(),
  })),
}));
jest.mock("@/src/providers/ai/gemini.provider", () => ({
  GeminiProvider: jest.fn().mockImplementation(() => ({
    chat: jest.fn(),
  })),
}));
jest.mock("@/src/lib/cache");
jest.mock("@clerk/nextjs/server", () => ({
  currentUser: jest.fn(() => ({
    emailAddresses: [{ emailAddress: "test@example.com" }],
  })),
}));
jest.mock("@/src/providers/db.provider", () => ({
  __esModule: true,
  default: jest.fn(() => Promise.resolve()),
}));

// Now import after mocks
import { sendChatMessage } from "@/src/services/chat.service";
import { creditRepository } from "@/src/repositories/credits.repository";
import { getAIProvider } from "@/src/providers/ai/ai.factory";
import { cache } from "@/src/lib/cache";
import { InsufficientCreditsError, ProviderError } from "@/src/lib/errors";

// The DISABLE_CHAT_* flags are read at module load; jest.setup.js sets them
// to "false", so cache and credits are both on in these tests.
describe("Chat Service", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (cache.get as jest.Mock).mockResolvedValue(null);
    (cache.set as jest.Mock).mockResolvedValue(true);
    (creditRepository.deductCreditsAtomic as jest.Mock).mockResolvedValue({
      success: true,
      remainingCredits: 9,
    });
    (creditRepository.refundCredits as jest.Mock).mockResolvedValue(10);
  });

  it("should return cached reply if available", async () => {
    const mockCachedReply = "Cached response";
    (cache.get as jest.Mock).mockResolvedValue(mockCachedReply);

    const result = await sendChatMessage(
      "test message",
      "gemini",
      "user123",
      "req123"
    );

    expect(result.reply).toBe(mockCachedReply);
    expect(result.cached).toBe(true);
    expect(creditRepository.deductCreditsAtomic).not.toHaveBeenCalled();
  });

  it("should deduct one credit and return the AI reply when not cached", async () => {
    const mockAI = {
      chat: jest.fn().mockResolvedValue("AI response"),
    };
    (getAIProvider as jest.Mock).mockResolvedValue(mockAI);

    const result = await sendChatMessage(
      "test message",
      "gemini",
      "user123",
      "req123"
    );

    expect(creditRepository.deductCreditsAtomic).toHaveBeenCalledWith(
      "test@example.com",
      1,
      "req123"
    );
    expect(mockAI.chat).toHaveBeenCalledWith("test message");
    expect(result.reply).toBe("AI response");
    expect(result.cached).toBe(false);
    expect(result.remainingCredits).toBe(9);
    expect(creditRepository.refundCredits).not.toHaveBeenCalled();
  });

  it("should handle provider fallback correctly", async () => {
    // Mock Gemini to fail, OpenAI to succeed
    const mockGemini = {
      chat: jest.fn().mockRejectedValue(new Error("Gemini failed")),
    };
    const mockOpenAI = {
      chat: jest.fn().mockResolvedValue("Fallback response"),
    };

    (getAIProvider as jest.Mock)
      .mockResolvedValueOnce(mockGemini)
      .mockResolvedValueOnce(mockOpenAI);

    const result = await sendChatMessage(
      "test message",
      "gemini", // Request Gemini first
      "user123",
      "req123"
    );

    expect(result.reply).toBe("Fallback response");
    expect(result.cached).toBe(false);
    expect(creditRepository.refundCredits).not.toHaveBeenCalled();
  });

  it("should refund the credit when every provider fails", async () => {
    const failing = () => ({
      chat: jest.fn().mockRejectedValue(new Error("provider down")),
    });
    (getAIProvider as jest.Mock)
      .mockResolvedValueOnce(failing())
      .mockResolvedValueOnce(failing());

    await expect(
      sendChatMessage("test message", "gemini", "user123", "req123")
    ).rejects.toBeInstanceOf(ProviderError);

    expect(creditRepository.refundCredits).toHaveBeenCalledWith(
      "test@example.com",
      1,
      "req123"
    );
    expect(cache.set).not.toHaveBeenCalled();
  });

  it("should not call the AI when credits are insufficient", async () => {
    (creditRepository.deductCreditsAtomic as jest.Mock).mockResolvedValue({
      success: false,
      remainingCredits: 0,
    });

    await expect(
      sendChatMessage("test message", "gemini", "user123", "req123")
    ).rejects.toBeInstanceOf(InsufficientCreditsError);

    expect(getAIProvider).not.toHaveBeenCalled();
    expect(creditRepository.refundCredits).not.toHaveBeenCalled();
  });
});
