"use client";
import React from "react";
import { PayPalButtons, usePayPalScriptReducer } from "@paypal/react-paypal-js";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import toast from "react-hot-toast";

import Loader from "@/components/ui/loader";
import { useCredits } from "@/hooks/useCredits";
import PaypalProvider from "@/context/paypal";
import { CREDIT_PACKAGES, type CreditPackage } from "@/src/lib/credit-packages";

export default function BuyCreditsPage() {
  return (
    <PaypalProvider>
      <BuyCredits />
    </PaypalProvider>
  );
}

function BuyCredits() {
  const [{ isPending }] = usePayPalScriptReducer();

  // 默认选项是第一个
  const [selected, setSelected] = React.useState<CreditPackage>(CREDIT_PACKAGES[0]);

  const { credits, getUserCredits } = useCredits();

  // The server prices the order; the browser only says which package
  const createOrder = async () => {
    const res = await fetch("/api/paypal/orders", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ packageId: selected.id }),
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.error || "Failed to create order");
    return data.data.orderId as string;
  };

  // The server captures the payment and adds the credits
  const handleApprove = async (orderId: string) => {
    try {
      const res = await fetch(`/api/paypal/orders/${orderId}/capture`, {
        method: "POST",
      });
      const data = await res.json();
      if (!data.success) throw new Error(data.error || "Failed to capture order");

      await getUserCredits();
      toast.success(`Successfully purchased ${selected.credits} credits.`);
    } catch (err) {
      console.error("err => ", err);
      toast.error("Could not confirm the payment. Please try again.");
    }
  };

  const handleError = (err: unknown) => {
    console.error("err => ", err);
    toast.error("An error occurred. Please try again.");
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="container max-w-7xl mx-auto p-4 md:p-8 flex items-center justify-center min-h-[calc(100dvh-100px)]">
      <Card className="w-full max-w-md mx-auto">
        <CardHeader>
          <CardTitle className="text-2xl font-bold text-center">
            Buy Credits
          </CardTitle>
          <p className="text-center">
            You currently have{" "}
            <span className="font-bold text-primary">{credits}</span> credits.
          </p>
        </CardHeader>

        <CardContent>
          <div className="flex flex-col gap-3 mb-8">
            {/* 读取积分选项数据 */}
            {CREDIT_PACKAGES.map((option) => (
              <Button
                key={option.id}
                onClick={() => setSelected(option)}
                variant={selected.id === option.id ? "default" : "outline"}
                className={`h-14 text-lg transition-all ${selected.id === option.id ? 'ring-2 ring-primary ring-offset-2 ring-offset-background' : 'hover:border-primary/50'}`}
              >
                {option.credits} Credits - ${option.price}
              </Button>
            ))}
          </div>

          <div className="relative z-0">
            {/* key: re-mount the buttons so createOrder sees the new package */}
            <PayPalButtons
              key={selected.id}
              createOrder={createOrder}
              onApprove={async (data) => {
                await handleApprove(data.orderID);
              }}
              onError={handleError}
            />
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
