"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { asset } from "@/lib/config";

const NAME = "Pocketnode";

export function Logo() {
  return (
    <>
      <img className="only-light" src={asset("/brand/logo/horizontal-black.svg")} alt={NAME} height={30} />
      <img className="only-dark" src={asset("/brand/logo/horizontal-on-dark.svg")} alt="" height={30} />
    </>
  );
}

export function Header() {
  const path = usePathname() || "/";
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 8);
    on();
    window.addEventListener("scroll", on, { passive: true });
    return () => window.removeEventListener("scroll", on);
  }, []);
  useEffect(() => setOpen(false), [path]);

  const toggleTheme = () => {
    const root = document.documentElement;
    const t = root.getAttribute("data-theme");
    const dark = t ? t === "dark" : window.matchMedia("(prefers-color-scheme: dark)").matches;
    const next = dark ? "light" : "dark";
    root.setAttribute("data-theme", next);
    try { localStorage.setItem("mc-theme", next); } catch { /* the choice is only a convenience */ }
  };
  const link = (href: string, label: string, match: (p: string) => boolean) => (
    <Link className="link" href={href} aria-current={match(path) ? "page" : undefined}>{label}</Link>
  );

  return (
    <header className={`site-header${scrolled ? " scrolled" : ""}`}>
      <div className="wrap">
        <Link className="brand" href="/" aria-label={`${NAME}, home`}><Logo /></Link>
        <button className="icon-btn menu-btn" type="button" aria-expanded={open} aria-controls="site-nav" aria-label="Menu" onClick={() => setOpen((v) => !v)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
        </button>
        <nav className={`nav${open ? " open" : ""}`} id="site-nav" aria-label="Main">
          <Link className="link" href="/#features">What it does</Link>
          {link("/packs/", "Packs", (p) => p.startsWith("/packs"))}
          {link("/pricing/", "Pricing", (p) => p.startsWith("/pricing"))}
          {link("/docs/", "Docs", (p) => p.startsWith("/docs") && !p.startsWith("/docs/privacy"))}
          {link("/docs/privacy/", "Privacy", (p) => p.startsWith("/docs/privacy"))}
          <button className="icon-btn" type="button" aria-label="Search the documents" onClick={() => window.dispatchEvent(new Event("mc-search"))}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></svg>
          </button>
          <button className="icon-btn" type="button" aria-label="Switch between light and dark" onClick={toggleTheme}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" /></svg>
          </button>
          <Link className="btn small" href="/download/">Download</Link>
        </nav>
      </div>
    </header>
  );
}
