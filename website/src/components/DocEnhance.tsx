"use client";
import { useEffect } from "react";

/** Adds copy buttons to code blocks and marks the section in view in the page outline. */
export function DocEnhance() {
  useEffect(() => {
    const cleanups: (() => void)[] = [];
    document.querySelectorAll<HTMLPreElement>("article pre").forEach((pre) => {
      if (pre.querySelector(".copy")) return;
      const b = document.createElement("button");
      b.type = "button"; b.className = "copy"; b.textContent = "Copy";
      const on = () => {
        const text = pre.querySelector("code")?.textContent ?? "";
        navigator.clipboard?.writeText(text).then(() => { b.textContent = "Copied"; setTimeout(() => (b.textContent = "Copy"), 1500); }, () => {});
      };
      b.addEventListener("click", on);
      pre.appendChild(b);
      cleanups.push(() => { b.removeEventListener("click", on); b.remove(); });
    });
    const toc = Array.from(document.querySelectorAll<HTMLAnchorElement>(".doc-toc a"));
    if (toc.length) {
      const map = new Map(toc.map((a) => [a.getAttribute("href")!.slice(1), a]));
      const obs = new IntersectionObserver((entries) => {
        entries.forEach((e) => {
          if (!e.isIntersecting) return;
          toc.forEach((a) => a.classList.remove("active"));
          map.get((e.target as HTMLElement).id)?.classList.add("active");
        });
      }, { rootMargin: "-80px 0px -70% 0px" });
      map.forEach((_, id) => { const el = document.getElementById(id); if (el) obs.observe(el); });
      cleanups.push(() => obs.disconnect());
    }
    return () => cleanups.forEach((f) => f());
  }, []);
  return null;
}
