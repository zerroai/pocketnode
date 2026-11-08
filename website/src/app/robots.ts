import type { MetadataRoute } from "next";
import { origin } from "@/lib/config";

export const dynamic = "force-static";

export default function robots(): MetadataRoute.Robots {
  return { rules: { userAgent: "*", allow: "/" }, ...(origin ? { sitemap: `${origin}/sitemap.xml` } : {}) };
}
