// Mock dependencies BEFORE imports
jest.mock("@clerk/nextjs/server", () => ({
  auth: jest.fn(async () => ({
    isAuthenticated: true,
    redirectToSignIn: jest.fn(),
  })),
  currentUser: jest.fn(async () => ({
    emailAddresses: [{ emailAddress: "test@example.com" }],
    fullName: "Test User",
  })),
}));
jest.mock("@/src/providers/db.provider", () => ({
  __esModule: true,
  default: jest.fn(() => Promise.resolve()),
}));
jest.mock("@/src/repositories/credits.repository");
jest.mock("@/src/repositories/image.repository");
jest.mock("@/src/middlewares/rate-limit", () => ({
  checkImageRateLimit: jest.fn(),
}));
jest.mock("@/src/services/cost-tracking.service", () => ({
  trackCost: jest.fn(),
}));
jest.mock("nanoid", () => ({ nanoid: () => "test-id" }));
// One shared Replicate client, created when image.service is loaded
jest.mock("replicate", () => {
  const instance = {
    predictions: { create: jest.fn(), cancel: jest.fn() },
    wait: jest.fn(),
  };
  return { __esModule: true, default: jest.fn(() => instance), instance };
});
jest.mock("cloudinary", () => ({
  __esModule: true,
  v2: { config: jest.fn(), uploader: { upload_stream: jest.fn() } },
}));

// Now import after mocks
import { generateImageAi } from "@/src/services/image.service";
import { creditRepository } from "@/src/repositories/credits.repository";
import { imageRepository } from "@/src/repositories/image.repository";
import { checkImageRateLimit } from "@/src/middlewares/rate-limit";
import { v2 as cloudinary } from "cloudinary";

const replicate = jest.requireMock("replicate").instance;
const PROMPT = "a red apple on a wooden table";

function uploadSucceeds() {
  (cloudinary.uploader.upload_stream as jest.Mock).mockImplementation(
    (_options: unknown, callback: (err?: Error, result?: unknown) => void) => ({
      end: () =>
        callback(undefined, {
          secure_url: "https://res.cloudinary.com/demo/test.png",
        }),
    })
  );
}

describe("Image Service", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (checkImageRateLimit as jest.Mock).mockResolvedValue({
      success: true,
      limit: 20,
      remaining: 19,
      reset: 0,
    });
    (creditRepository.deductCreditsAtomic as jest.Mock).mockResolvedValue({
      success: true,
      remainingCredits: 9,
    });
    (creditRepository.refundCredits as jest.Mock).mockResolvedValue(10);
    (imageRepository.createImage as jest.Mock).mockResolvedValue({ _id: "img1" });
    replicate.predictions.create.mockResolvedValue({ id: "pred1" });
    replicate.predictions.cancel.mockResolvedValue({});
    replicate.wait.mockResolvedValue({
      id: "pred1",
      status: "succeeded",
      output: ["https://replicate.delivery/test.png"],
    });
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      status: 200,
      arrayBuffer: async () => new ArrayBuffer(8),
    }) as unknown as typeof fetch;
    uploadSucceeds();
  });

  it("generates an image through the predictions API", async () => {
    const result = await generateImageAi(PROMPT, "req1");

    expect(result).toEqual({ success: true, _id: "img1", credits: 9 });
    expect(replicate.predictions.create).toHaveBeenCalledWith(
      expect.objectContaining({
        model: "black-forest-labs/flux-schnell",
        input: expect.objectContaining({ prompt: PROMPT }),
      })
    );
    expect(imageRepository.createImage).toHaveBeenCalledWith(
      expect.objectContaining({
        userEmail: "test@example.com",
        url: "https://res.cloudinary.com/demo/test.png",
        name: PROMPT,
      })
    );
    expect(creditRepository.refundCredits).not.toHaveBeenCalled();
  });

  it("rejects an invalid prompt before using quota or credits", async () => {
    const result = await generateImageAi("hi", "req1");

    expect(result).toMatchObject({ success: false, invalidPrompt: true });
    expect(checkImageRateLimit).not.toHaveBeenCalled();
    expect(creditRepository.deductCreditsAtomic).not.toHaveBeenCalled();
  });

  it("returns rateLimited without charging when the daily cap is hit", async () => {
    (checkImageRateLimit as jest.Mock).mockResolvedValue({
      success: false,
      limit: 20,
      remaining: 0,
      reset: 1234,
    });

    const result = await generateImageAi(PROMPT, "req1");

    expect(result).toMatchObject({ success: false, rateLimited: true, retryAfter: 1234 });
    expect(creditRepository.deductCreditsAtomic).not.toHaveBeenCalled();
  });

  it("does not call Replicate when credits are insufficient", async () => {
    (creditRepository.deductCreditsAtomic as jest.Mock).mockResolvedValue({
      success: false,
      remainingCredits: 0,
    });

    const result = await generateImageAi(PROMPT, "req1");

    expect(result).toEqual({ success: false, _id: null, credits: 0 });
    expect(replicate.predictions.create).not.toHaveBeenCalled();
    expect(creditRepository.refundCredits).not.toHaveBeenCalled();
  });

  it("refunds the credit when the prediction fails", async () => {
    replicate.wait.mockResolvedValue({
      id: "pred1",
      status: "failed",
      error: "model error",
      output: null,
    });

    await expect(generateImageAi(PROMPT, "req1")).rejects.toThrow(
      "Replicate prediction failed"
    );
    expect(creditRepository.refundCredits).toHaveBeenCalledWith(
      "test@example.com",
      1,
      "req1"
    );
    expect(imageRepository.createImage).not.toHaveBeenCalled();
  });

  it("cancels the prediction and refunds when waiting fails", async () => {
    replicate.wait.mockRejectedValue(
      new Error("Replicate prediction timeout (120000ms)")
    );

    await expect(generateImageAi(PROMPT, "req1")).rejects.toThrow("timeout");
    expect(replicate.predictions.cancel).toHaveBeenCalledWith("pred1");
    expect(creditRepository.refundCredits).toHaveBeenCalledWith(
      "test@example.com",
      1,
      "req1"
    );
  });

  it("refunds the credit when the upload fails", async () => {
    (cloudinary.uploader.upload_stream as jest.Mock).mockImplementation(
      (_options: unknown, callback: (err?: Error) => void) => ({
        end: () => callback(new Error("upload failed")),
      })
    );

    await expect(generateImageAi(PROMPT, "req1")).rejects.toThrow("upload failed");
    expect(creditRepository.refundCredits).toHaveBeenCalledWith(
      "test@example.com",
      1,
      "req1"
    );
  });
});
