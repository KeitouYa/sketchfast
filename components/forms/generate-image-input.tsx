"use client";

import React from "react";
import { useImageGeneration } from "@/hooks/useImageGeneration";
import { ChatInput } from "@/components/forms/chat-input";
import { STYLE_PRESETS } from "@/src/lib/style-presets";
import { cn } from "@/src/lib/utils";

export function GenerateImageInput() {
  const {
    generateImage,
    imagePrompt,
    setImagePrompt,
    loading,
    enhancePrompt,
    enhancing,
    style,
    setStyle,
  } = useImageGeneration();

  return (
    <div className="space-y-3">
      <ChatInput
        value={imagePrompt}
        onChange={setImagePrompt}
        onSubmit={() => generateImage()}
        onEnhance={() => enhancePrompt()}
        enhancing={enhancing}
        placeholder="Describe the image you want to create..."
        loading={loading}
        size="lg"
        maxHeight={400}
      />

      {/* Style presets */}
      <div role="radiogroup" aria-label="Image style" className="flex flex-wrap gap-2">
        {STYLE_PRESETS.map((preset) => {
          const selected = style === preset.id;
          return (
            <button
              key={preset.id}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => setStyle(preset.id)}
              disabled={loading}
              className={cn(
                "rounded-full border px-3 py-1 text-sm transition-colors",
                selected
                  ? "border-transparent bg-gradient-to-r from-purple-600 to-indigo-500 text-white shadow-sm"
                  : "bg-white/40 text-muted-foreground hover:text-foreground dark:bg-muted/30",
              )}
            >
              {preset.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
