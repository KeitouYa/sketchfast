import { z } from "zod";

// The buyer picks a package; the server looks up its price (src/lib/credit-packages.ts)
export const CreatePaypalOrderSchema = z.object({
  packageId: z.string().min(1, "Package is required"),
});
export type CreatePaypalOrderInput = z.infer<typeof CreatePaypalOrderSchema>;

// PayPal order ids are short alphanumeric strings; reject anything else
// before it is put into a PayPal API path.
export const CapturePaypalOrderSchema = z.object({
  orderId: z.string().regex(/^[A-Za-z0-9-]{1,64}$/, "Invalid order id"),
});
export type CapturePaypalOrderInput = z.infer<typeof CapturePaypalOrderSchema>;
