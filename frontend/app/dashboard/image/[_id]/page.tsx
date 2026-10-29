import React from "react";
import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import ImageEditButtons from "@/components/image/image-edit-buttons";
import { ApiError } from "@/lib/api";
import { serverApi } from "@/lib/server-api";
import type { ImageView } from "@/lib/types";

interface ImagePageProps {
  // Next 15 passes params as a Promise
  params: Promise<{ _id: string }>;
}

/** The owner's view: shows unfinished and failed images too, unlike the public share page. */
export default async function ImagePage({ params }: ImagePageProps) {
  const { _id } = await params;

  let image: ImageView;
  try {
    image = await serverApi<ImageView>(`/api/images/${_id}`, { signedIn: true });
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) notFound();
    throw err;
  }

  if (image.status !== "SUCCEEDED" || !image.url) {
    const failed = image.status === "FAILED";
    return (
      <div className="max-w-xl mx-auto py-16 px-4 text-center space-y-3">
        <h1 className="text-2xl font-semibold">{failed ? "This image couldn't be generated" : "Still generating"}</h1>
        <p className="text-muted-foreground">
          {failed
            ? "Your credit has been refunded. Try again, perhaps with a different description."
            : "Refresh in a few seconds to see it."}
        </p>
        <p className="text-sm text-muted-foreground">&ldquo;{image.prompt}&rdquo;</p>
        <Link href="/" className="underline">
          Back to the generator
        </Link>
      </div>
    );
  }

  return (
    <div className="flex flex-col max-w-4xl mx-auto justify-center items-center p-4">
      <div className="relative w-full h-[60vh] mb-8">
        <Image src={image.url} alt={image.prompt} fill className="rounded-lg object-contain" />
      </div>
      <ImageEditButtons image={image} />
    </div>
  );
}
