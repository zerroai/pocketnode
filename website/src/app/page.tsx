const REPO = "https://github.com/zerroai/pocketnode";

const abilities = [
  ["Controls your phone", "Flashlight, alarms, timers, calls, texts, maps, sharing and notifications. You ask in plain words; it picks the right action."],
  ["Remembers things", "Notes, to-do lists and reminders that fire even when the app is closed."],
  ["Answers on Telegram", "Message your assistant from Telegram. A small relay holds each message until your phone collects it."],
  ["Runs jobs by itself", "Build an agent on a flow canvas, for example a check that tells you each morning whether it will rain."],
] as const;

const faq = [
  ["Which phones work?", "Android 7.0 and newer. It has been tested on a Samsung Galaxy S9 running Android 10. There is no iPhone version."],
  ["Where do my chats live?", "On the phone, in the app's private storage. Keys are kept in Android's secure keystore. The model provider sees the messages you send it."],
  ["Can it do anything it likes on my phone?", "No. Each tool lists what it may touch. Sensitive actions, such as reading contacts or the calendar, are off until you switch them on, and some ask every time."],
  ["Is it finished?", "No. It is an early build made for the Vultr Agent Rush hackathon. Expect rough edges. The APK is a test build, not a Play Store release."],
] as const;

export default function Home() {
  return (
    <>
      <header className="top">
        <div className="wrap bar">
          <a className="brand" href="#top" aria-label="Pocketnode home">
            <svg width="28" height="28" viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="16" fill="currentColor" /><circle cx="26" cy="38" r="9" fill="var(--paper)" /><path d="M38 26a14 14 0 0 1 14 14M38 14a26 26 0 0 1 26 26" fill="none" stroke="var(--paper)" strokeWidth="4" strokeLinecap="round" transform="translate(-8 4) scale(.85)" /></svg>
            Pocketnode
          </a>
          <nav aria-label="Main">
            <a href="#what">What it does</a><a href="#try">Try it</a><a href="#vultr">On Vultr</a><a href="#run">Run it</a><a className="btn small" href={REPO}>GitHub</a>
          </nav>
        </div>
      </header>

      <main id="top">
        <section className="hero"><div className="wrap">
          <p className="eyebrow">Android assistant · Vultr Agent Rush</p>
          <h1>An assistant that <em>acts</em> on your phone.</h1>
          <p className="lead">Pocketnode lives on your Android phone. Say &ldquo;turn on the flashlight&rdquo; or &ldquo;wake me at 6:30&rdquo; and it does it. Every model call goes through Vultr Serverless Inference.</p>
          <div className="cta"><a className="btn" href="/app/">Try it in your browser</a><a className="btn ghost" href="/downloads/pocketnode.apk">Download for Android</a><a className="btn ghost" href={REPO}>View the code</a></div>
          <div className="chat" role="img" aria-label="Example: the user asks to turn on the flashlight and Pocketnode confirms">
            <div className="msg me">Turn on the flashlight</div>
            <div className="tool"><span className="dot" />Phone · Flashlight on</div>
            <div className="msg bot">Flashlight is on.</div>
            <p className="cap">From a real run on a Galaxy S9: about 3 seconds from request to reply.</p>
          </div>
        </div></section>

        <section className="band" id="what"><div className="wrap">
          <h2>What it does</h2>
          <div className="cards">{abilities.map(([h, t]) => <article key={h} className="card"><h3>{h}</h3><p>{t}</p></article>)}</div>
        </div></section>

        <section className="band alt" id="try"><div className="wrap">
          <h2>Two ways to try it</h2>
          <div className="cards two">
            <article className="card"><h3>In your browser</h3><p>Sign up and ask it to search the web, read a page or open a site in a new tab. Nothing to install. Search is limited to a daily number of messages per account.</p><p style={{ marginTop: 14 }}><a className="btn small" href="/app/">Open the web app</a></p></article>
            <article className="card"><h3>On your Android phone</h3><p>Download the test build (Android 7.0 or newer), allow installs from this site, then open it. On the model screen choose Vultr and paste your own Vultr Inference key. There are no accounts in the phone app; your chats stay on the phone.</p><p style={{ marginTop: 14 }}><a className="btn small" href="/downloads/pocketnode.apk">Download the APK</a></p></article>
          </div>
        </div></section>

        <section className="band" id="vultr"><div className="wrap">
          <h2>How it uses Vultr</h2>
          <p className="intro">Two Vultr services do the work. The phone stays in charge of your data.</p>
          <div className="flow" aria-label="Architecture">
            <div className="node">Telegram</div><span className="arrow">→</span>
            <div className="node strong">Relay<small>Vultr Cloud Compute</small></div><span className="arrow">→</span>
            <div className="node">Your phone<small>runs the assistant</small></div><span className="arrow">→</span>
            <div className="node strong">Model<small>Vultr Serverless Inference</small></div>
          </div>
          <div className="cards two">
            <article className="card"><h3>Vultr Serverless Inference</h3><p>The phone talks to <code>api.vultrinference.com/v1</code> with ordinary tool calling. In our tests <code>deepseek-v4.1-flash-1017</code>, <code>glm-5.3-flash</code> and <code>mimo-v2.6-flash</code> each picked the right tool on every try, in about 1.2 seconds. Vultr is the preset in the setup screen.</p></article>
            <article className="card"><h3>Vultr Cloud Compute</h3><p>The relay is one small service and one SQLite file, run with Docker Compose behind Caddy, which gets its own TLS certificate. Phones only ever connect out to it. Messages are stored encrypted and deleted once the phone confirms it has them.</p></article>
          </div>
        </div></section>

        <section className="band alt" id="safe"><div className="wrap">
          <h2>Kept on a short leash</h2>
          <ul className="checks">
            <li>Tools run in a sandbox and can only use the permissions they declare.</li>
            <li>Private actions need your switch. The most sensitive ask every time.</li>
            <li>An Activity record lists what the assistant did and what left the phone.</li>
            <li>One switch turns the whole assistant off.</li>
          </ul>
        </div></section>

        <section className="band" id="run"><div className="wrap">
          <h2>Run it</h2>
          <pre><code>{`git clone ${REPO}
cd pocketnode
./gradlew :app:assembleDebug     # the Android app
./gradlew :core:test :relay:test # tests`}</code></pre>
          <p>In the app, choose <strong>Vultr</strong> on the model screen and paste your Vultr Inference key. To host the relay, see <code>deploy/relay/</code>.</p>
        </div></section>

        <section className="band alt" id="faq"><div className="wrap">
          <h2>Questions</h2>
          <div className="faq">{faq.map(([q, a]) => <details key={q}><summary>{q}</summary><p>{a}</p></details>)}</div>
        </div></section>
      </main>

      <footer className="foot"><div className="wrap"><span>Pocketnode</span><a href={REPO}>github.com/zerroai/pocketnode</a></div></footer>
    </>
  );
}
