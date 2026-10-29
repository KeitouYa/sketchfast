import React from "react";
import Link from "next/link";
import ImageCard from "@/components/image/image-card";
import Pagination from "@/components/layout/pagination";
import { serverApi } from "@/lib/server-api";
import type { ImageView, Page } from "@/lib/types";

const PAGE_SIZE = 12;

interface DashboardProps {
  // Next 15 passes searchParams as a Promise; query values are strings
  searchParams: Promise<{ page?: string }>;
}

export default async function Dashboard({ searchParams }: DashboardProps) {
  const { page } = await searchParams;
  const pageNum = Number.isFinite(Number(page)) && Number(page) > 0 ? Math.floor(Number(page)) : 1;

  const gallery = await serverApi<Page<ImageView>>(`/api/me/images?page=${pageNum}&size=${PAGE_SIZE}`, {
    signedIn: true,
  });

  if (gallery.total === 0) {
    return (
      <div className="container max-w-7xl mx-auto py-16 px-4 text-center space-y-3">
        <h1 className="text-2xl font-semibold">No images yet</h1>
        <p className="text-muted-foreground">
          <Link href="/" className="underline">
            Describe your first image
          </Link>{" "}
          and it will show up here.
        </p>
      </div>
    );
  }

  return (
    <div className="container max-w-7xl mx-auto py-8 px-4 md:px-8 flex flex-col gap-8">
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
        {gallery.items.map((image) => (
          <Link key={image.id} href={`/dashboard/image/${image.id}`}>
            <ImageCard image={image} />
          </Link>
        ))}
      </div>

      <div className="flex justify-center mt-8">
        <Pagination page={pageNum} totalPages={gallery.totalPages} />
      </div>
    </div>
  );
}
