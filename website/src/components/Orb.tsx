"use client";
import { motion, useReducedMotion } from "motion/react";
import { useEffect, useId, useState } from "react";

export type Phase = "idle" | "listening" | "thinking" | "speaking";

/**
 * The Pocketnode mark drawn large and moving, with the same geometry as the logo (ring radius 30, opening from -75 to -15 degrees,
 * dot radius 8 at -45 degrees) and the same behavior as the call screen in the app: it swells with your voice while it listens, the whole
 * mark turns while it thinks, and the ring ripples while it speaks. The silver metal is the only gradient on the page.
 */
export function Orb({ phase }: { phase: Phase }) {
  const id = useId().replace(/:/g, "");
  const reduce = useReducedMotion();
  const [turn, setTurn] = useState(0);
  useEffect(() => { if (phase === "thinking") setTurn((t) => t + 1); }, [phase]);

  const swell =
    reduce ? [1] :
    phase === "listening" ? [1, 1.09, 1.02, 1.11, 1.03, 1] :
    phase === "speaking" ? [1, 1.04, 1, 1.035, 1] :
    [1, 1.03, 1];
  const swellTime = phase === "listening" ? 1.9 : phase === "speaking" ? 1.3 : 4;
  const ripples = !reduce && (phase === "listening" || phase === "speaking") ? [0, 1, 2] : [];

  return (
    <svg viewBox="-62 -62 124 124" role="img" aria-label={`The Pocketnode mark, ${phase === "idle" ? "resting" : phase}`}>
      <defs>
        <linearGradient id={`m${id}`} x1="-30" y1="-30" x2="30" y2="30" gradientUnits="userSpaceOnUse">
          <stop offset="0" style={{ stopColor: "var(--orb-a)" }} />
          <stop offset="0.5" style={{ stopColor: "var(--orb-b)" }} />
          <stop offset="1" style={{ stopColor: "var(--orb-c)" }} />
        </linearGradient>
      </defs>
      {ripples.map((i) => (
        <motion.circle key={`${phase}-${i}`} r={30} fill="none" stroke={`url(#m${id})`} strokeWidth={1.2}
          initial={{ scale: 1.05, opacity: 0.38 }} animate={{ scale: 1.9, opacity: 0 }}
          transition={{ duration: phase === "speaking" ? 1.6 : 2.4, delay: i * (phase === "speaking" ? 0.5 : 0.8), repeat: Infinity, ease: "easeOut" }} />
      ))}
      <motion.g animate={{ rotate: reduce ? 0 : turn * 360 }} transition={{ duration: 2.2, ease: [0.55, 0, 0.2, 1] }} style={{ transformOrigin: "0px 0px" }}>
        <motion.g animate={{ scale: swell }} transition={{ duration: swellTime, repeat: reduce ? 0 : Infinity, ease: "easeInOut" }} style={{ transformOrigin: "0px 0px" }}>
          <path d="M28.98 -7.76 A30 30 0 1 1 7.76 -28.98" fill="none" stroke={`url(#m${id})`} strokeWidth={9} strokeLinecap="round" />
          <circle cx={21.21} cy={-21.21} r={8} fill={`url(#m${id})`} />
        </motion.g>
      </motion.g>
    </svg>
  );
}

/** Thin tick marks and a small satellite turning slowly behind the hero. Decoration only. */
export function HeroRing() {
  const reduce = useReducedMotion();
  const ticks = Array.from({ length: 96 }, (_, i) => i);
  return (
    <svg viewBox="-200 -200 400 400" aria-hidden="true">
      <motion.g animate={reduce ? undefined : { rotate: 360 }} transition={{ duration: 140, repeat: Infinity, ease: "linear" }} style={{ transformOrigin: "0px 0px" }}>
        {ticks.map((i) => {
          const a = (i / ticks.length) * Math.PI * 2, long = i % 8 === 0, r1 = 188, r2 = long ? 174 : 181;
          return <line key={i} x1={Math.cos(a) * r1} y1={Math.sin(a) * r1} x2={Math.cos(a) * r2} y2={Math.sin(a) * r2} stroke="currentColor" strokeOpacity={long ? 0.5 : 0.22} strokeWidth={long ? 1.4 : 1} />;
        })}
      </motion.g>
      <circle r={150} fill="none" stroke="currentColor" strokeOpacity={0.14} strokeWidth={1} />
      <circle r={118} fill="none" stroke="currentColor" strokeOpacity={0.1} strokeWidth={1} strokeDasharray="2 7" />
      <motion.g animate={reduce ? undefined : { rotate: -360 }} transition={{ duration: 36, repeat: Infinity, ease: "linear" }} style={{ transformOrigin: "0px 0px" }}>
        <circle cx={150} cy={0} r={4} fill="currentColor" fillOpacity={0.75} />
      </motion.g>
    </svg>
  );
}
