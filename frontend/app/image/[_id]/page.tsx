import React from "react";
import Image from "next/image";
import { notFound } from "next/navigation";
import ImageCard from "@/components/image/image-card";
import { ApiError } from "@/lib/api";
import { serverApi } from "@/lib/server-api";
import type { ImageView } from "@/lib/types";

interface ImagePageProps {
  // Next 15 passes params as a Promise
  params: Promise<{ _id: string }>;
}

/** The public share page. The backend only returns finished images to people who don't own them. */
export default async function SharedImagePage({ params }: ImagePageProps) {
  const { _id } = await params;

  let image: ImageView;
  try {
    image = await serverApi<ImageView>(`/api/images/${_id}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) notFound();
    throw err;
  }
  if (!image.url) notFound();

  return (
    <div>
      <div className="flex flex-row justify-center mt-20">
        <ImageCard image={image} />
      </div>

      <div className="flex flex-row justify-center items-center">
        <div className="relative w-full h-[75vh] my-20">
          <Image src={image.url} alt={image.prompt} fill className="rounded-lg object-contain" />
        </div>
      </div>
    </div>
  );
}
