// Shapes of the backend API's responses.

export type ImageStatus = "PENDING" | "SAVING" | "SUCCEEDED" | "FAILED";

export interface ImageView {
  id: string;
  status: ImageStatus;
  prompt: string;
  style: string;
  /** Set once the image is SUCCEEDED. */
  url: string | null;
  failureCode: string | null;
  userName: string | null;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface CreditPackage {
  id: string;
  credits: number;
  /** In the currency's minor unit: 250 = $2.50. */
  priceCents: number;
  currency: string;
}

export interface StyleOption {
  id: string;
  label: string;
}

export interface SubmittedImage {
  id: string;
  status: ImageStatus;
  balance: number;
}

export interface OrderStatus {
  orderId: string;
  status: "PENDING" | "PAID" | "EXPIRED";
  credits: number;
}
