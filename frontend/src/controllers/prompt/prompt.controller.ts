import { EnhancePromptSchema } from "@/src/schemas/prompt.schema";
import { enhancePrompt } from "@/src/services/prompt.service";
import { success, fail } from "@/src/lib/http";
import { AppError } from "@/src/lib/errors";
import { logger } from "@/src/lib/logger";

type ControllerResponse = {
  status: number;
  body: ReturnType<typeof success> | ReturnType<typeof fail>;
};

export async function handleEnhancePrompt(
  input: unknown,
  { requestId }: { requestId: string }
): Promise<ControllerResponse> {
  const parsed = EnhancePromptSchema.safeParse(input);
  if (!parsed.success) {
    return { status: 400, body: fail(parsed.error.issues[0].message) };
  }

  try {
    const result = await enhancePrompt(parsed.data.prompt, requestId);
    return { status: 200, body: success(result) };
  } catch (err) {
    if (err instanceof AppError) {
      return { status: err.status, body: fail(err.message) };
    }
    logger.error("Failed to enhance prompt", { err, requestId });
    return { status: 500, body: fail("Failed to enhance prompt") };
  }
}
