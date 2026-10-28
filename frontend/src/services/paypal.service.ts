/**
 * PAYPAL PURCHASE SERVICE
 * ----------------------------------------------------
 * Credit purchases are priced, captured and credited on the server,
 * from what PayPal reports, never from what the browser says.
 *
 * Deliberately NOT a "use server" module: these functions take the buyer's
 * identity as arguments, so exposing them as Server Actions would let any
 * client pass someone else's email. Call them only from route handlers that
 * read the identity from Clerk.
 */

import db from "@/src/providers/db.provider";
import { createPaypalOrder, capturePaypalOrder } from "@/src/providers/paypal.provider";
import { paymentRepository } from "@/src/repositories/payment.repository";
import { creditRepository } from "@/src/repositories/credits.repository";
import { PaymentStatus } from "@/src/models/payment.model";
import { CREDIT_CURRENCY, findCreditPackage } from "@/src/lib/credit-packages";
import { AppError, ValidationError } from "@/src/lib/errors";
import { logger } from "@/src/lib/logger";

export interface Buyer {
  email: string;
  clerkUserId: string;
}

async function currentBalance(email: string): Promise<number> {
  const credit = await creditRepository.findUserCredit(email);
  return credit?.credits ?? 0;
}

// =====================================================================
// Step 1 — create a PayPal order priced from the server's package table
// =====================================================================
export async function startCreditPurchase(
  buyer: Buyer,
  packageId: string,
  requestId?: string
) {
  const pkg = findCreditPackage(packageId);
  if (!pkg) throw new ValidationError("Unknown credit package");

  await db();

  const orderId = await createPaypalOrder(
    {
      referenceId: pkg.id,
      amount: pkg.price,
      currency: CREDIT_CURRENCY,
      description: `${pkg.credits} credits`,
    },
    requestId
  );

  await paymentRepository.createPending({
    orderId,
    userEmail: buyer.email,
    packageId: pkg.id,
    credits: pkg.credits,
    amount: pkg.price,
    currency: CREDIT_CURRENCY,
  });

  logger.info("Credit purchase started", { orderId, packageId: pkg.id, requestId });
  return { orderId };
}

// =====================================================================
// Step 2 — after the buyer approves in the PayPal popup: capture on the
// server and credit the account once. Safe to call again for the same order.
// =====================================================================
export async function completeCreditPurchase(
  buyer: Buyer,
  orderId: string,
  requestId?: string
) {
  await db();

  // Only the buyer who created the order can capture it
  const payment = await paymentRepository.findForUser(orderId, buyer.email);
  if (!payment) throw new AppError("Order not found", 404);

  if (payment.status === PaymentStatus.COMPLETED) {
    return { credits: await currentBalance(buyer.email), alreadyCompleted: true };
  }

  const capture = await capturePaypalOrder(orderId, requestId);

  if (
    capture.orderStatus !== "COMPLETED" ||
    capture.captureStatus !== "COMPLETED" ||
    !capture.captureId
  ) {
    // e.g. PENDING while PayPal reviews the payment: nothing to credit yet
    logger.warn("PayPal capture not completed", {
      orderId,
      orderStatus: capture.orderStatus,
      captureStatus: capture.captureStatus,
      requestId,
    });
    throw new AppError("Payment has not completed yet", 409);
  }

  if (
    capture.currency !== payment.currency ||
    Number(capture.amount) !== Number(payment.amount)
  ) {
    logger.error("PayPal captured amount does not match the order", {
      orderId,
      expected: `${payment.amount} ${payment.currency}`,
      captured: `${capture.amount} ${capture.currency}`,
      requestId,
    });
    throw new AppError("Captured amount does not match the order", 400);
  }

  const credited = await paymentRepository.completeAndCredit(
    orderId,
    capture.captureId,
    requestId
  );

  if (credited) {
    logger.info("Credits purchased", { orderId, credits: payment.credits, requestId });
  }

  return { credits: await currentBalance(buyer.email), alreadyCompleted: !credited };
}
