"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { BASE } from "@/lib/config";

type Entry = { title: string; group: string; url: string; headings: string; text: string };

/** Search over the documents, in the browser. The index is one file and is only fetched when search is first opened. */
export function Search() {
  const dlg = useRef<HTMLDialogElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [index, setIndex] = useState<Entry[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [q, setQ] = useState("");

  const open = useCallback(() => {
    const d = dlg.current;
    if (!d) return;
    if (!d.open) d.showModal();
    setQ("");
    setTimeout(() => input.current?.focus(), 0);
    if (!index && !failed) fetch(`${BASE}/search.json`).then((r) => r.json()).then(setIndex).catch(() => setFailed(true));
  }, [index, failed]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName?.toLowerCase();
      if ((e.key === "/" && tag !== "input" && tag !== "textarea") || ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k")) { e.preventDefault(); open(); }
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mc-search", open);
    return () => { window.removeEventListener("keydown", onKey); window.removeEventListener("mc-search", open); };
  }, [open]);

  const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
  const hits = (index ?? []).map((d) => {
    let score = 0;
    const t = d.title.toLowerCase(), h = d.headings.toLowerCase(), x = d.text.toLowerCase();
    for (const w of words) {
      const s = (t.includes(w) ? 8 : 0) + (h.includes(w) ? 4 : 0) + (x.includes(w) ? 1 : 0);
      if (!s) return { d, score: 0 };
      score += s;
    }
    return { d, score };
  }).filter((h) => h.score > 0).sort((a, b) => b.score - a.score).slice(0, 12);

  return (
    <dialog ref={dlg} className="search-dialog" aria-label="Search the documents" onClick={(e) => { if (e.target === dlg.current) dlg.current?.close(); }}>
      <form method="dialog">
        <input ref={input} type="search" placeholder="Search the documents" autoComplete="off" aria-label="Search the documents" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="icon-btn" type="button" aria-label="Close search" onClick={() => dlg.current?.close()}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg></button>
      </form>
      <ul className="search-results">
        {failed ? <li className="search-empty">Search could not be loaded. Use the menu on the left instead.</li>
          : !words.length ? <li className="search-empty">Type a word, such as reminders, email or privacy.</li>
          : !index ? <li className="search-empty">Loading</li>
          : !hits.length ? <li className="search-empty">Nothing found for &quot;{q}&quot;. Try a shorter word.</li>
          : hits.map(({ d }) => {
            const at = d.text.toLowerCase().indexOf(words[0]);
            const snip = at < 0 ? d.text.slice(0, 120) : (at > 40 ? "..." : "") + d.text.slice(Math.max(0, at - 40), at + 100);
            return <li key={d.url}><a href={d.url} onClick={() => dlg.current?.close()}><span className="where">{d.group}</span><strong>{d.title}</strong><span>{snip}</span></a></li>;
          })}
      </ul>
    </dialog>
  );
}
