"use client";

import React, { createContext, useState } from "react";
import { generateImageAi } from "@/src/services/image.service";
import { toast } from "react-hot-toast";
import { useRouter } from "next/navigation";
import { useCredits } from "@/hooks/useCredits";
import type { StyleId } from "@/src/lib/style-presets";

// --- Context Type (exported for hooks/useImageGeneration.ts) ---
export interface ImageGenerationContextType {
  imagePrompt: string;
  setImagePrompt: (query: string) => void;
  loading: boolean;
  generateImage: () => void;
  enhancing: boolean;
  enhancePrompt: () => void;
  style: StyleId;
  setStyle: (style: StyleId) => void;
}

export const ImageGenerationContext = createContext<
  ImageGenerationContextType | undefined
>(undefined);

// --- Provider ---
export const ImageGenerationProvider = ({
  children,
}: {
  children: React.ReactNode;
}) => {
  const [imagePrompt, setImagePrompt] = useState("");
  const [loading, setLoading] = useState(false);
  const [enhancing, setEnhancing] = useState(false);
  const [style, setStyle] = useState<StyleId>("none");
  const router = useRouter();

  // Get getUserCredits from CreditsContext to refresh after generation
  const { getUserCredits } = useCredits();

  const generateImage = async () => {
    if (!imagePrompt.trim()) {
      toast.error("Please enter a prompt");
      return;
    }

    setLoading(true);

    try {
      const result = await generateImageAi(imagePrompt, style);
      const { success, _id, rateLimited, invalidPrompt, error } = result as {
        success: boolean;
        _id: string | null;
        rateLimited?: boolean;
        invalidPrompt?: boolean;
        error?: string;
      };

      if (success) {
        await getUserCredits();
        toast.success("🎉 Image generated!");
        router.push(`/dashboard/image/${_id}`);
      } else if (rateLimited) {
        toast.error("Daily limit reached (20 images/day). Try again tomorrow.");
      } else if (invalidPrompt) {
        toast.error(error || "Please check your prompt.");
      } else {
        await getUserCredits();
        toast.error("Insufficient credits. Please buy more credits.");
        router.push("/buy-credits");
      }
    } catch (err: any) {
      if (err?.digest === "NEXT_REDIRECT") throw err;
      console.error("Error generating image:", err);
      toast.error("Failed to generate image. Please try again later.");
    } finally {
      setLoading(false);
    }
  };

  // Rewrite the current idea into a detailed prompt the user can edit before generating
  const enhancePrompt = async () => {
    if (!imagePrompt.trim()) {
      toast.error("Type an idea first");
      return;
    }

    setEnhancing(true);
    try {
      const res = await fetch("/api/prompt/optimize", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ prompt: imagePrompt }),
      });
      if (res.status === 401) {
        toast.error("Please sign in to enhance prompts");
        return;
      }
      const body = await res.json();
      if (!body.success) {
        toast.error(body.error || "Could not enhance the prompt");
        return;
      }
      setImagePrompt(body.data.prompt);
    } catch {
      toast.error("Could not enhance the prompt. Please try again.");
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

// Hook moved to hooks/useImageGeneration.ts
