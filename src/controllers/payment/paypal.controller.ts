import {
  CreatePaypalOrderSchema,
  CapturePaypalOrderSchema,
} from "@/src/schemas/payment.schema";
import {
  startCreditPurchase,
  completeCreditPurchase,
  type Buyer,
} from "@/src/services/paypal.service";
import { success, fail } from "@/src/lib/http";
import { AppError, ValidationError } from "@/src/lib/errors";
import { logger } from "@/src/lib/logger";

type PaymentContext = {
  buyer: Buyer;
  requestId: string;
};

type ControllerResponse = {
  status: number;
  body: ReturnType<typeof success> | ReturnType<typeof fail>;
};

function toErrorResponse(err: unknown, fallback: string, requestId: string): ControllerResponse {
  if (err instanceof AppError || err instanceof ValidationError) {
    return { status: err.status, body: fail(err.message) };
  }
  logger.error(fallback, { err, requestId });
  return { status: 500, body: fail(fallback) };
}

export async function handleCreatePaypalOrder(
  input: unknown,
  { buyer, requestId }: PaymentContext
): Promise<ControllerResponse> {
  const parsed = CreatePaypalOrderSchema.safeParse(input);
  if (!parsed.success) {
    return { status: 400, body: fail(parsed.error.issues[0].message) };
  }

  try {
    const result = await startCreditPurchase(buyer, parsed.data.packageId, requestId);
    return { status: 200, body: success(result) };
  } catch (err) {
    return toErrorResponse(err, "Failed to create PayPal order", requestId);
  }
}

export async function handleCapturePaypalOrder(
  input: unknown,
  { buyer, requestId }: PaymentContext
): Promise<ControllerResponse> {
  const parsed = CapturePaypalOrderSchema.safeParse(input);
  if (!parsed.success) {
    return { status: 400, body: fail(parsed.error.issues[0].message) };
  }

  try {
    const result = await completeCreditPurchase(buyer, parsed.data.orderId, requestId);
    return { status: 200, body: success(result) };
  } catch (err) {
    return toErrorResponse(err, "Failed to capture PayPal order", requestId);
  }
}
