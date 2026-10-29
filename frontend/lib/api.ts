// Client for the backend REST API (/api/**). The browser calls it same-origin and Next.js forwards the
// request to the backend at API_URL (see next.config.ts), so the frontend works with any backend that
// implements the same API. Errors come back as problem details with a stable "code".

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public requestId?: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

type ProblemDetails = {
  status?: number;
  title?: string;
  detail?: string;
  code?: string;
  requestId?: string;
};

export type ApiOptions = {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  /** Clerk session token. In the browser get a fresh one per request: they expire after 60 seconds. */
  token?: string | null;
  /** Absolute backend URL, for calls made on the server. */
  baseUrl?: string;
};

export async function api<T>(path: string, options: ApiOptions = {}): Promise<T> {
  const { body, token, baseUrl = "" } = options;
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;

  const res = await fetch(`${baseUrl}${path}`, {
    method: options.method ?? (body !== undefined ? "POST" : "GET"),
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });

  if (!res.ok) {
    let problem: ProblemDetails = {};
    try {
      problem = await res.json();
    } catch {
      // not JSON (e.g. the backend is down and a proxy answered)
    }
    throw new ApiError(
      res.status,
      problem.code ?? (res.status >= 500 ? "INTERNAL_ERROR" : "UNKNOWN"),
      problem.detail ?? problem.title ?? res.statusText,
      problem.requestId
    );
  }
  return (await res.json()) as T;
}

// What the user sees for each error code. The backend's own detail is shown for validation errors,
// because it says which field to fix.
const MESSAGES: Record<string, string> = {
  INSUFFICIENT_CREDITS: "You're out of credits. Buy more to keep going.",
  UNAUTHENTICATED: "Please sign in again.",
  NOT_FOUND: "We couldn't find that.",
  PROVIDER_UNAVAILABLE: "The AI service is busy right now. Nothing was charged; try again in a moment.",
  PAYMENT_PROVIDER_ERROR: "Payments are unavailable right now. Please try again later.",
  INTERNAL_ERROR: "Something went wrong on our side. Please try again.",
};

// Why an image ended FAILED (ImageView.failureCode). Every failed image has been refunded.
const IMAGE_FAILURES: Record<string, string> = {
  CONTENT_FILTERED:
    "The model's safety filter flagged this description. It sometimes flags harmless ones, so try rewording it.",
  TIMEOUT: "The image took too long to generate.",
  UPLOAD_FAILED: "The image was generated but couldn't be saved.",
};

export function imageFailureMessage(failureCode: string | null): string {
  const reason = (failureCode && IMAGE_FAILURES[failureCode]) || "The image couldn't be generated.";
  return `${reason} Your credit has been refunded.`;
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === "VALIDATION_FAILED" && err.message) return err.message;
    return MESSAGES[err.code] ?? (err.message || MESSAGES.INTERNAL_ERROR);
  }
  return "Can't reach the server. Check your connection and try again.";
}
