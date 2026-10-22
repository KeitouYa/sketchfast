// Mock dependencies BEFORE imports
jest.mock("@/src/providers/db.provider", () => ({
  __esModule: true,
  default: jest.fn(() => Promise.resolve()),
}));
jest.mock("@/src/providers/paypal.provider", () => ({
  createPaypalOrder: jest.fn(),
  capturePaypalOrder: jest.fn(),
}));
jest.mock("@/src/repositories/payment.repository", () => ({
  paymentRepository: {
    createPending: jest.fn(),
    findForUser: jest.fn(),
    completeAndCredit: jest.fn(),
  },
}));
jest.mock("@/src/repositories/credits.repository", () => ({
  creditRepository: { findUserCredit: jest.fn() },
}));

// Now import after mocks
import {
  startCreditPurchase,
  completeCreditPurchase,
} from "@/src/services/paypal.service";
import {
  createPaypalOrder,
  capturePaypalOrder,
} from "@/src/providers/paypal.provider";
import { paymentRepository } from "@/src/repositories/payment.repository";
import { creditRepository } from "@/src/repositories/credits.repository";
import { PaymentStatus } from "@/src/models/payment.model";

const buyer = { email: "buyer@example.com", clerkUserId: "user_123" };

const pendingPayment = {
  orderId: "ORDER1",
  userEmail: buyer.email,
  packageId: "credits_30",
  credits: 30,
  amount: "2.50",
  currency: "AUD",
  status: PaymentStatus.CREATED,
};

const completedCapture = {
  orderStatus: "COMPLETED",
  captureId: "CAPTURE1",
  captureStatus: "COMPLETED",
  amount: "2.50",
  currency: "AUD",
};

describe("PayPal purchase service", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (creditRepository.findUserCredit as jest.Mock).mockResolvedValue({ credits: 50 });
  });

  describe("startCreditPurchase", () => {
    it("prices the order from the server's package table", async () => {
      (createPaypalOrder as jest.Mock).mockResolvedValue("ORDER1");

      const result = await startCreditPurchase(buyer, "credits_30", "req1");

      expect(result).toEqual({ orderId: "ORDER1" });
      expect(createPaypalOrder).toHaveBeenCalledWith(
        expect.objectContaining({ amount: "2.50", currency: "AUD" }),
        "req1"
      );
      expect(paymentRepository.createPending).toHaveBeenCalledWith(
        expect.objectContaining({
          orderId: "ORDER1",
          userEmail: buyer.email,
          credits: 30,
          amount: "2.50",
        })
      );
    });

    it("rejects an unknown package without calling PayPal", async () => {
      await expect(startCreditPurchase(buyer, "credits_9999", "req1")).rejects.toThrow(
        "Unknown credit package"
      );
      expect(createPaypalOrder).not.toHaveBeenCalled();
    });
  });

  describe("completeCreditPurchase", () => {
    it("captures on the server and credits the buyer once", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue(pendingPayment);
      (capturePaypalOrder as jest.Mock).mockResolvedValue(completedCapture);
      (paymentRepository.completeAndCredit as jest.Mock).mockResolvedValue(true);

      const result = await completeCreditPurchase(buyer, "ORDER1", "req1");

      expect(result).toEqual({ credits: 50, alreadyCompleted: false });
      expect(paymentRepository.findForUser).toHaveBeenCalledWith("ORDER1", buyer.email);
      expect(paymentRepository.completeAndCredit).toHaveBeenCalledWith(
        "ORDER1",
        "CAPTURE1",
        "req1"
      );
    });

    it("refuses an order that belongs to someone else", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue(null);

      await expect(completeCreditPurchase(buyer, "ORDER1", "req1")).rejects.toMatchObject({
        status: 404,
      });
      expect(capturePaypalOrder).not.toHaveBeenCalled();
    });

    it("does not capture or credit an order that is already completed", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue({
        ...pendingPayment,
        status: PaymentStatus.COMPLETED,
      });

      const result = await completeCreditPurchase(buyer, "ORDER1", "req1");

      expect(result).toEqual({ credits: 50, alreadyCompleted: true });
      expect(capturePaypalOrder).not.toHaveBeenCalled();
      expect(paymentRepository.completeAndCredit).not.toHaveBeenCalled();
    });

    it("adds no credits when a concurrent capture already completed the order", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue(pendingPayment);
      (capturePaypalOrder as jest.Mock).mockResolvedValue(completedCapture);
      (paymentRepository.completeAndCredit as jest.Mock).mockResolvedValue(false);

      const result = await completeCreditPurchase(buyer, "ORDER1", "req1");

      expect(result).toEqual({ credits: 50, alreadyCompleted: true });
    });

    it("does not credit while PayPal reports the payment as pending", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue(pendingPayment);
      (capturePaypalOrder as jest.Mock).mockResolvedValue({
        ...completedCapture,
        captureStatus: "PENDING",
      });

      await expect(completeCreditPurchase(buyer, "ORDER1", "req1")).rejects.toMatchObject({
        status: 409,
      });
      expect(paymentRepository.completeAndCredit).not.toHaveBeenCalled();
    });

    it("does not credit when the captured amount differs from the order", async () => {
      (paymentRepository.findForUser as jest.Mock).mockResolvedValue(pendingPayment);
      (capturePaypalOrder as jest.Mock).mockResolvedValue({
        ...completedCapture,
        amount: "0.01",
      });

      await expect(completeCreditPurchase(buyer, "ORDER1", "req1")).rejects.toMatchObject({
        status: 400,
      });
      expect(paymentRepository.completeAndCredit).not.toHaveBeenCalled();
    });
  });
});
