"use server";
/**
 * IMAGE SERVICE — Business Logic Only
 */

import { auth, currentUser } from "@clerk/nextjs/server";
import Replicate from "replicate";
import { v2 as cloudinary } from "cloudinary";
import { nanoid } from "nanoid";

import db from "@/src/providers/db.provider";
import { imageRepository } from "@/src/repositories/image.repository";
import { creditRepository } from "@/src/repositories/credits.repository";
import { checkImageRateLimit } from "@/src/middlewares/rate-limit";
import { ImageGenerateSchema } from "@/src/schemas/image.schema";
import { logger } from "@/src/lib/logger";
import { ProviderError } from "@/src/lib/errors";
import { withTimeout, TIMEOUTS } from "@/src/lib/timeout";
import { trackCost } from "@/src/services/cost-tracking.service";
import { applyStyle } from "@/src/lib/style-presets";

const IMAGE_GENERATION_CREDIT_COST = 1;

// Metrics collection flag
const METRICS_ENABLED = process.env.METRICS_ENABLED === "true";

// Helper function for timing
function nowMs() {
  return Date.now();
}

// ---------------------------
// AI + Cloudinary Setup
// ---------------------------
const replicate = new Replicate({
  auth: process.env.REPLICATE_API_TOKEN,
});

cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

