// Image style presets. The client sends only the id; the server appends the
// suffix to the prompt, so a style always renders the same way.
export const STYLE_PRESETS = [
  { id: "none", label: "None", suffix: "" },
  {
    id: "photo",
    label: "Photo",
    suffix: "realistic photograph, natural lighting, sharp focus, 35mm lens, high detail",
  },
  {
    id: "anime",
    label: "Anime",
    suffix: "anime key visual, clean line art, cel shading, vibrant colors, detailed background",
  },
  {
    id: "oil",
    label: "Oil painting",
    suffix: "oil painting on canvas, visible brushstrokes, rich colors, classical composition",
  },
  {
    id: "watercolor",
    label: "Watercolor",
    suffix: "watercolor painting, soft washes of color, visible paper texture, gentle edges",
  },
  {
    id: "3d",
    label: "3D",
    suffix: "stylized 3D render, soft studio lighting, smooth materials, high detail",
  },
  {
    id: "comic",
    label: "Comic",
    suffix: "comic book illustration, bold ink outlines, halftone shading, flat vivid colors, dynamic composition",
  },
] as const;

export type StyleId = (typeof STYLE_PRESETS)[number]["id"];

export const STYLE_IDS = STYLE_PRESETS.map((s) => s.id) as [StyleId, ...StyleId[]];

export function applyStyle(prompt: string, style: StyleId): string {
  const preset = STYLE_PRESETS.find((s) => s.id === style);
  return preset?.suffix ? `${prompt}, ${preset.suffix}` : prompt;
}
