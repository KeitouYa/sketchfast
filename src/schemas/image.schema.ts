import { z } from "zod";

export const ImageGenerateSchema = z.object({
  prompt: z
    .string()
    .trim()
    .min(3, "Prompt must be at least 3 characters")
    .max(300, "Prompt must be at most 300 characters"),
});
export type ImageGenerateInput = z.infer<typeof ImageGenerateSchema>;
