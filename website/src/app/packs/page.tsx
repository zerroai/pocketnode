import type { Metadata } from "next";
import Link from "next/link";
import { config } from "@/lib/config";
import { allPacks } from "@/lib/packs";
import { Reveal } from "@/components/Reveal";
import { SpotCard } from "@/components/SpotCard";

export const metadata: Metadata = { title: "Packs", description: `Everything the ${config.name} assistant can do, as signed packs you add one at a time.` };

export default function Packs() {
  const packs = allPacks();
  return (
    <div className="wrap">
      <div className="page-head"><Reveal><p className="eyebrow">Packs</p><h1>Abilities you add, one by one</h1>
        <p className="lead" style={{ maxWidth: "62ch" }}>The base app is small. Packs add what you need, like map packs for a game. Every pack is signed and checked before it installs, and each lists exactly what it may touch. Private things stay off until you switch them on. Packs run in a sandbox: they cannot download and run native code.</p></Reveal></div>
      <div className="pack-grid">
        {packs.map((p, i) => <Reveal key={p.name} delay={(i % 4) * 0.05}><SpotCard className="pack"><h3>{p.name}</h3><p>{p.text}</p><a href={p.href}>What it may do</a></SpotCard></Reveal>)}
      </div>
      <p><br />Want to make one? Read <Link href="/docs/developers/writing-a-pack/">writing a pack</Link>.</p><br />
    </div>
  );
}
