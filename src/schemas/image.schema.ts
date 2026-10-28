import { z } from "zod";
import { STYLE_IDS } from "@/src/lib/style-presets";

// Room for a prompt rewritten by the Enhance step (about 60 words)
export const MAX_IMAGE_PROMPT_LENGTH = 600;

export const ImageGenerateSchema = z.object({
  prompt: z
    .string()
    .trim()
    .min(3, "Prompt must be at least 3 characters")
    .max(MAX_IMAGE_PROMPT_LENGTH, `Prompt must be at most ${MAX_IMAGE_PROMPT_LENGTH} characters`),
  style: z.enum(STYLE_IDS, { message: "Unknown style" }).default("none"),
});
export type ImageGenerateInput = z.infer<typeof ImageGenerateSchema>;
