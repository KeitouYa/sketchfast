// Credit packages sold on /buy-credits.
// The server prices PayPal orders from this table; the browser only sends a package id.
export const CREDIT_CURRENCY = "AUD";

export const CREDIT_PACKAGES = [
  { id: "credits_10", credits: 10, price: "1.00" },
  { id: "credits_30", credits: 30, price: "2.50" },
  { id: "credits_60", credits: 60, price: "4.00" },
] as const;

export type CreditPackage = (typeof CREDIT_PACKAGES)[number];

export function findCreditPackage(id: string): CreditPackage | undefined {
  return CREDIT_PACKAGES.find((pkg) => pkg.id === id);
}
