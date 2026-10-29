import type { NextConfig } from "next";

// The backend that serves /api/**. Swap it here to run the same frontend against another backend.
const API_URL = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  images: {
    remotePatterns: [
      {
        protocol: "https",
        hostname: "res.cloudinary.com",
      },
    ],
  },
  async rewrites() {
    return {
      // beforeFiles: these win over the app/api route handlers of the old TypeScript backend
      beforeFiles: [{ source: "/api/:path*", destination: `${API_URL}/api/:path*` }],
    };
  },
};

export default nextConfig;
