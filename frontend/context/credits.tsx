"use client";

import React, { createContext, useCallback, useEffect, useState } from "react";
import { useAuth } from "@clerk/nextjs";
import { toast } from "react-hot-toast";
import { api, errorMessage } from "@/lib/api";

// Exported for hooks/useCredits.ts
export interface CreditsContextType {
  credits: number;
  getUserCredits: () => Promise<void>;
}

export const CreditsContext = createContext<CreditsContextType | undefined>(undefined);

export const CreditsProvider = ({ children }: { children: React.ReactNode }) => {
  const [credits, setCredits] = useState(0);
  const { isSignedIn, getToken } = useAuth();

  // The backend opens the account (with the signup bonus) on the first read
  const getUserCredits = useCallback(async () => {
    if (!isSignedIn) return;
    try {
      const { balance } = await api<{ balance: number }>("/api/me/credits", { token: await getToken() });
      setCredits(balance);
    } catch (err) {
      toast.error(errorMessage(err));
    }
  }, [isSignedIn, getToken]);

  useEffect(() => {
    getUserCredits();
  }, [getUserCredits]);

  return (
    <CreditsContext.Provider value={{ credits, getUserCredits }}>{children}</CreditsContext.Provider>
  );
};
