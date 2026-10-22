import Credit from "@/src/models/credit.model";
import { logger } from "@/src/lib/logger";

// Free credits for a new user's first login
const INITIAL_FREE_CREDITS = 20;

export const creditRepository = {
  // ------------------------------------------------
  // Fetch single user's credit record
  // ------------------------------------------------
  async findUserCredit(userEmail: string) {
    // findOne: find a single record
    // { userEmail }: query condition (match by email)
    // .lean(): return a plain JavaScript object (not a Mongoose document, better performance)
    const credit = await Credit.findOne({ userEmail }).lean();
    return credit ? JSON.parse(JSON.stringify(credit)) : null;
  },

  // ------------------------------------------------
  // ATOMIC: Deduct credits only if sufficient balance exists
  // Prevents race conditions by checking + deducting in single operation
  // Returns { success: boolean, remainingCredits: number | null }
  // ------------------------------------------------
  async deductCreditsAtomic(
    userEmail: string,
    creditAmount: number,
    requestId?: string
  ): Promise<{ success: boolean; remainingCredits: number | null }> {
    // Ensure record exists first
    await this.ensureInitialCredits(userEmail);

    // Atomic operation: only decrement if credits >= creditAmount
    const result = await Credit.findOneAndUpdate(
      {
        userEmail,
        credits: { $gte: creditAmount }, // Only match if sufficient credits
      },
      {
        $inc: { credits: -creditAmount },
      },
      {
        new: true,
        runValidators: true,
      }
    ).lean();

    if (!result) {
      // Insufficient credits or user not found
      const current = await Credit.findOne({ userEmail }).lean();
      const currentCredits = (current as any)?.credits ?? 0;
      logger.warn("Insufficient credits for deduction", {
        userEmail,
        requested: creditAmount,
        available: currentCredits,
        requestId,
      });
      return {
        success: false,
        remainingCredits: currentCredits ?? null,
      };
    }

    const resultCredits = (result as any)?.credits ?? 0;
    logger.info("Credits deducted successfully", {
      userEmail,
      deducted: creditAmount,
      remaining: resultCredits,
      requestId,
    });

    return {
      success: true,
      remainingCredits: resultCredits,
    };
  },

  // ------------------------------------------------
  // Give credits back after a failed generation
  // Returns the new balance (null if the user has no record)
  // ------------------------------------------------
  async refundCredits(
    userEmail: string,
    creditAmount: number,
    requestId?: string
  ): Promise<number | null> {
    const result = await Credit.findOneAndUpdate(
      { userEmail },
      { $inc: { credits: creditAmount } },
      { new: true }
    ).lean();

    const remaining = (result as any)?.credits ?? null;
    logger.info("Credits refunded", {
      userEmail,
      refunded: creditAmount,
      remaining,
      requestId,
    });
    return remaining;
  },

  // ------------------------------------------------
  // Ensure record exists (new users get INITIAL_FREE_CREDITS)
  // ------------------------------------------------
  async ensureInitialCredits(userEmail: string) {
    const existing = await Credit.findOne({ userEmail });
    if (!existing) {
      return await Credit.create({
        userEmail,
        amount: 0,
        credits: INITIAL_FREE_CREDITS,
      });
    }

    return existing;
  },
};
