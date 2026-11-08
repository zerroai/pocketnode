import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { allDocs, docBySlug } from "@/lib/docs";
import { DocEnhance } from "@/components/DocEnhance";
import { DocNav } from "@/components/DocNav";

export function generateStaticParams() { return allDocs().map((d) => ({ slug: d.slug })); }
export const dynamicParams = false;

export async function generateMetadata({ params }: { params: Promise<{ slug: string[] }> }): Promise<Metadata> {
  const d = docBySlug((await params).slug);
  return d ? { title: d.navTitle, description: d.desc } : {};
}

const crumbOf = (g: string): [string, string] => (g === "Guide" ? ["User guide", "/docs/guide/"] : g === "For developers" ? ["Developer guide", "/docs/developers/"] : ["Trust", "/docs/privacy/"]);

export default async function DocPage({ params }: { params: Promise<{ slug: string[] }> }) {
  const doc = docBySlug((await params).slug);
  if (!doc) notFound();
  const docs = allDocs();
  const same = docs.filter((d) => d.group === doc.group);
  const at = same.indexOf(doc);
  const prev = same[at - 1], next = same[at + 1];
  const toc = doc.headings.filter((h) => h.level === 2 || h.level === 3);
  const crumb = crumbOf(doc.group);
  return (
    <div className="doc-layout">
      <DocNav>
        {(["Guide", "For developers", "Trust"] as const).map((g) => (
          <div key={g}><h4>{g}</h4>{docs.filter((d) => d.group === g).map((d) => <Link key={d.url} href={d.url} aria-current={d.url === doc.url ? "page" : undefined}>{d.navTitle}</Link>)}</div>
        ))}
      </DocNav>
      <article className="prose">
        <p className="crumbs"><Link href="/docs/">Docs</Link> / <Link href={crumb[1]}>{crumb[0]}</Link></p>
        <div dangerouslySetInnerHTML={{ __html: doc.html }} />
        <nav className="pager" aria-label="Next and previous pages">
          {prev ? <Link className="prev" href={prev.url}><small>Previous</small>{prev.navTitle}</Link> : <span />}
          {next ? <Link className="next" href={next.url}><small>Next</small>{next.navTitle}</Link> : null}
        </nav>
      </article>
      <aside className="doc-toc" aria-label="On this page">
        {toc.length > 1 ? <><h4>On this page</h4>{toc.map((h) => <a key={h.id} className={`l${h.level}`} href={`#${h.id}`}>{h.text}</a>)}</> : null}
      </aside>
      <DocEnhance />
    </div>
  );
}
