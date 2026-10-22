import mongoose from "mongoose";

export enum PaymentStatus {
  CREATED = "created", // PayPal order created, not captured yet
  COMPLETED = "completed", // captured and credits added
}

const PaymentSchema = new mongoose.Schema(
  {
    // PayPal order id; unique so one order can only ever be credited once
    orderId: { type: String, required: true, unique: true },
    userEmail: { type: String, required: true, index: true },
    packageId: { type: String, required: true },
    credits: { type: Number, required: true },
    amount: { type: String, required: true }, // e.g. "2.50", exactly as sent to PayPal
    currency: { type: String, required: true },
    status: {
      type: String,
      enum: Object.values(PaymentStatus),
      default: PaymentStatus.CREATED,
      required: true,
    },
    captureId: String,
    completedAt: Date,
  },
  { timestamps: true }
);

const Payment =
  mongoose.models.Payment || mongoose.model("Payment", PaymentSchema);

export default Payment;