// =====================================================================
// generateImageAi()
// =====================================================================
export async function generateImageAi(
  imagePrompt: string,
  style: string = "none",
  requestId?: string
) {
  const t0 = nowMs();
  
  // Metrics tracking variables
  let status: "success" | "fail" = "success";
  let errorName: string | null = null;
  let dbMs = 0;
  let creditMs = 0;
  let replicateMs = 0;
  let downloadMs = 0;
  let uploadMs = 0;
  let saveMs = 0;
  
  const { isAuthenticated, redirectToSignIn } = await auth();
  if (!isAuthenticated) return redirectToSignIn({ returnBackUrl: "/" });

  const user = await currentUser();
  const userEmail = user?.emailAddresses[0]?.emailAddress;
  const userName = user?.fullName;

  if (!userEmail) {
    return { success: false, _id: null, credits: null };
  }

  // Validate before spending rate-limit quota or credits
  const parsed = ImageGenerateSchema.safeParse({ prompt: imagePrompt, style });
  if (!parsed.success) {
    return {
      success: false,
      _id: null,
      credits: null,
      invalidPrompt: true,
      error: parsed.error.issues[0].message,
    };
  }
  const prompt = parsed.data.prompt;

  // ⭐ Rate limit BEFORE touching DB / credits: 20 image generations per day per user
  const rateLimit = await checkImageRateLimit(userEmail);
  if (!rateLimit.success) {
    logger.warn("Image generation rate limit exceeded", {
      userEmail,
      limit: rateLimit.limit,
      reset: rateLimit.reset,
      requestId,
    });
    return {
      success: false,
      _id: null,
      credits: null,
      rateLimited: true,
      retryAfter: rateLimit.reset,
    };
  }

  // Set once the credit is taken, so a later failure knows to refund it
  let charged = false;

  try {
    const tDb = nowMs();
    await db();
    dbMs = nowMs() - tDb;

    logger.info("Image generation started", { userEmail, requestId });

    // STEP 1 — ATOMIC: Deduct Credit (prevents race conditions)
    const tCredit = nowMs();
    const deductionResult = await creditRepository.deductCreditsAtomic(
      userEmail,
      IMAGE_GENERATION_CREDIT_COST,
      requestId
    );
    creditMs = nowMs() - tCredit;

    if (!deductionResult.success) {
      logger.warn("Insufficient credits for image generation", {
        userEmail,
        remainingCredits: deductionResult.remainingCredits,
        requestId,
      });
      status = "fail";
      errorName = "InsufficientCreditsError";
      return {
        success: false,
        _id: null,
        credits: deductionResult.remainingCredits,
      };
    }

    charged = true;

    // STEP 2 — Generate Image (credit already deducted)
    logger.debug("Generating image with Replicate", { requestId });
    const input = {
      // The gallery keeps the user's own words; only the model sees the style text
      prompt: applyStyle(prompt, parsed.data.style),
      output_format: "png",
      output_quality: 80,
      aspect_ratio: "16:9",
    };

    // replicate.run() (SDK 1.4.0) came back null for this model after ~60s,
    // so create the prediction and wait on it ourselves.
    const tReplicate = nowMs();
    const prediction = await replicate.predictions.create({
      model: "black-forest-labs/flux-schnell",
      input,
    });
    let finished;
    try {
      finished = await withTimeout(
        replicate.wait(prediction),
        TIMEOUTS.IMAGE_GENERATION,
        "Replicate prediction timeout",
        requestId
      );
    } catch (err) {
      // Stop paying for a prediction nobody will read
      await replicate.predictions.cancel(prediction.id).catch(() => {});
      throw err;
    }
    replicateMs = nowMs() - tReplicate;

    if (finished.status !== "succeeded") {
      throw new ProviderError(
        `Replicate prediction ${finished.status}: ${String(finished.error ?? "no error message")}`
      );
    }
    const output = finished.output as string[] | string | null;
    const imageUrl = Array.isArray(output) ? output[0] : output;
    if (!imageUrl) throw new ProviderError("Replicate returned no image");

    //STEP 3 — Download Image from Replicate
    const tDownload = nowMs();
    const response = await fetch(imageUrl);
    if (!response.ok) {
      throw new ProviderError(`Failed to download generated image (${response.status})`);
    }
    const buffer = await response.arrayBuffer();
    const nodeBuffer = Buffer.from(buffer);
    downloadMs = nowMs() - tDownload;

    // STEP 4 — Upload to Cloudinary
    const tUpload = nowMs();
    const uploadResponse: any = await new Promise((resolve, reject) => {
      const uploadStream = cloudinary.uploader.upload_stream(
        { folder: "ai_images", public_id: nanoid() },
        (error, result) => (error ? reject(error) : resolve(result))
      );
      uploadStream.end(nodeBuffer);
    });
    uploadMs = nowMs() - tUpload;

    // STEP 5 — Save Image in DB
    const tSave = nowMs();
    const cloudinaryUrl = uploadResponse.secure_url;
    const image = await imageRepository.createImage({
      userEmail,
      userName,
      url: cloudinaryUrl,
      name: prompt,
    });
    saveMs = nowMs() - tSave;

    // Track cost
    try {
      await trackCost(
        userEmail,
        "image",
        "replicate",
        IMAGE_GENERATION_CREDIT_COST,
        requestId,
        { model: "flux-schnell" }
      );
    } catch (costErr) {
      logger.warn("Failed to track image cost", { err: costErr, requestId });
    }

    logger.info("Image generated successfully", {
      userEmail,
      imageId: String(image._id),
      remainingCredits: deductionResult.remainingCredits,
      requestId,
    });

    return {
      success: true,
      _id: String(image._id), // Convert ObjectId → string
      credits: deductionResult.remainingCredits, // updated balance from atomic operation
    };
  } catch (err) {
    status = "fail";
    errorName = err instanceof Error ? err.name : "UnknownError";
    logger.error("Image generation error", { err, userEmail, requestId });

    // The credit was taken before calling Replicate; give it back
    if (charged) {
      try {
        await creditRepository.refundCredits(
          userEmail,
          IMAGE_GENERATION_CREDIT_COST,
          requestId
        );
      } catch (refundErr) {
        logger.error("Failed to refund credit after image generation error", {
          err: refundErr,
          userEmail,
          requestId,
        });
      }
    }

    throw err instanceof Error ? err : new Error(String(err));
  } finally {
    if (METRICS_ENABLED) {
      logger.info("[metric] image.generation", {
        requestId,
        userEmail,
        status,
        errorName,
        promptLength: imagePrompt.length,
        totalMs: nowMs() - t0,
        dbMs,
        creditMs,
        replicateMs,
        downloadMs,
        uploadMs,
        saveMs,
      });
    }
  }
}

// =====================================================================
// getUserImagesFromDb()
// =====================================================================
export const getUserImagesFromDb = async (page: number, limit: number) => {
  try {
    await db();

    const user = await currentUser();
    const userEmail = user?.emailAddresses[0]?.emailAddress;

    if (!userEmail) throw new Error("User email missing");

    return await imageRepository.findUserImages(userEmail, page, limit);
  } catch (err: any) {
    throw new Error(String(err));
  }
};

// =====================================================================
// getImageFromDb()
// =====================================================================
export const getImageFromDb = async (_id: string) => {
  try {
    await db();
    return await imageRepository.findImageById(_id);
  } catch (err: any) {
    throw new Error(String(err));
  }
};
