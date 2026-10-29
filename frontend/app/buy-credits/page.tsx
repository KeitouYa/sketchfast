"use client";

import React, { Suspense, useEffect, useState } from "react";
import { useAuth } from "@clerk/nextjs";
import { useSearchParams } from "next/navigation";
import toast from "react-hot-toast";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import Loader from "@/components/ui/loader";
import { useCredits } from "@/hooks/useCredits";
import { api, errorMessage } from "@/lib/api";
import type { CreditPackage } from "@/lib/types";

function formatPrice(pkg: CreditPackage) {
  return new Intl.NumberFormat("en-US", { style: "currency", currency: pkg.currency.toUpperCase() }).format(
    pkg.priceCents / 100
  );
}

export default function BuyCreditsPage() {
  return (
    <Suspense fallback={<Loader />}>
      <BuyCredits />
    </Suspense>
  );
}

/** Prices come from the backend; paying happens on Stripe's own page. */
function BuyCredits() {
  const [packages, setPackages] = useState<CreditPackage[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [redirecting, setRedirecting] = useState(false);
  const { credits } = useCredits();
  const { getToken } = useAuth();
  const canceled = useSearchParams().get("canceled") === "1";

  useEffect(() => {
    api<CreditPackage[]>("/api/credit-packages")
      .then((list) => {
        setPackages(list);
        setSelected(list[0]?.id ?? null);
      })
      .catch((err) => toast.error(errorMessage(err)));
  }, []);

  const checkout = async () => {
    if (!selected) return;
    setRedirecting(true);
    try {
      const { checkoutUrl } = await api<{ orderId: string; checkoutUrl: string }>("/api/payments/checkout", {
        body: { packageId: selected },
        token: await getToken(),
      });
      window.location.assign(checkoutUrl);
    } catch (err) {
      toast.error(errorMessage(err));
      setRedirecting(false);
    }
  };

  if (!packages) return <Loader />;

  return (
    <div className="container max-w-7xl mx-auto p-4 md:p-8 flex items-center justify-center min-h-[calc(100dvh-100px)]">
      <Card className="w-full max-w-md mx-auto">
        <CardHeader>
          <CardTitle className="text-2xl font-bold text-center">Buy credits</CardTitle>
          <p className="text-center">
            You have <span className="font-bold text-primary">{credits}</span> credits. Each image costs 1.
          </p>
          {canceled && (
            <p role="status" className="text-center text-sm text-muted-foreground">
              Payment canceled. You weren&apos;t charged.
            </p>
          )}
        </CardHeader>

        <CardContent>
          <div role="radiogroup" aria-label="Credit package" className="flex flex-col gap-3 mb-8">
            {packages.map((pkg) => (
              <Button
                key={pkg.id}
                role="radio"
                aria-checked={selected === pkg.id}
                onClick={() => setSelected(pkg.id)}
                variant={selected === pkg.id ? "default" : "outline"}
                className={`h-14 text-lg transition-all ${
                  selected === pkg.id ? "ring-2 ring-primary ring-offset-2 ring-offset-background" : "hover:border-primary/50"
                }`}
              >
                {pkg.credits} credits · {formatPrice(pkg)}
              </Button>
            ))}
          </div>

          <Button onClick={checkout} disabled={!selected || redirecting} className="w-full h-12 text-base">
            {redirecting ? "Opening secure checkout…" : "Continue to payment"}
          </Button>
          <p className="mt-3 text-center text-xs text-muted-foreground">Payments are processed by Stripe.</p>
        </CardContent>
      </Card>
    </div>
  );
}
