import type { Metadata } from "next";
import Link from "next/link";
import { config, BASE } from "@/lib/config";
import { Reveal } from "@/components/Reveal";

export const metadata: Metadata = { title: "Download", description: `Download ${config.name} for Android 7.0 or newer. 14 day free trial, no payment details.` };

export default function Download() {
  const apk = config.apk && config.apk.file ? config.apk : null;
  return (
    <div className="wrap">
      <div className="page-head"><Reveal><p className="eyebrow">Download</p><h1>Get {config.name} for Android</h1><p className="lead">A small base app. You add abilities later as packs. The free trial lasts 14 days and needs no payment details.</p></Reveal></div>
      <div className="split">
        <Reveal>
          {apk ? (
            <div className="card">
              <h3>{config.name} for Android{config.version ? `, version ${config.version}` : ""}</h3>
              <dl className="kv">
                <dt>File</dt><dd>{apk.file}{apk.sizeMB ? ` (${apk.sizeMB} MB)` : ""}</dd>
                <dt>Needs</dt><dd>Android {config.minAndroid} or newer</dd>
                {apk.sha256 ? <><dt>SHA-256</dt><dd><code>{apk.sha256}</code></dd></> : null}
              </dl>
              <p><a className="btn" href={`${BASE}/downloads/${encodeURIComponent(apk.file)}`} download>Download the app</a></p>
            </div>
          ) : (
            <div className="note"><strong>The first public build is not published yet</strong>There is nothing to download today. This page will carry the file, its size and its checksum when it is ready. Until then, the guide shows what the app does, and the <Link href="/docs/guide/01-getting-started/">getting started page</Link> shows the first run step by step.</div>
          )}
          {config.playStoreUrl ? <p><a className="btn secondary" href={config.playStoreUrl} rel="noopener noreferrer">Get it on Google Play</a></p> : <p className="muted">Google Play: not listed yet. iPhone: no version yet.</p>}
        </Reveal>
        <Reveal delay={0.1}>
          <h2>What you need</h2>
          <ul className="checks">
            <li><span>An Android phone, version {config.minAndroid} or newer.</span></li>
            <li><span>A connection to an AI model: an account with OpenRouter, OpenAI, Groq or Anthropic, or a model on your own computer. Your provider bills you directly.</span></li>
            <li><span>No account with us. The app has no sign-up.</span></li>
          </ul>
        </Reveal>
      </div>
      <h2>Install it</h2>
      <ol className="steps">
        <li><h3>Download the file</h3><p>Open this page on your phone and download the app file.</p></li>
        <li><h3>Open it</h3><p>Tap the file in your notifications or in your Downloads. Android may ask you to allow installs from your browser or files app. That is normal for an app that did not come from a store. You can switch it off again afterwards.</p></li>
        <li><h3>Follow the first run</h3><p>The app asks you to choose a plan, connect a model, pick starter tools and allow notifications. The <Link href="/docs/guide/01-getting-started/">guide</Link> walks through each step.</p></li>
      </ol>
      <article className="prose" style={{ maxWidth: 760 }}>
        <h2>Check the file</h2>
        <p>{apk && apk.sha256 ? "Compare the checksum above with your copy before you install it." : "When a build is published, its SHA-256 checksum will be here."} On a computer with the file in the current folder:</p>
        <pre><code>{`shasum -a 256 ${apk ? apk.file : "pocketnode.apk"}`}</code></pre>
        <p>The two long strings must be identical. If they are not, do not install it.</p>
        <h2>What it asks for</h2>
        <p>Almost nothing at install. The microphone is asked for only when you start a voice call. Notifications are asked for during setup on Android 13 and newer, so reminders can reach you. Calendar, contacts, location, photos and email stay off until you switch each one on. The full list is on the <Link href="/docs/guide/03-packs/">packs page</Link>.</p>
        <h2>Updating</h2>
        <p>Install the newer file over the old one. When both are signed with the same key, Android keeps your chats and settings.</p>
        <p className="small muted">Questions about install? See <Link href="/docs/guide/10-troubleshooting/">troubleshooting</Link>.</p>
      </article>
    </div>
  );
}
