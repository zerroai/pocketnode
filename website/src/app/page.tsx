import Link from "next/link";
import { config, asset } from "@/lib/config";
import { allPacks } from "@/lib/packs";
import { HeroTitle } from "@/components/HeroTitle";
import { HeroRing, Orb } from "@/components/Orb";
import { PhoneDemo } from "@/components/PhoneDemo";
import { VoiceDemo } from "@/components/VoiceDemo";
import { Reveal } from "@/components/Reveal";
import { SpotCard } from "@/components/SpotCard";
import { MiniOrb } from "@/components/MiniOrb";

const Tile = ({ name }: { name: string }) => <img className="tile" src={asset(`/brand/icons/${name}.svg`)} alt="" width={48} height={48} />;

const pillars = [
  ["phone", "Lives on your phone", "Your chats, notes, reminders and files stay on the phone, in the app's private storage. There is no account to create."],
  ["server", "Your choice of model", "Connect OpenRouter, OpenAI, Groq, Anthropic, or a model on your own computer. You pay your provider directly."],
  ["lock", "Asks first", "Calendar, contacts, location, photos and email are off until you switch them on. The most private things ask every single time."],
  ["eye", "Shows its work", "The Activity record lists what it did and what left the phone. One switch turns the whole assistant off."],
] as const;

const features = [
  ["chat", "Ask in plain words", "Type or speak. Attach a photo or a file, or share something into the app from another one."],
  ["voice", "Talk like a phone call", "Tap the voice button and just talk. It listens, answers out loud, and listens again. Speak over it to stop it."],
  ["bell", "Reminders and a morning briefing", "Say when, in plain words. Reminders appear even when the app is closed. The briefing gives you a short summary at the time you choose."],
  ["files", "Makes real files", "PDFs, spreadsheets and text files, kept locked on your phone. Open them, save a copy, or share them."],
  ["mail", "Email from your own account", "Reads your inbox and drafts mail. It never sends anything without showing it to you first."],
  ["channel", "Telegram and WhatsApp", "Talk to your assistant from the chat apps you already use. Only chats you approve get answers."],
  ["agents", "Agents that run by themselves", "Build a job as a flow, or describe it in a chat. For example, tell you each morning whether it will rain."],
  ["packs", "Packs add abilities", "Weather, notes, budgets, calendar, documents and more. Each pack lists exactly what it may touch."],
] as const;

const faq = [
  ["Is it free?", "You get a 14 day free trial with everything included and no payment details. Paid plans open later. Separately, the AI model you connect charges you for its own use, and we take no cut of that."],
  ["Which phones work?", "Android 7.0 and newer. There is no iPhone version yet."],
  ["Does the AI run on my phone?", "No. The assistant, your chats and your files are on your phone, but the AI model runs at the provider you choose, or on your own computer if you prefer. A model that runs entirely on the phone is not offered yet."],
  ["Who pays for the AI?", "You do, directly to your provider, based on how much you use. Short requests cost very little. The guide shows how to keep it at or near zero with a free plan or your own computer."],
  ["Is it open source?", "No. Pocketnode is closed source. In its place we publish plain documents about what it does with your data and a threat model, and the app has an Activity record that shows what it did. An independent security review is planned before release and has not happened yet."],
  ["What can it not do?", "It cannot buy things or move money. It has no payment abilities. Anything private is off until you switch it on, and the most private things ask you every time."],
] as const;

