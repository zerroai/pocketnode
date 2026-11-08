import { allDocs } from "@/lib/docs";

export const dynamic = "force-static";

export function GET() {
  return Response.json(allDocs().map((d) => ({ title: d.navTitle, group: d.group, url: d.url, headings: d.headings.map((h) => h.text).join(" "), text: d.text.slice(0, 6000) })));
}
