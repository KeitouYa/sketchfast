jest.mock("@/src/providers/ai/ai.factory");
jest.mock("@/src/lib/cache");

import { enhancePrompt, cleanEnhancedPrompt } from "@/src/services/prompt.service";
import { getAIProvider } from "@/src/providers/ai/ai.factory";
import { cache } from "@/src/lib/cache";
import { ProviderError } from "@/src/lib/errors";
import { MAX_IMAGE_PROMPT_LENGTH } from "@/src/schemas/image.schema";

const ENHANCED =
  "A tabby cat curled on a rain-streaked windowsill, soft grey morning light, shallow depth of field, cozy watercolor style";

function mockProviders(impl: Record<string, () => Promise<string>>) {
  (getAIProvider as jest.Mock).mockImplementation(async (name: string) => ({
    chat: jest.fn(),
    complete: jest.fn(impl[name]),
  }));
}

describe("Prompt service", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (cache.get as jest.Mock).mockResolvedValue(null);
    (cache.set as jest.Mock).mockResolvedValue(true);
  });

  it("rewrites an idea with Gemini and caches the result", async () => {
    mockProviders({ gemini: async () => ENHANCED });

    const result = await enhancePrompt("cat in the rain", "req1");

    expect(result).toEqual({ prompt: ENHANCED, cached: false, providerUsed: "gemini" });
    expect(getAIProvider).toHaveBeenCalledTimes(1);
    expect(cache.set).toHaveBeenCalledWith(
      expect.stringMatching(/^enhance:/),
      ENHANCED,
      expect.objectContaining({ ttl: expect.any(Number) })
    );
  });

  it("returns a cached rewrite without calling a provider", async () => {
    (cache.get as jest.Mock).mockResolvedValue(ENHANCED);

    const result = await enhancePrompt("cat in the rain", "req1");

    expect(result).toEqual({ prompt: ENHANCED, cached: true, providerUsed: null });
    expect(getAIProvider).not.toHaveBeenCalled();
  });

  it("falls back to OpenAI when Gemini fails", async () => {
    mockProviders({
      gemini: async () => {
        throw new Error("quota exceeded");
      },
      openai: async () => ENHANCED,
    });

    const result = await enhancePrompt("cat in the rain", "req1");

    expect(result.providerUsed).toBe("openai");
    expect(result.prompt).toBe(ENHANCED);
  });

  it("treats an empty answer as a failure and tries the next provider", async () => {
    mockProviders({ gemini: async () => '  ""  ', openai: async () => ENHANCED });

    const result = await enhancePrompt("cat in the rain", "req1");

    expect(result.providerUsed).toBe("openai");
  });

  it("throws ProviderError and caches nothing when every provider fails", async () => {
    mockProviders({
      gemini: async () => {
        throw new Error("down");
      },
      openai: async () => {
        throw new Error("down");
      },
    });

    await expect(enhancePrompt("cat in the rain", "req1")).rejects.toBeInstanceOf(ProviderError);
    expect(cache.set).not.toHaveBeenCalled();
  });

  it("strips labels and quotes and keeps the result within the image prompt limit", () => {
    expect(cleanEnhancedPrompt(`Prompt: "${ENHANCED}"`)).toBe(ENHANCED);

    const long = cleanEnhancedPrompt(Array(200).fill("detailed").join(" "));
    expect(long.length).toBeLessThanOrEqual(MAX_IMAGE_PROMPT_LENGTH);
    expect(long.endsWith("detailed")).toBe(true);
  });
});
