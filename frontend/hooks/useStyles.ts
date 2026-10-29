"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { StyleOption } from "@/lib/types";

const FALLBACK: StyleOption[] = [{ id: "none", label: "None" }];

/** The style presets the backend offers. Until they load (or if the backend is down) only "None" is shown. */
export function useStyles(): StyleOption[] {
  const [styles, setStyles] = useState<StyleOption[]>(FALLBACK);

  useEffect(() => {
    let cancelled = false;
    api<StyleOption[]>("/api/styles")
      .then((list) => {
        if (!cancelled && list.length > 0) setStyles(list);
      })
      .catch(() => {
        // keep the fallback; generating without a style still works
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return styles;
}
