import fs from "node:fs";
import path from "node:path";
import { plain, slug } from "./markdown.mjs";
import { BASE } from "./config";

export type Pack = { name: string; text: string; href: string };

/** The packs, read from the packs guide so the website and the guide always agree. */
export function allPacks(): Pack[] {
  const lines = fs.readFileSync(path.resolve(process.cwd(), "..", "docs/guide/03-packs.md"), "utf8").split("\n");
  const out: Pack[] = [];
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith("## ")) continue;
    const name = lines[i].slice(3).trim();
    let j = i + 1;
    while (j < lines.length && !lines[j].trim()) j++;
    out.push({ name, text: lines[j] && !lines[j].startsWith("#") ? plain(lines[j]) : "", href: `${BASE}/docs/guide/03-packs/#${slug(name)}` });
  }
  return out;
}
