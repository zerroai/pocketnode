// Runs after `next build`: every internal link, image and section address must exist, and the words must follow the house style
// (no em dashes, no emoji). Exits with an error when something is wrong.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const out = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "out");
const base = process.env.NEXT_PUBLIC_BASE_PATH || "";
const walk = (d) => fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
const files = walk(out);
const pages = files.filter((f) => f.endsWith(".html"));
const ids = new Map(pages.map((f) => [f, new Set([...fs.readFileSync(f, "utf8").matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]))]));
const problems = [];
const resolve = (from, href) => {
  let p = href.split("#")[0].split("?")[0];
  if (!p) return from;
  if (p.startsWith("/")) { if (base && p.startsWith(base)) p = p.slice(base.length); p = path.join(out, p); }
  else p = path.resolve(path.dirname(from), p);
  if (fs.existsSync(p) && fs.statSync(p).isDirectory()) p = path.join(p, "index.html");
  return p;
};
for (const f of pages) {
  const html = fs.readFileSync(f, "utf8");
  const rel = path.relative(out, f);
  for (const m of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const u = m[1].replace(/&amp;/g, "&");
    if (/^(https?:|mailto:|data:|#$)/.test(u)) continue;
    const target = resolve(f, u);
    if (!fs.existsSync(target)) { problems.push(`${rel}: missing ${u}`); continue; }
    const hash = u.split("#")[1];
    if (hash && target.endsWith(".html") && !ids.get(target)?.has(hash)) problems.push(`${rel}: no section ${u}`);
  }
  if (html.includes(String.fromCharCode(8212))) problems.push(`${rel}: contains an em dash`);
  if (/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(html)) problems.push(`${rel}: contains an emoji`);
}
const size = files.reduce((n, f) => n + fs.statSync(f).size, 0);
console.log(`Checked ${pages.length} pages (${(size / 1e6).toFixed(1)} MB in website/out).`);
if (problems.length) { console.error(problems.slice(0, 60).join("\n")); console.error(`${problems.length} problem(s)`); process.exit(1); }
console.log("All internal links resolve; no em dashes or emoji.");
