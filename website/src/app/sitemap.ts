import type { MetadataRoute } from "next";
import { origin } from "@/lib/config";
import { allDocs } from "@/lib/docs";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  if (!origin) return [];
  const urls = ["/", "/download/", "/packs/", "/pricing/", "/docs/", ...allDocs().map((d) => d.url)];
  return urls.map((u) => ({ url: origin + u }));
}
