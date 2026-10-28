import { Ratelimit } from "@upstash/ratelimit";
import { Redis } from "@upstash/redis";

const redis = Redis.fromEnv();

// Image generation rate limit: 20 requests/day per user
// Caps Replicate API costs and prevents single-user abuse
export const rateLimiter = new Ratelimit({
  redis,
  limiter: Ratelimit.fixedWindow(20, "1 d"),
  analytics: true,
});

// Check whether this user can generate another image today.
// Returns { success: boolean, limit, remaining, reset } from Upstash.
export async function checkImageRateLimit(userEmail: string) {
  return await rateLimiter.limit(`image_${userEmail}`);
}
