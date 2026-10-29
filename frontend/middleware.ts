import { clerkMiddleware, createRouteMatcher } from "@clerk/nextjs/server";

// Pages that need a signed-in user. API routes are not listed: the backend checks the token itself.
const isProtectedPage = createRouteMatcher(["/dashboard(.*)", "/buy-credits(.*)"]);

export default clerkMiddleware(async (auth, req) => {
  if (isProtectedPage(req)) {
    await auth.protect();
  }
});

export const config = {
  matcher: [
    "/((?!_next|[^?]*\\.(?:html?|css|js(?!on)|jpe?g|png|gif|svg|ttf|woff2?|ico|csv|docx?|xlsx?|zip|webmanifest)).*)",
    "/(api|trpc)(.*)",
  ],
};
