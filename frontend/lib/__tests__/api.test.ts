import { api, ApiError, errorMessage } from "@/lib/api";

const realFetch = global.fetch;

function respond(status: number, body: unknown) {
  global.fetch = jest.fn().mockResolvedValue({
    ok: status >= 200 && status < 300,
    status,
    statusText: "status " + status,
    json: async () => body,
  }) as unknown as typeof fetch;
}

afterEach(() => {
  global.fetch = realFetch;
});

describe("api", () => {
  it("sends JSON with the bearer token and returns the parsed body", async () => {
    respond(202, { id: "img1", status: "PENDING", balance: 19 });

    const result = await api("/api/images", { body: { prompt: "a cat" }, token: "tok" });

    expect(result).toEqual({ id: "img1", status: "PENDING", balance: 19 });
    expect(global.fetch).toHaveBeenCalledWith(
      "/api/images",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ prompt: "a cat" }),
        headers: { "Content-Type": "application/json", Authorization: "Bearer tok" },
      })
    );
  });

  it("turns problem details into an ApiError with the code and request id", async () => {
    respond(402, { status: 402, code: "INSUFFICIENT_CREDITS", detail: "Too low", requestId: "r1" });

    await expect(api("/api/images", { body: {} })).rejects.toMatchObject({
      status: 402,
      code: "INSUFFICIENT_CREDITS",
      requestId: "r1",
    });
  });
});

describe("errorMessage", () => {
  it("uses a friendly message per code", () => {
    expect(errorMessage(new ApiError(402, "INSUFFICIENT_CREDITS", "x"))).toMatch(/out of credits/);
  });

  it("shows the backend's detail for validation errors, since it names the field", () => {
    expect(errorMessage(new ApiError(400, "VALIDATION_FAILED", "Describe the image in 3 to 600 characters."))).toBe(
      "Describe the image in 3 to 600 characters."
    );
  });

  it("explains network failures", () => {
    expect(errorMessage(new TypeError("fetch failed"))).toMatch(/Can't reach the server/);
  });
});
