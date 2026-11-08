import Link from "next/link";
import { config } from "@/lib/config";
import { Logo } from "./Header";

export function Footer() {
  return (
    <footer className="site-footer"><div className="wrap"><div className="cols">
      <div><Link className="brand" href="/" aria-label={`${config.name}, home`}><Logo /></Link><p className="muted small">{config.tagline}</p></div>
      <div><h4>Product</h4><ul><li><Link href="/#features">What it does</Link></li><li><Link href="/packs/">Packs</Link></li><li><Link href="/pricing/">Pricing</Link></li><li><Link href="/download/">Download</Link></li></ul></div>
      <div><h4>Learn</h4><ul><li><Link href="/docs/guide/01-getting-started/">Getting started</Link></li><li><Link href="/docs/guide/02-chatting/">Chatting and voice calls</Link></li><li><Link href="/docs/guide/11-faq/">Questions and answers</Link></li><li><Link href="/docs/guide/10-troubleshooting/">Troubleshooting</Link></li></ul></div>
      <div><h4>Trust</h4><ul><li><Link href="/docs/privacy/">Privacy</Link></li><li><Link href="/docs/guide/08-privacy-and-control/">Privacy and control</Link></li><li><Link href="/docs/threat-model/">Threat model</Link></li><li><Link href="/docs/developers/">For developers</Link></li></ul></div>
      <p className="fine">{config.name} is closed source. This site sets no cookies, runs no analytics and loads nothing from other sites.{config.supportEmail ? <> Contact: <a href={`mailto:${config.supportEmail}`}>{config.supportEmail}</a>.</> : null}</p>
    </div></div></footer>
  );
}
