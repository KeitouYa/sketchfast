"use client";

import React, { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useAuth } from "@clerk/nextjs";
import { useSearchParams } from "next/navigation";
import Loader from "@/components/ui/loader";
import { useCredits } from "@/hooks/useCredits";
import { api, errorMessage } from "@/lib/api";
import type { OrderStatus } from "@/lib/types";

export default function PaymentSuccessPage() {
  return (
    <Suspense fallback={<Loader />}>
      <PaymentSuccess />
    </Suspense>
  );
}

const ATTEMPTS = 6;

/**
 * Stripe sends the buyer here after paying. Confirming asks the backend to credit the order now if Stripe's
 * webhook hasn't yet; both paths credit an order once, so it's safe to call while the webhook is in flight.
 */
function PaymentSuccess() {
  const orderId = useSearchParams().get("order");
  const { getToken } = useAuth();
  const { getUserCredits } = useCredits();
  const [order, setOrder] = useState<OrderStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    let cancelled = false;
    (async () => {
      try {
        for (let attempt = 1; attempt <= ATTEMPTS && !cancelled; attempt++) {
          const status = await api<OrderStatus>(`/api/payments/${orderId}/confirm`, {
            method: "POST",
            token: await getToken(),
          });
          if (cancelled) return;
          setOrder(status);
          if (status.status !== "PENDING") break;
          await new Promise((resolve) => setTimeout(resolve, 1500));
        }
        await getUserCredits();
      } catch (err) {
        if (!cancelled) setError(errorMessage(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [orderId, getToken, getUserCredits]);

  if (!orderId) return <Message title="No order to confirm" body="Start from the buy credits page." />;
  if (error) return <Message title="We couldn't confirm your payment" body={error} />;
  if (!order) return <Loader />;

  if (order.status === "PAID") {
    return <Message title="Payment received" body={`${order.credits} credits have been added to your account.`} />;
  }
  if (order.status === "EXPIRED") {
    return <Message title="This checkout expired" body="You weren't charged. Start a new purchase to buy credits." />;
  }
  return (
    <Message
      title="Payment is still processing"
      body="Your credits will appear as soon as Stripe confirms the payment. You can leave this page."
    />
  );
}

function Message({ title, body }: { title: string; body: string }) {
  return (
    <div className="max-w-md mx-auto py-16 px-4 text-center space-y-3">
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="text-muted-foreground">{body}</p>
      <div className="flex justify-center gap-4 text-sm">
        <Link href="/" className="underline">
          Create an image
        </Link>
        <Link href="/buy-credits" className="underline">
          Buy credits
        </Link>
      </div>
    </div>
  );
}
