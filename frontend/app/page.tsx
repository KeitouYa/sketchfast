import { GenerateImageInput } from "@/components/forms/generate-image-input";
import { HeroImageSlider } from "@/components/image/hero-image-slider";

export default function Home() {
  return (
    <div className="w-full max-w-5xl mx-auto px-6">
      <div className="flex flex-col gap-4 pt-10 pb-6">
        <h1 className="text-4xl md:text-5xl font-bold tracking-tight text-balance">
          Describe a picture. Get it in seconds.
        </h1>
        <p className="max-w-2xl text-base text-foreground/85 leading-relaxed">
          Type what you want to see, pick a style, and the image is ready in about ten seconds. Write in any
          language; non-English descriptions are translated for the model. Not sure how to phrase it? Enhance
          turns a few words into a detailed description you can edit.
        </p>
        <p className="text-sm text-muted-foreground">New accounts start with 20 free credits. Each image costs 1.</p>
        <GenerateImageInput />
      </div>

      <div className="pb-8">
        <HeroImageSlider />
      </div>
    </div>
  );
}
