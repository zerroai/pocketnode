import type { Metadata } from "next";
import Link from "next/link";
import { config } from "@/lib/config";
import { Reveal } from "@/components/Reveal";
import { SpotCard } from "@/components/SpotCard";

export const metadata: Metadata = { title: "Pricing", description: `Plans for ${config.name}: a 14 day free trial with everything included, then the Personal plan. The AI model you connect is billed by your provider.` };

const rows: [string, string, string][] = [
  ["All the base app: chat, voice calls, reminders, agents", "Yes", "Yes"],
  ["Every pack, one at a time, each with its permissions listed", "Yes", "Yes"],
  ["Works with your own model account or a model on your computer", "Yes", "Yes"],
  ["No account to create", "Yes", "Yes"],
  ["Length", "14 days", "Until you stop"],
  ["Payment details needed to start", "None", "At purchase"],
];

const faq: [string, string][] = [
  ["What happens when the trial ends?", "This is not decided yet, and nothing is enforced in the app today. This page will say exactly what happens before any plan can be bought. Your chats and files are kept on your phone, not on our servers."],
  ["Do you take a cut of my model costs?", "No. You connect your own model account and your provider bills you directly. There are free options too, listed in the guide on using it for free. A plan that includes the model may come later; it is not available today."],
  ["How do I pay?", "You buy a licence key and type it in, or the app receives it after payment. The app checks the key on the phone itself, so there is no sign-in. Payment methods for direct installs are planned (Paystack, Stripe and Flutterwave); none is open to the public yet."],
  ["Can I use one plan on more than one phone?", "This is not decided. Phones can be paired so they act together, but how plans count phones has not been set."],
  ["Is there a team or business plan?", "Not today. Only the trial and the Personal plan exist."],
  ["Can I get a refund?", "Refund rules are not set yet. They will be written here before any purchase is possible."],
];

export default function Pricing() {
  const price = config.personalPrice;
  return (
    <div className="wrap">
      <div className="page-head"><Reveal><p className="eyebrow">Pricing</p><h1>Start free. Pay when you are sure.</h1>
        <p className="lead" style={{ maxWidth: "62ch" }}>Try everything for 14 days with no payment details. The AI model you connect is billed by your own provider, and we take no part of that.</p></Reveal></div>

      <div className="plans" style={{ maxWidth: 820 }}>
        <Reveal><SpotCard className="plan main">
          <h3>Free trial</h3><div className="price">14 days</div>
          <ul><li>Everything included</li><li>No payment details</li><li>No account to create</li></ul>
          <Link className="btn" href="/download/">Get the app</Link>
        </SpotCard></Reveal>
        <Reveal delay={0.08}><SpotCard className="plan">
          <h3>Personal</h3><div className="price">{price || "Not open yet"}</div>{price && !config.purchasesOpen ? <p className="muted small" style={{ margin: "-6px 0 12px" }}>Planned price. It cannot be bought yet.</p> : null}
          <ul><li>For one person on their own phone</li><li>Paid with a signed licence key, checked on the phone</li><li>You bring your own AI model account, so the app price stays low</li><li>{price ? "Billing terms are set at purchase" : "The price and billing are not set yet"}</li></ul>
          {price ? <Link className="btn secondary" href="/download/">Start with the trial</Link> : <Link className="btn secondary" href="/docs/guide/11-faq/">Read the questions</Link>}
        </SpotCard></Reveal>
      </div>

      <h2 style={{ marginTop: 56 }}>What each plan includes</h2>
      <div className="table-wrap"><table className="compare">
        <thead><tr><th scope="col">Feature</th><th scope="col">Free trial</th><th scope="col">Personal</th></tr></thead>
        <tbody>{rows.map(([a, b, c]) => <tr key={a}><th scope="row">{a}</th><td>{b}</td><td>{c}</td></tr>)}</tbody>
      </table></div>

      <h2 style={{ marginTop: 56 }}>What you pay for besides the app</h2>
      <p className="lead" style={{ maxWidth: "62ch" }}>The assistant needs an AI model to think. You choose it. Some providers have a free plan, some charge for what you use, and a model on your own computer costs only electricity. Your model bill is between you and your provider. See <Link href="/docs/guide/13-using-it-for-free/">using it for free</Link>.</p>

      <p style={{ maxWidth: "62ch" }}>What the model costs depends on which one you pick and how much you use. Everyday jobs such as reminders, files and quick questions can run on a small, cheap model. You can set a daily limit in the app so a month never surprises you.</p>

      <h2 style={{ marginTop: 56 }}>Questions about plans</h2>
      <div className="faq" style={{ maxWidth: 760 }}>{faq.map(([q, a]) => <details key={q}><summary>{q}</summary><div className="answer">{a}</div></details>)}</div>
      <p><br />More answers are in the <Link href="/docs/guide/11-faq/">full list of questions</Link>. <br /><br /></p>
    </div>
  );
}
