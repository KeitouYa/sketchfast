import { auth } from "@clerk/nextjs/server";
import { api } from "@/lib/api";

// Server components call the backend directly rather than through the /api rewrite.
export const API_URL = process.env.API_URL ?? "http://localhost:8080";

/** Calls the backend from a server component, as the signed-in user when `signedIn` is set. */
export async function serverApi<T>(path: string, { signedIn = false } = {}): Promise<T> {
  const token = signedIn ? await (await auth()).getToken() : null;
  return api<T>(path, { baseUrl: API_URL, token });
}
