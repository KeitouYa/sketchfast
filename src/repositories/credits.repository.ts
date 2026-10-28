import Credit from "@/src/models/credit.model";
import { logger } from "@/src/lib/logger";

// Free credits for a new user's first login
const INITIAL_FREE_CREDITS = 20;

function insufficientCredits(
  userEmail: string,
  requested: number,
  record: { credits?: number } | null,
  requestId?: string
) {
  const available = record?.credits ?? 0;
  logger.warn("Insufficient credits for deduction", {
    userEmail,
    requested,
    available,
    requestId,
  });
  return { success: false, remainingCredits: available };
}

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
  // Checking and deducting is one conditional update, so concurrent requests
  // can never overspend. An existing user costs a single round trip.
  // Returns { success: boolean, remainingCredits: number | null }
  // ------------------------------------------------
  async deductCreditsAtomic(
    userEmail: string,
    creditAmount: number,
    requestId?: string
  ): Promise<{ success: boolean; remainingCredits: number | null }> {
    // Only decrements when credits >= creditAmount
    const deduct = () =>
      Credit.findOneAndUpdate(
        { userEmail, credits: { $gte: creditAmount } },
        { $inc: { credits: -creditAmount } },
        { new: true, runValidators: true }
      ).lean();

    let result = await deduct();

    if (!result) {
      // Too few credits, or a new user who has no record yet
      const current = await Credit.findOne({ userEmail }).lean();
      if (current) {
        return insufficientCredits(userEmail, creditAmount, current as { credits?: number }, requestId);
      }

      // First request from a new user: create the record with the free credits, then deduct
      await this.ensureInitialCredits(userEmail);
      result = await deduct();
      if (!result) {
        // Their other concurrent first requests already spent the free credits
        const after = await Credit.findOne({ userEmail }).lean();
        return insufficientCredits(userEmail, creditAmount, after as { credits?: number } | null, requestId);
      }
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
  // A single upsert rather than find-then-create: concurrent first requests all
  // land on one record, and the unique index on userEmail rejects a second insert.
  // ------------------------------------------------
  async ensureInitialCredits(userEmail: string) {
    try {
      return await Credit.findOneAndUpdate(
        { userEmail },
        { $setOnInsert: { credits: INITIAL_FREE_CREDITS, amount: 0 } },
        { upsert: true, new: true }
      ).lean();
    } catch (err) {
      // Another request inserted the record first; it exists now
      if ((err as { code?: number }).code === 11000) {
        return Credit.findOne({ userEmail }).lean();
      }
      throw err;
    }
  },
};
