// A small Markdown reader for our own documents. No outside code. It handles what the guides use:
// headings, paragraphs, lists (nested), tables, quotes, code, rules, links, bold, italic and code spans.

export function escapeHtml(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

export function slug(s) {
  return s.toLowerCase().replace(/<[^>]+>/g, "").replace(/[`*_]/g, "").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "section";
}

export function plain(s) {
  return s.replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/[`*_]/g, "").trim();
}

/** Inline text to HTML. `link(href)` returns the address to use, or null to show the words without a link. */
export function inline(text, link) {
  const spans = [];
  let t = text.replace(/`([^`]+)`/g, (_, c) => { spans.push("<code>" + escapeHtml(c) + "</code>"); return "\u0000" + (spans.length - 1) + "\u0000"; });
  t = escapeHtml(t);
  t = t.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (_, alt, src) => `<img src="${src}" alt="${alt}" loading="lazy">`);
  t = t.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, label, href) => {
    const out = link ? link(href.replace(/&amp;/g, "&")) : href;
    if (out == null) return label;
    const external = /^https?:/.test(out);
    return `<a href="${escapeHtml(out)}"${external ? ' rel="noopener noreferrer"' : ""}>${label}</a>`;
  });
  t = t.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>").replace(/(^|[\s(])\*([^*\s][^*]*)\*(?=[\s).,;:!?]|$)/g, "$1<em>$2</em>");
  return t.replace(/\u0000(\d+)\u0000/g, (_, i) => spans[+i]);
}

const indentOf = (l) => l.match(/^ */)[0].length;
const isFence = (l) => /^\s*```/.test(l);
const isList = (l) => /^\s*([-*]|\d+\.)\s+/.test(l);
const isTableSep = (l) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(l) && l.includes("-");

function cells(line) {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|")) s = s.slice(0, -1);
  return s.split("|").map((c) => c.trim());
}

/** Returns { html, headings: [{level, id, text}], title }. */
export function render(md, link) {
  const headings = [];
  const used = new Map();
  const lines = md.replace(/\r\n/g, "\n").split("\n");

  function blocks(ls) {
    const out = [];
    let i = 0;
    while (i < ls.length) {
      const l = ls[i];
      if (!l.trim()) { i++; continue; }
      if (isFence(l)) {
        const lang = l.trim().slice(3).trim();
        const body = [];
        i++;
        while (i < ls.length && !isFence(ls[i])) body.push(ls[i++]);
        i++;
        out.push(`<pre><code${lang ? ` class="lang-${escapeHtml(lang)}"` : ""}>${escapeHtml(body.join("\n"))}</code></pre>`);
        continue;
      }
      const h = l.match(/^(#{1,6})\s+(.*?)\s*#*\s*$/);
      if (h) {
        const level = h[1].length;
        const text = h[2];
        let id = slug(text);
        const n = used.get(id) || 0; used.set(id, n + 1);
        if (n) id += "-" + n;
        headings.push({ level, id, text: plain(text) });
        out.push(`<h${level} id="${id}">${inline(text, link)}${level > 1 ? `<a class="anchor" href="#${id}" aria-label="Link to this section">#</a>` : ""}</h${level}>`);
        i++; continue;
      }
      if (/^\s*([-*_])\1\1+\s*$/.test(l)) { out.push("<hr>"); i++; continue; }
      if (l.includes("|") && i + 1 < ls.length && isTableSep(ls[i + 1])) {
        const head = cells(l);
        i += 2;
        const rows = [];
        while (i < ls.length && ls[i].includes("|") && ls[i].trim()) rows.push(cells(ls[i++]));
        out.push(`<div class="table-wrap"><table><thead><tr>${head.map((c) => `<th>${inline(c, link)}</th>`).join("")}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${inline(c, link)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`);
        continue;
      }
      if (/^\s*>/.test(l)) {
        const q = [];
        while (i < ls.length && /^\s*>/.test(ls[i])) q.push(ls[i++].replace(/^\s*>\s?/, ""));
        out.push(`<blockquote>${blocks(q)}</blockquote>`);
        continue;
      }
      if (isList(l)) {
        const base = indentOf(l);
        const ordered = /^\s*\d+\./.test(l);
        const items = [];
        while (i < ls.length && ls[i].trim() && isList(ls[i]) && indentOf(ls[i]) === base) {
          const m = ls[i].match(/^(\s*)([-*]|\d+\.)\s+(.*)$/);
          const item = [m[3]];
          const width = m[1].length + m[2].length + 1;
          i++;
          while (i < ls.length) {
            if (!ls[i].trim()) {
              // A blank line stays inside the item only when more indented text follows.
              if (i + 1 < ls.length && ls[i + 1].trim() && indentOf(ls[i + 1]) > base) { item.push(""); i++; continue; }
              break;
            }
            if (indentOf(ls[i]) > base) { item.push(ls[i].slice(Math.min(width, indentOf(ls[i])))); i++; continue; }
            if (isList(ls[i]) || isFence(ls[i]) || /^#/.test(ls[i])) break;
            item.push(ls[i].trim()); i++;   // a wrapped line of the same item
          }
          let inner = blocks(item);
          if ((inner.match(/<p>/g) || []).length === 1 && inner.startsWith("<p>")) {
            const end = inner.indexOf("</p>");
            if (end + 4 === inner.length || !inner.slice(end + 4).includes("<p>")) inner = inner.replace("<p>", "").replace("</p>", "");
          }
          items.push(`<li>${inner}</li>`);
        }
        out.push(`<${ordered ? "ol" : "ul"}>${items.join("")}</${ordered ? "ol" : "ul"}>`);
        continue;
      }
      if (indentOf(l) >= 4) {
        const body = [];
        while (i < ls.length && (indentOf(ls[i]) >= 4 || !ls[i].trim())) body.push(ls[i++].slice(4));
        while (body.length && !body[body.length - 1].trim()) body.pop();
        out.push(`<pre><code>${escapeHtml(body.join("\n"))}</code></pre>`);
        continue;
      }
      const p = [];
      while (i < ls.length && ls[i].trim() && !isFence(ls[i]) && !/^#{1,6}\s/.test(ls[i]) && !isList(ls[i]) && !/^\s*>/.test(ls[i]) && !(ls[i].includes("|") && i + 1 < ls.length && isTableSep(ls[i + 1]))) p.push(ls[i++].trim());
      out.push(`<p>${inline(p.join(" "), link)}</p>`);
    }
    return out.join("\n");
  }

  const html = blocks(lines);
  const title = headings.find((h) => h.level === 1)?.text || "";
  return { html, headings, title };
}