export default function Home() {
  const packs = allPacks();
  const half = Math.ceil(packs.length / 2);
  const rowA = packs.slice(0, half), rowB = packs.slice(half);
  return (
    <>
      <section className="hero">
        <div className="wrap grid">
          <div>
            <p className="eyebrow live">For Android</p>
            <HeroTitle text="A helper on your phone that gets things done." />
            <Reveal delay={0.5}><p className="lead">{config.name} is an assistant that lives on your phone. Ask in plain words, by typing or by talking. It looks things up, sets reminders, makes PDFs and spreadsheets, and runs jobs on its own. It asks before it touches anything private.</p></Reveal>
            <Reveal delay={0.65}>
              <div className="cta"><Link className="btn" href="/download/">Download for Android</Link><Link className="btn secondary" href="/docs/guide/01-getting-started/">Read the guide</Link></div>
              <p className="muted small">Android 7.0 or newer. 14 day free trial with everything included. No payment details.</p>
            </Reveal>
          </div>
          <div className="hero-stage">
            <div className="hero-ring" style={{ color: "var(--mc-text)" }}><HeroRing /></div>
            <PhoneDemo />
            <span className="floating-note a">Asks before it acts</span>
            <span className="floating-note b">Your chats stay here</span>
          </div>
        </div>
        <p className="wrap muted small" style={{ textAlign: "right", marginTop: 8 }}>An illustration of the chat, not a recording of the app.</p>
      </section>

      <section className="band" id="why"><div className="wrap">
        <Reveal><span className="sec-num">01 / Control</span><h2>Yours, and under your control</h2><p className="intro">Four things hold true however you use it.</p></Reveal>
        <div className="cards">{pillars.map(([icon, h, t], i) => <Reveal key={h} delay={i * 0.08}><SpotCard><Tile name={icon} /><h3>{h}</h3><p>{t}</p></SpotCard></Reveal>)}</div>
      </div></section>

      <section className="band alt" id="features"><div className="wrap">
        <Reveal><span className="sec-num">02 / Abilities</span><h2>What it can do</h2><p className="intro">Ask for what you want. It uses small add-ons called packs to do it, and shows a card for each one it uses.</p></Reveal>
        <div className="cards">{features.map(([icon, h, t], i) => <Reveal key={h} delay={(i % 4) * 0.07}><SpotCard><Tile name={icon} /><h3>{h}</h3><p>{t}</p></SpotCard></Reveal>)}</div>
      </div></section>

      <section className="band" id="voice"><div className="wrap split">
        <Reveal>
          <span className="sec-num">03 / Voice</span>
          <h2>Talk to it like a call</h2>
          <p className="intro">Nothing to tap in between.</p>
          <ul className="checks">
            <li><span>It listens, thinks, answers out loud, then listens again.</span></li>
            <li><span>Start talking while it is talking and it stops and listens to you.</span></li>
            <li><span>Anything that needs your OK, such as deleting something, still asks on the screen and waits.</span></li>
            <li><span>Each call is saved as text under Profile, then Calls, where you can read, rename or delete it. The sound is not recorded.</span></li>
          </ul>
          <div className="note"><strong>Plain about the limits</strong>The call works while {config.name} is on the screen, because Android does not let a hidden app keep the microphone open. Your phone&apos;s own speech service turns your voice into words and may send the audio to its provider, the same way your keyboard&apos;s dictation does. {config.name} receives only the words.</div>
        </Reveal>
        <Reveal delay={0.1}><VoiceDemo /></Reveal>
      </div></section>

      <section className="band alt" id="packs"><div className="wrap">
        <Reveal><span className="sec-num">04 / Packs</span><h2>Add abilities, like map packs</h2><p className="intro">The base app is small. You add what you need. Every pack is signed and checked before it installs, and none can download and run native code.</p></Reveal>
      </div>
        <div className="ticker" aria-label="The packs">
          <div className="track">{[...rowA, ...rowA].map((p, i) => <Link key={i} className="chip" href={p.href} tabIndex={i >= rowA.length ? -1 : undefined} aria-hidden={i >= rowA.length ? true : undefined}>{p.name}</Link>)}</div>
        </div>
        <div className="ticker rev" aria-hidden="true">
          <div className="track">{[...rowB, ...rowB].map((p, i) => <Link key={i} className="chip" href={p.href} tabIndex={-1}>{p.name}</Link>)}</div>
        </div>
        <div className="wrap"><p style={{ marginTop: 28 }}><Link className="btn secondary" href="/packs/">See all {packs.length} packs</Link></p></div>
      </section>

      <section className="band" id="privacy"><div className="wrap split">
        <Reveal>
          <span className="sec-num">05 / Privacy</span>
          <h2>Plain about privacy</h2>
          <p className="intro">What stays, what leaves, and how you stay in charge.</p>
          <Link className="btn secondary" href="/docs/guide/08-privacy-and-control/">Read privacy and control</Link>
        </Reveal>
        <Reveal delay={0.1}>
          <ul className="checks">
            <li><span><strong>On your phone:</strong> chats, notes, reminders, files and settings, in the app&apos;s private storage. No servers hold them. Nothing is backed up to the cloud.</span></li>
            <li><span><strong>Locked keys:</strong> your keys and passwords are encrypted with a key held by Android&apos;s secure hardware.</span></li>
            <li><span><strong>To your model provider:</strong> your messages, recent conversation, tool results and anything you attach. The provider can read them, so choose one you trust or run a model on your own computer.</span></li>
            <li><span><strong>Closed source, openly documented:</strong> we publish what it does with your data and a threat model. An independent security review is planned and has not been done yet.</span></li>
          </ul>
        </Reveal>
      </div></section>

      <section className="band alt" id="start"><div className="wrap">
        <Reveal><span className="sec-num">06 / Start</span><h2>Start in a few minutes</h2><p className="intro">The app walks you through it. You need an Android phone and a connection to an AI model.</p></Reveal>
        <ol className="steps">
          {[["Install", "Download the app on the download page and open it."], ["Start the free trial", "14 days, everything included, no payment details."], ["Connect a model", "Pick OpenRouter, OpenAI, Groq, Anthropic or your own computer, and paste your key. The app checks it for you."], ["Ask for something", "Try \"What is the weather in my city right now?\" or \"Remind me to stretch in 30 minutes.\""]].map(([h, t], i) => (
            <li key={h}><Reveal delay={i * 0.08}><h3>{h}</h3><p>{t}</p></Reveal></li>
          ))}
        </ol>
      </div></section>

      <section className="band" id="plans"><div className="wrap">
        <Reveal><span className="sec-num">07 / Plans</span><h2>Plans</h2><p className="intro">Start free. The model you connect is billed by your provider, not by us.</p></Reveal>
        <Reveal delay={0.1}>
          <div className="plans">
            <div className="plan main"><h3>Free trial</h3><div className="price">14 days</div><ul><li>Everything included</li><li>No payment details</li><li>No account to create</li></ul><Link className="btn" href="/download/">Get the app</Link></div>
            <div className="plan"><h3>Personal</h3><div className="price">{config.personalPrice || "Not open yet"}</div>{config.personalPrice && !config.purchasesOpen ? <p className="muted small" style={{ margin: "-6px 0 12px" }}>Planned price. It cannot be bought yet.</p> : null}<ul><li>For people using it on their own phone</li><li>{config.personalPrice ? "Paid with a licence key" : "Price and billing are not set yet"}</li></ul><Link className="btn secondary" href="/pricing/">See the plans</Link></div>
          </div>
        </Reveal>
      </div></section>

      <section className="band alt" id="faq"><div className="wrap">
        <Reveal><span className="sec-num">08 / Questions</span><h2>Questions</h2></Reveal>
        <div className="faq">{faq.map(([q, a]) => <details key={q}><summary>{q}</summary><div className="answer">{a}</div></details>)}</div>
        <p><Link href="/docs/guide/11-faq/">More questions and answers</Link></p>
      </div></section>

      <section className="cta-band"><div className="wrap">
        <Reveal><MiniOrb /></Reveal>
        <Reveal delay={0.1}><h2>A helper that stays yours</h2></Reveal>
        <Reveal delay={0.15}><p className="lead">Free for 14 days. No payment details. No account.</p></Reveal>
        <Reveal delay={0.2}><div className="cta"><Link className="btn" href="/download/">Download for Android</Link><Link className="btn secondary" href="/docs/">Read the documentation</Link></div></Reveal>
      </div></section>
    </>
  );
}
