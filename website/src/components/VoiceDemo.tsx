"use client";
import { useEffect, useState } from "react";
import { useReducedMotion } from "motion/react";
import { Orb, type Phase } from "./Orb";
import { Typewriter } from "./Typewriter";

const STEPS: { phase: Exclude<Phase, "idle">; ms: number; label: string; line: string }[] = [
  { phase: "listening", ms: 4200, label: "Listening", line: "What is the weather in Accra?" },
  { phase: "thinking", ms: 2400, label: "Thinking", line: "" },
  { phase: "speaking", ms: 5200, label: "Speaking", line: "It is 27 degrees and sunny in Accra. No rain today." },
];

/** The voice call, drawn: the mark swells while you talk, turns while it thinks, and ripples while it answers. Black, like the call screen. */
export function VoiceDemo() {
  const reduce = useReducedMotion();
  const [i, setI] = useState(0);
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    if (reduce) return;
    const t = setTimeout(() => setI((v) => (v + 1) % STEPS.length), STEPS[i].ms);
    return () => clearTimeout(t);
  }, [i, reduce]);
  useEffect(() => {
    if (reduce) return;
    const t = setInterval(() => setSeconds((s) => (s + 1) % 3600), 1000);
    return () => clearInterval(t);
  }, [reduce]);
  const step = reduce ? STEPS[2] : STEPS[i];
  return (
    <div className="call-panel" role="img" aria-label="An illustration of the voice call screen. You ask about the weather, the mark turns while the assistant thinks, then it answers out loud.">
      <div aria-hidden="true" style={{ display: "contents" }}>
        <div className="timer">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, "0")}</div>
        <div className="orb"><Orb phase={step.phase} /></div>
        <div className="state">{step.label}</div>
        <div className="line">{step.line ? <Typewriter key={step.phase} text={step.line} speed={step.phase === "speaking" ? 34 : 40} /> : null}</div>
        <div className="phases">{STEPS.map((s) => <span key={s.phase} className={s.phase === step.phase ? "on" : ""}>{s.label}</span>)}</div>
        <div className="btns">
          <div className="cbtn"><svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3" /></svg></div>
          <div className="cbtn end"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18" /></svg></div>
        </div>
      </div>
    </div>
  );
}
