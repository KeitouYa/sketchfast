import { nanoid } from "nanoid";
import { getSignedInUser } from "@/src/lib/current-user";
import { handleCreatePaypalOrder } from "@/src/controllers/payment/paypal.controller";
import { logger } from "@/src/lib/logger";

// POST /api/paypal/orders — body: { packageId }
// Creates a PayPal order priced on the server and returns its id.
export async function POST(req: Request) {
  const requestId = req.headers.get("x-request-id") || nanoid();
  logger.info("➡️ POST /api/paypal/orders", { requestId });

  const buyer = await getSignedInUser();
  if (!buyer) {
    return Response.json({ success: false, error: "Unauthorized" }, { status: 401 });
  }

  const input = await req.json().catch(() => null);
  const { status, body } = await handleCreatePaypalOrder(input, {
    buyer: { email: buyer.email, clerkUserId: buyer.clerkUserId },
    requestId,
  });
  return Response.json(body, { status });
}
