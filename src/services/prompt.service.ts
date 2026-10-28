/**
 * Prompt enhancement: turns a short idea into a detailed text-to-image prompt.
 *
 * Deliberately NOT "use server": it is only reached through
 * POST /api/prompt/optimize, which checks the session first.
 * Free for the user (no credits); results are cached by prompt.
 */

import crypto from "crypto";
import { getAIProvider, type ProviderName } from "@/src/providers/ai/ai.factory";
import { cache } from "@/src/lib/cache";
import { logger } from "@/src/lib/logger";
import { ProviderError } from "@/src/lib/errors";
import { withTimeout, TIMEOUTS } from "@/src/lib/timeout";
import { MAX_IMAGE_PROMPT_LENGTH } from "@/src/schemas/image.schema";

const CACHE_TTL_SECONDS = 7 * 24 * 60 * 60;

const PROVIDERS: ProviderName[] = ["gemini", "openai"];

// Bump when ENHANCE_INSTRUCTIONS change, so cached rewrites from the old wording aren't served
const INSTRUCTIONS_VERSION = 2;

export const ENHANCE_INSTRUCTIONS = [
  "You rewrite a user's short idea into a prompt for a text-to-image model.",
  "Keep the user's subject and intent; never add events, weather or moods they did not imply.",
  "Add concrete visual detail: the subject, setting, lighting, and composition or camera angle.",
  "Do not name an art style, medium or artist; the user picks the style separately.",
  "If the idea is not in English, translate it.",
  "Write one paragraph in English, under 60 words, with no lists, quotes, labels or preamble.",
  "Output only the prompt.",
].join(" ");

function hashPrompt(text: string) {
  return crypto.createHash("sha256").update(text).digest("hex").slice(0, 16);
}

function cacheKey(prompt: string) {
  return `enhance:v${INSTRUCTIONS_VERSION}:${hashPrompt(prompt.toLowerCase().replace(/\s+/g, " "))}`;
}

// Models sometimes wrap the answer in quotes or prefix it with "Prompt:"
export function cleanEnhancedPrompt(raw: string) {
  let text = raw.trim().replace(/^prompt\s*:\s*/i, "");
  text = text.replace(/^["'`“”]+|["'`“”]+$/g, "");
  text = text.replace(/\s+/g, " ").trim();

  // Never hand back something the image form would reject
  if (text.length > MAX_IMAGE_PROMPT_LENGTH) {
    const cut = text.slice(0, MAX_IMAGE_PROMPT_LENGTH);
    text = cut.slice(0, cut.lastIndexOf(" ")).replace(/[,;:\s]+$/, "");
  }
  return text;
}

export type EnhanceResult = {
  prompt: string;
  cached: boolean;
  providerUsed: ProviderName | null;
};

export async function enhancePrompt(
  idea: string,
  requestId?: string
): Promise<EnhanceResult> {
  const key = cacheKey(idea);
  const hit = await cache.get<string>(key, requestId);
  if (hit) {
    return { prompt: hit, cached: true, providerUsed: null };
  }

  for (const provider of PROVIDERS) {
    const t0 = Date.now();
    try {
      const ai = await getAIProvider(provider);
      const raw = await withTimeout(
        ai.complete(ENHANCE_INSTRUCTIONS, idea),
        TIMEOUTS.AI_CHAT,
        `Prompt enhance via ${provider} timeout`,
        requestId
      );

      const prompt = cleanEnhancedPrompt(raw);
      if (!prompt) throw new Error(`${provider} returned an empty prompt`);

      logger.info("Prompt enhanced", {
        requestId,
        provider,
        ms: Date.now() - t0,
        promptHash: hashPrompt(idea).slice(0, 12),
      });
      await cache.set(key, prompt, { ttl: CACHE_TTL_SECONDS, requestId });
      return { prompt, cached: false, providerUsed: provider };
    } catch (err) {
      logger.warn(`Prompt enhance via ${provider} failed`, { err, requestId });
    }
  }

  throw new ProviderError("Could not enhance the prompt right now");
}
