import { auth, currentUser } from "@clerk/nextjs/server";

/**
 * The signed-in user's Clerk id and the email the app keys its data by.
 * Returns null when nobody is signed in.
 */
export async function getSignedInUser(): Promise<{
  clerkUserId: string;
  email: string;
} | null> {
  const { userId } = await auth();
  if (!userId) return null;

  const user = await currentUser();
  // Same address the services use as the user key (credits, images)
  const email = user?.emailAddresses[0]?.emailAddress;

  return email ? { clerkUserId: userId, email } : null;
}
