import { nanoid } from "nanoid";
import { getSignedInUser } from "@/src/lib/current-user";
import { handleCapturePaypalOrder } from "@/src/controllers/payment/paypal.controller";
import { logger } from "@/src/lib/logger";

// POST /api/paypal/orders/:orderId/capture
// Captures an approved order on the server and credits the buyer once.
export async function POST(
  req: Request,
  { params }: { params: Promise<{ orderId: string }> }
) {
  const requestId = req.headers.get("x-request-id") || nanoid();
  logger.info("➡️ POST /api/paypal/orders/:orderId/capture", { requestId });

  const buyer = await getSignedInUser();
  if (!buyer) {
    return Response.json({ success: false, error: "Unauthorized" }, { status: 401 });
  }

  const { orderId } = await params;
  const { status, body } = await handleCapturePaypalOrder(
    { orderId },
    { buyer: { email: buyer.email, clerkUserId: buyer.clerkUserId }, requestId }
  );
  return Response.json(body, { status });
}
