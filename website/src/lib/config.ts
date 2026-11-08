import raw from "../../site.config.json";

export type SiteConfig = {
  name: string;
  tagline: string;
  domain: string;
  supportEmail: string;
  version: string;
  apk: { file: string; sha256?: string; sizeMB?: number } | null;
  playStoreUrl: string;
  minAndroid: string;
  /** What the Personal plan costs, in the owner's words (for example "$5 a month"). Empty until a price is set. */
  personalPrice: string;
  /** False until the licence service is live and a person can really buy. The price is then shown as planned. */
  purchasesOpen: boolean;
};

export const config = raw as SiteConfig;

/** The address the site is served from, or an empty string until a domain is chosen. */
export const origin = config.domain ? (config.domain.startsWith("http") ? config.domain : "https://" + config.domain).replace(/\/$/, "") : "";

/** A prefix for hosts that serve the site from a folder. Empty at the root of a domain. */
export const BASE = process.env.NEXT_PUBLIC_BASE_PATH || "";

/** The address of a file in public/, for plain img tags and raw HTML. */
export const asset = (p: string) => BASE + p;
