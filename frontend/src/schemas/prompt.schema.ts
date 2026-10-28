import { z } from "zod";

export const EnhancePromptSchema = z
  .object({
    prompt: z
      .string()
      .trim()
      .min(1, "Prompt cannot be empty")
      .max(500, "Prompt is too long (max 500 characters)"),
  })
  .strict();

export type EnhancePromptInput = z.infer<typeof EnhancePromptSchema>;
