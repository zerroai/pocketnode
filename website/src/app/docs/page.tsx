import type { Metadata } from "next";
import Link from "next/link";
import { config, asset } from "@/lib/config";
import { Reveal } from "@/components/Reveal";
import { SpotCard } from "@/components/SpotCard";
import { SearchButton } from "@/components/SearchButton";

export const metadata: Metadata = { title: "Documentation", description: `The ${config.name} user guide, developer guide, privacy statement and threat model.` };

const card = (icon: string, h: string, t: string, href: string) => (
  <Link className="doc-card spot" href={href} key={href}><img className="tile" src={asset(`/brand/icons/${icon}.svg`)} alt="" width={48} height={48} /><h3>{h}</h3><p>{t}</p></Link>
);

export default function DocsHome() {
  return (
    <div className="wrap">
      <div className="page-head"><Reveal><p className="eyebrow">Documentation</p><h1>Learn {config.name}</h1><p className="lead">Plain words, in the order you will need them. Press the slash key to search.</p><p><SearchButton /></p></Reveal></div>
      <h2>Start here</h2>
      <div className="doc-cards">
        {card("download", "Getting started", "Install, first set up, and your first request.", "/docs/guide/01-getting-started/")}
        {card("chat", "Chatting and voice calls", "Typing, speaking, calls, attaching files and photos, stopping it.", "/docs/guide/02-chatting/")}
        {card("packs", "Packs", "Everything the assistant can do, and what each ability may touch.", "/docs/guide/03-packs/")}
      </div>
      <h2>Everyday</h2>
      <div className="doc-cards">
        {card("files", "Files", "The PDFs and spreadsheets it makes, and how to open them.", "/docs/guide/04-files/")}
        {card("bell", "Reminders and the morning briefing", "Ask in plain words. They fire even when the app is closed.", "/docs/guide/05-reminders-and-briefing/")}
        {card("mail", "Email", "Read your inbox and send from your own account, with your OK.", "/docs/guide/06-email/")}
        {card("channel", "Telegram and WhatsApp", "Talk to your assistant from the chat apps you use.", "/docs/guide/07-telegram-and-whatsapp/")}
        {card("agents", "Agents", "Jobs your phone does on its own.", "/docs/guide/14-agents/")}
        {card("phone", "Other devices", "Pair a second phone or a computer.", "/docs/guide/09-other-devices/")}
      </div>
      <h2>Safety and help</h2>
      <div className="doc-cards">
        {card("lock", "Privacy and control", "What leaves your phone, and how to check and stop the assistant.", "/docs/guide/08-privacy-and-control/")}
        {card("help", "Troubleshooting", "When something does not work.", "/docs/guide/10-troubleshooting/")}
        {card("help", "Questions and answers", "Cost, phones, models and keys.", "/docs/guide/11-faq/")}
        {card("key", "Using it with little or no cost", "Free tiers, your own computer, and keeping a bill small.", "/docs/guide/13-using-it-for-free/")}
      </div>
      <h2>Trust</h2>
      <div className="doc-cards">
        {card("shield", "Privacy, in plain words", "The statement of what it does with your information.", "/docs/privacy/")}
        {card("shield", "Threat model", "What we defend against, and what we do not.", "/docs/threat-model/")}
        {card("code", "For developers", "Write a pack, understand the design, build and test.", "/docs/developers/")}
      </div>
      <br />
    </div>
  );
}
