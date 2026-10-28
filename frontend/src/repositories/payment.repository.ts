import mongoose from "mongoose";
import Payment, { PaymentStatus } from "@/src/models/payment.model";
import Credit from "@/src/models/credit.model";
import { logger } from "@/src/lib/logger";

export interface PaymentRecord {
  orderId: string;
  userEmail: string;
  packageId: string;
  credits: number;
  amount: string;
  currency: string;
  status: PaymentStatus;
  captureId?: string;
}

export const paymentRepository = {
  // ------------------------------------------------
  // Record a PayPal order the server just created
  // ------------------------------------------------
  async createPending(data: {
    orderId: string;
    userEmail: string;
    packageId: string;
    credits: number;
    amount: string;
    currency: string;
  }) {
    const payment = await Payment.create({
      ...data,
      status: PaymentStatus.CREATED,
    });
    return payment.toObject();
  },

  // ------------------------------------------------
  // Find an order, but only if it belongs to this user
  // ------------------------------------------------
  async findForUser(orderId: string, userEmail: string): Promise<PaymentRecord | null> {
    return Payment.findOne({ orderId, userEmail }).lean<PaymentRecord>();
  },

  // ------------------------------------------------
  // Mark the payment completed AND add its credits, in one transaction.
  // The CREATED → COMPLETED guard is what makes this idempotent: a second
  // capture of the same order finds nothing to update and adds no credits.
  // Returns whether credits were added.
  // ------------------------------------------------
  async completeAndCredit(
    orderId: string,
    captureId: string,
    requestId?: string
  ): Promise<boolean> {
    const session = await mongoose.startSession();
    try {
      let credited = false;

      await session.withTransaction(async () => {
        credited = false; // withTransaction may retry this callback

        const payment = await Payment.findOneAndUpdate(
          { orderId, status: PaymentStatus.CREATED },
          {
            $set: {
              status: PaymentStatus.COMPLETED,
              captureId,
              completedAt: new Date(),
            },
          },
          { new: true, session }
        );
        if (!payment) return;

        await Credit.findOneAndUpdate(
          { userEmail: payment.userEmail },
          { $inc: { credits: payment.credits, amount: Number(payment.amount) } },
          { upsert: true, new: true, session }
        );
        credited = true;
      });

      if (!credited) {
        logger.info("Payment already completed, no credits added", {
          orderId,
          requestId,
        });
      }
      return credited;
    } finally {
      await session.endSession();
    }
  },
};
