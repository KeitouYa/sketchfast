// The provider keeps an access token in module state, so each test loads a fresh copy.
type PaypalProvider = typeof import("@/src/providers/paypal.provider");

function loadProvider(): PaypalProvider {
  let provider: PaypalProvider | undefined;
  jest.isolateModules(() => {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    provider = require("@/src/providers/paypal.provider");
  });
  return provider!;
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

const tokenResponse = jsonResponse(200, { access_token: "token", expires_in: 32400 });

const completedOrder = {
  id: "ORDER1",
  status: "COMPLETED",
  purchase_units: [
    {
      payments: {
        captures: [
          {
            id: "CAPTURE1",
            status: "COMPLETED",
            amount: { value: "2.50", currency_code: "AUD" },
          },
        ],
      },
    },
  ],
};

describe("PayPal provider", () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock as unknown as typeof fetch;
    process.env.PAYPAL_ENV = "sandbox";
    process.env.NEXT_PUBLIC_PAYPAL_CLIENT_ID = "client-id";
    process.env.PAYPAL_CLIENT_SECRET = "client-secret";
  });

  it("creates an order with the server's amount", async () => {
    fetchMock
      .mockResolvedValueOnce(tokenResponse)
      .mockResolvedValueOnce(jsonResponse(201, { id: "ORDER1", status: "CREATED" }));
    const { createPaypalOrder } = loadProvider();

    const orderId = await createPaypalOrder({
      referenceId: "credits_30",
      amount: "2.50",
      currency: "AUD",
      description: "30 credits",
    });

    expect(orderId).toBe("ORDER1");
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe("https://api-m.sandbox.paypal.com/v2/checkout/orders");
    expect(JSON.parse(init.body).purchase_units[0].amount).toEqual({
      currency_code: "AUD",
      value: "2.50",
    });
  });

  it("captures an order with an idempotency key and reads the result", async () => {
    fetchMock
      .mockResolvedValueOnce(tokenResponse)
      .mockResolvedValueOnce(jsonResponse(201, completedOrder));
    const { capturePaypalOrder } = loadProvider();

    const capture = await capturePaypalOrder("ORDER1");

    expect(capture).toEqual({
      orderStatus: "COMPLETED",
      captureId: "CAPTURE1",
      captureStatus: "COMPLETED",
      amount: "2.50",
      currency: "AUD",
    });
    const [url, init] = fetchMock.mock.calls[1];
    expect(url).toBe("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER1/capture");
    expect(init.headers["PayPal-Request-Id"]).toBe("capture-ORDER1");
  });

  it("reads the order when it was already captured", async () => {
    fetchMock
      .mockResolvedValueOnce(tokenResponse)
      .mockResolvedValueOnce(
        jsonResponse(422, { details: [{ issue: "ORDER_ALREADY_CAPTURED" }] })
      )
      .mockResolvedValueOnce(jsonResponse(200, completedOrder));
    const { capturePaypalOrder } = loadProvider();

    const capture = await capturePaypalOrder("ORDER1");

    expect(capture.captureId).toBe("CAPTURE1");
    const [url, init] = fetchMock.mock.calls[2];
    expect(url).toBe("https://api-m.sandbox.paypal.com/v2/checkout/orders/ORDER1");
    expect(init.method).toBe("GET");
  });

  it("refuses to run without a PayPal environment", async () => {
    delete process.env.PAYPAL_ENV;
    const { capturePaypalOrder } = loadProvider();

    await expect(capturePaypalOrder("ORDER1")).rejects.toThrow("PAYPAL_ENV");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
