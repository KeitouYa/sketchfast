import { auth } from "@clerk/nextjs/server";
import { nanoid } from "nanoid";
import { handleEnhancePrompt } from "@/src/controllers/prompt/prompt.controller";
import { logger } from "@/src/lib/logger";

// POST /api/prompt/optimize — body: { prompt }
// Rewrites a short idea into a detailed image prompt. Free, but sign-in required.
export async function POST(req: Request) {
  const requestId = req.headers.get("x-request-id") || nanoid();
  logger.info("➡️ POST /api/prompt/optimize", { requestId });

  const { userId } = await auth();
  if (!userId) {
    return Response.json({ success: false, error: "Unauthorized" }, { status: 401 });
  }

  const input = await req.json().catch(() => null);
  const { status, body } = await handleEnhancePrompt(input, { requestId });
  return Response.json(body, { status });
}
