/**
 * PAYPAL PROVIDER (server only)
 * ----------------------------------------------------
 * Thin client for the PayPal Orders v2 REST API.
 * - Authenticates with client credentials; the secret never reaches the browser
 * - PAYPAL_ENV picks sandbox or live and must match the client id / secret
 */

import { logger } from "@/src/lib/logger";
import { ProviderError } from "@/src/lib/errors";
import { withTimeout, TIMEOUTS } from "@/src/lib/timeout";

const API_BASE = {
  sandbox: "https://api-m.sandbox.paypal.com",
  live: "https://api-m.paypal.com",
} as const;

export interface PaypalCapture {
  orderStatus: string;
  captureId: string | null;
  captureStatus: string | null;
  amount: string | null;
  currency: string | null;
}

// Read at call time so missing PayPal config only breaks payments,
// not every module that imports this file.
function getConfig() {
  const env = process.env.PAYPAL_ENV;
  const clientId = process.env.NEXT_PUBLIC_PAYPAL_CLIENT_ID;
  const secret = process.env.PAYPAL_CLIENT_SECRET;

  if (env !== "sandbox" && env !== "live") {
    throw new Error("PAYPAL_ENV must be 'sandbox' or 'live'");
  }
  if (!clientId || !secret) {
    throw new Error(
      "NEXT_PUBLIC_PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET must be set"
    );
  }
  return { baseUrl: API_BASE[env], clientId, secret };
}

// Reuse the access token until shortly before it expires
let cachedToken: { value: string; expiresAt: number } | null = null;

async function getAccessToken(requestId?: string): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now()) {
    return cachedToken.value;
  }

  const { baseUrl, clientId, secret } = getConfig();
  const basic = Buffer.from(`${clientId}:${secret}`).toString("base64");

  const res = await withTimeout(
    fetch(`${baseUrl}/v1/oauth2/token`, {
      method: "POST",
      headers: {
        Authorization: `Basic ${basic}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: "grant_type=client_credentials",
    }),
    TIMEOUTS.EXTERNAL_API,
    "PayPal token request timeout",
    requestId
  );
  const body = await res.json().catch(() => ({}));

  if (!res.ok || !body.access_token) {
    logger.error("PayPal token request failed", { status: res.status, requestId });
    throw new ProviderError("PayPal authentication failed");
  }

  cachedToken = {
    value: body.access_token,
    expiresAt: Date.now() + (Number(body.expires_in) - 60) * 1000,
  };
  return body.access_token;
}

async function paypalRequest(path: string, init: RequestInit, requestId?: string) {
  const { baseUrl } = getConfig();
  const token = await getAccessToken(requestId);

  const res = await withTimeout(
    fetch(`${baseUrl}${path}`, {
      ...init,
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        ...init.headers,
      },
    }),
    TIMEOUTS.EXTERNAL_API,
    "PayPal request timeout",
    requestId
  );
  const body = await res.json().catch(() => ({}));
  return { status: res.status, body };
}

// =====================================================================
// createPaypalOrder() — returns the PayPal order id
// =====================================================================
export async function createPaypalOrder(
  order: { referenceId: string; amount: string; currency: string; description: string },
  requestId?: string
): Promise<string> {
  const { status, body } = await paypalRequest(
    "/v2/checkout/orders",
    {
      method: "POST",
      body: JSON.stringify({
        intent: "CAPTURE",
        purchase_units: [
          {
            reference_id: order.referenceId,
            custom_id: order.referenceId,
            description: order.description,
            amount: { currency_code: order.currency, value: order.amount },
          },
        ],
      }),
    },
    requestId
  );

  if (status >= 300 || !body.id) {
    logger.error("PayPal create order failed", { status, debugId: body.debug_id, requestId });
    throw new ProviderError("Failed to create PayPal order");
  }
  return body.id;
}

// =====================================================================
// capturePaypalOrder() — capture an approved order and report the result
// =====================================================================
export async function capturePaypalOrder(
  orderId: string,
  requestId?: string
): Promise<PaypalCapture> {
  const orderPath = `/v2/checkout/orders/${encodeURIComponent(orderId)}`;

  // PayPal-Request-Id makes a retried capture of the same order idempotent on PayPal's side
  let { status, body } = await paypalRequest(
    `${orderPath}/capture`,
    { method: "POST", headers: { "PayPal-Request-Id": `capture-${orderId}` } },
    requestId
  );

  // Captured by an earlier attempt (e.g. our request timed out after PayPal succeeded):
  // read the order instead of failing
  const alreadyCaptured =
    status === 422 &&
    Array.isArray(body.details) &&
    body.details.some((d: { issue?: string }) => d.issue === "ORDER_ALREADY_CAPTURED");
  if (alreadyCaptured) {
    ({ status, body } = await paypalRequest(orderPath, { method: "GET" }, requestId));
  }

  if (status >= 300) {
    logger.error("PayPal capture failed", { status, orderId, debugId: body.debug_id, requestId });
    throw new ProviderError("Failed to capture PayPal order");
  }

  const capture = body.purchase_units?.[0]?.payments?.captures?.[0];
  return {
    orderStatus: body.status,
    captureId: capture?.id ?? null,
    captureStatus: capture?.status ?? null,
    amount: capture?.amount?.value ?? null,
    currency: capture?.amount?.currency_code ?? null,
  };
}
