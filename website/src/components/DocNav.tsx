"use client";
import { useState, type ReactNode } from "react";

/** The documents menu. Always open beside the page on a computer; a button that opens it on a phone. */
export function DocNav({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <aside className={`doc-nav${open ? " open" : ""}`}>
      <button className="doc-nav-toggle" type="button" aria-expanded={open} aria-controls="doc-nav-inner" onClick={() => setOpen((v) => !v)}><span>Browse the documents</span><span aria-hidden="true">{open ? "−" : "+"}</span></button>
      <div className="doc-nav-inner" id="doc-nav-inner">{children}</div>
    </aside>
  );
}
