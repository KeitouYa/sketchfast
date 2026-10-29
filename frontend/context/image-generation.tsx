"use client";

import React, { createContext, useState } from "react";
import { useAuth } from "@clerk/nextjs";
import { useRouter } from "next/navigation";
import { toast } from "react-hot-toast";
import { useCredits } from "@/hooks/useCredits";
import { api, ApiError, errorMessage, imageFailureMessage } from "@/lib/api";
import type { ImageView, SubmittedImage } from "@/lib/types";

// Exported for hooks/useImageGeneration.ts
export interface ImageGenerationContextType {
  imagePrompt: string;
  setImagePrompt: (query: string) => void;
  loading: boolean;
  /** Shown while waiting for the image, e.g. "Generating…". */
  progress: string | null;
  generateImage: () => void;
  enhancing: boolean;
  enhancePrompt: () => void;
  style: string;
  setStyle: (style: string) => void;
}

export const ImageGenerationContext = createContext<ImageGenerationContextType | undefined>(undefined);

// Generation is asynchronous: submit, then poll until the backend says SUCCEEDED or FAILED.
// The backend fails and refunds anything unfinished after 5 minutes, so this never waits forever.
const POLL_START_MS = 1500;
const POLL_MAX_MS = 4000;
const POLL_GIVE_UP_MS = 6 * 60 * 1000;

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

export const ImageGenerationProvider = ({ children }: { children: React.ReactNode }) => {
  const [imagePrompt, setImagePrompt] = useState("");
  const [loading, setLoading] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [enhancing, setEnhancing] = useState(false);
  const [style, setStyle] = useState("none");
  const router = useRouter();
  const { isSignedIn, getToken } = useAuth();
  const { getUserCredits } = useCredits();

  const waitForImage = async (id: string): Promise<ImageView> => {
    const started = Date.now();
    let delay = POLL_START_MS;
    for (;;) {
      await sleep(delay);
      const image = await api<ImageView>(`/api/images/${id}`, { token: await getToken() });
      if (image.status === "SUCCEEDED" || image.status === "FAILED") return image;
      setProgress(image.status === "SAVING" ? "Saving your image…" : "Generating…");
      if (Date.now() - started > POLL_GIVE_UP_MS) return image;
      delay = Math.min(POLL_MAX_MS, Math.round(delay * 1.3));
    }
  };

  const generateImage = async () => {
    if (!isSignedIn) {
      toast.error("Sign in to generate images");
      return;
    }
    if (!imagePrompt.trim()) {
      toast.error("Please describe the image first");
      return;
    }

    setLoading(true);
    setProgress("Starting…");
    try {
      const submitted = await api<SubmittedImage>("/api/images", {
        body: { prompt: imagePrompt, style },
        token: await getToken(),
      });
      await getUserCredits();
      const image = await waitForImage(submitted.id);
      await getUserCredits();

      if (image.status === "SUCCEEDED") {
        toast.success("Your image is ready");
        router.push(`/dashboard/image/${image.id}`);
      } else if (image.status === "FAILED") {
        toast.error(imageFailureMessage(image.failureCode), { duration: 6000 });
      } else {
        toast("Still working on it. It will appear in your dashboard when it's done.");
      }
    } catch (err) {
      if (err instanceof ApiError && err.code === "INSUFFICIENT_CREDITS") {
        toast.error(errorMessage(err));
        router.push("/buy-credits");
      } else {
        toast.error(errorMessage(err));
      }
      await getUserCredits();
    } finally {
      setLoading(false);
      setProgress(null);
    }
  };

  // Rewrites the current idea into a detailed prompt the user can edit before generating
  const enhancePrompt = async () => {
    if (!isSignedIn) {
      toast.error("Sign in to enhance prompts");
      return;
    }
    if (!imagePrompt.trim()) {
      toast.error("Type an idea first");
      return;
    }

    setEnhancing(true);
    try {
      const { prompt } = await api<{ prompt: string }>("/api/prompts/enhance", {
        body: { idea: imagePrompt },
        token: await getToken(),
      });
      setImagePrompt(prompt);
    } catch (err) {
      toast.error(errorMessage(err));
    } finally {
      setEnhancing(false);
    }
  };

  return (
    <ImageGenerationContext.Provider
      value={{
        imagePrompt,
        setImagePrompt,
        loading,
        progress,
        generateImage,
        enhancing,
        enhancePrompt,
        style,
        setStyle,
      }}
    >
      {children}
    </ImageGenerationContext.Provider>
  );
};
