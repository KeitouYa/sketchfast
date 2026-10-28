import mongoose from "mongoose";

const CreditSchema = new mongoose.Schema(
  {
    // Unique: one record per user, even when a new user's first requests arrive at once
    userEmail: { type: String, required: true, unique: true },
    credits: Number,
    amount: Number,
  },
  { timestamps: true }
);

const Credit = mongoose.models.Credit || mongoose.model("Credit", CreditSchema);

export default Credit;
