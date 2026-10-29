"use client";

import React from "react";
import { useImageGeneration } from "@/hooks/useImageGeneration";
import { useStyles } from "@/hooks/useStyles";
import { ChatInput } from "@/components/forms/chat-input";
import { cn } from "@/src/lib/utils";

export function GenerateImageInput() {
  const {
    generateImage,
    imagePrompt,
    setImagePrompt,
    loading,
    progress,
    enhancePrompt,
    enhancing,
    style,
    setStyle,
  } = useImageGeneration();
  const styles = useStyles();

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

      <div role="radiogroup" aria-label="Image style" className="flex flex-wrap gap-2">
        {styles.map((preset) => {
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
                  ? "border-transparent bg-primary text-primary-foreground shadow-sm"
                  : "bg-white/40 text-muted-foreground hover:text-foreground dark:bg-muted/30",
              )}
            >
              {preset.label}
            </button>
          );
        })}
      </div>

      {progress && (
        <p role="status" className="text-sm text-muted-foreground">
          {progress} This usually takes 5 to 15 seconds.
        </p>
      )}
    </div>
  );
}
