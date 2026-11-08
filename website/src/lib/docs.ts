import fs from "node:fs";
import path from "node:path";
import { render, plain } from "./markdown.mjs";
import { BASE } from "./config";

const ROOT = path.resolve(process.cwd(), "..");

export type Doc = {
  rel: string;
  group: "Guide" | "For developers" | "Trust";
  url: string;            // the address on the site, with a trailing slash
  slug: string[];
  title: string;
  navTitle: string;
  html: string;
  headings: { level: number; id: string; text: string }[];
  text: string;
  desc: string;
};

const guide = ["README", "01-getting-started", "02-chatting", "03-packs", "04-files", "05-reminders-and-briefing", "06-email", "07-telegram-and-whatsapp", "09-other-devices", "14-agents", "08-privacy-and-control", "10-troubleshooting", "11-faq", "12-glossary", "13-using-it-for-free"];
const dev = ["README", "architecture", "writing-a-pack", "app-protocol", "building-and-testing", "signing-and-release", "phone-operations"];

const titles: Record<string, string> = { "docs/guide/README.md": "User guide", "docs/developers/README.md": "Developer guide", "docs/privacy.md": "Privacy, in plain words", "docs/threat-model.md": "Threat model" };

type Spec = { rel: string; group: Doc["group"]; slug: string[] };
const specs: Spec[] = [
  ...guide.map((n): Spec => ({ rel: `docs/guide/${n}.md`, group: "Guide", slug: n === "README" ? ["guide"] : ["guide", n] })),
  ...dev.map((n): Spec => ({ rel: `docs/developers/${n}.md`, group: "For developers", slug: n === "README" ? ["developers"] : ["developers", n] })),
  { rel: "docs/privacy.md", group: "Trust", slug: ["privacy"] },
  { rel: "docs/threat-model.md", group: "Trust", slug: ["threat-model"] },
];

const urlOf = (slug: string[]) => `${BASE}/docs/${slug.join("/")}/`;
const bySource = new Map(specs.map((s) => [path.join(ROOT, s.rel), s]));

let cache: Doc[] | null = null;

export function allDocs(): Doc[] {
  if (cache) return cache;
  cache = specs.map((s) => {
    const md = fs.readFileSync(path.join(ROOT, s.rel), "utf8");
    const link = (href: string) => {
      if (/^(https?:|mailto:)/.test(href) || href.startsWith("#")) return href;
      const [file, hash] = href.split("#");
      const target = bySource.get(path.resolve(path.dirname(path.join(ROOT, s.rel)), file));
      return target ? urlOf(target.slug) + (hash ? "#" + hash : "") : null;   // a file that is not on the site keeps its words, loses the link
    };
    const r = render(md, link);
    const first = md.split("\n\n").map((p) => p.trim()).find((p) => p && !/^(#|\||-|```)/.test(p));
    return {
      rel: s.rel, group: s.group, slug: s.slug, url: urlOf(s.slug),
      title: r.title || path.basename(s.rel, ".md"), navTitle: titles[s.rel] || r.title,
      html: r.html, headings: r.headings,
      text: plain(md.replace(/```[\s\S]*?```/g, " ").replace(/^#+\s.*$/gm, " ")).replace(/\s+/g, " "),
      desc: first ? plain(first).replace(/\s+/g, " ").slice(0, 170) : "",
    };
  });
  return cache;
}

export function docBySlug(slug: string[]): Doc | undefined {
  return allDocs().find((d) => d.slug.join("/") === slug.join("/"));
}
