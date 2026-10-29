"use client";
import React from "react";
import Link from "next/link";
import { saveAs } from "file-saver";
import toast from "react-hot-toast";
import { CloudDownload, SquareArrowOutUpRight, View } from "lucide-react";
import { Button } from "../ui/button";
import type { ImageView } from "@/lib/types";

/** A short, safe file name from the description: "a red bicycle at dusk" -> "a-red-bicycle-at-dusk.webp". */
function fileName(prompt: string) {
  const slug = prompt
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40);
  return `${slug || "image"}.webp`;
}

export default function ImageEditButtons({ image }: { image: ImageView }) {
  const handleDownload = () => {
    if (image.url) saveAs(image.url, fileName(image.prompt));
  };

  const handleShare = async () => {
    const shareUrl = `${window.location.origin}/image/${image.id}`;
    try {
      await navigator.clipboard.writeText(shareUrl);
      toast.success("Link copied. Anyone with it can view this image.");
    } catch {
      toast.error(`Couldn't copy automatically. The link is ${shareUrl}`);
    }
  };

  return (
    <div className="flex justify-center gap-4">
      <Button onClick={handleDownload} className="h-16 w-20 lg:h-16 lg:w-20 flex flex-col items-center justify-center">
        <CloudDownload className="h-6 w-6 mb-1" />
        <span className="text-sm">Download</span>
      </Button>

      <Button onClick={handleShare} className="h-16 w-20 lg:h-16 lg:w-20 flex flex-col items-center justify-center">
        <SquareArrowOutUpRight className="h-6 w-6 mb-1" />
        <span className="text-sm">Share</span>
      </Button>

      <Link href={`/image/${image.id}`}>
        <Button className="h-16 w-20 lg:h-16 lg:w-20 flex flex-col items-center justify-center">
          <View className="h-6 w-6 mb-1" />
          <span className="text-sm">View</span>
        </Button>
      </Link>
    </div>
  );
}
