"use client";
import { useEffect, useState } from "react";
import { useReducedMotion } from "motion/react";

/** Types its text out one letter at a time, then stops. Shows the whole text at once for people who asked for less motion. */
export function Typewriter({ text, speed = 24, caret = true }: { text: string; speed?: number; caret?: boolean }) {
  const reduce = useReducedMotion();
  const [n, setN] = useState(reduce ? text.length : 0);
  useEffect(() => {
    if (reduce) { setN(text.length); return; }
    setN(0);
    const t = setInterval(() => setN((v) => { if (v >= text.length) { clearInterval(t); return v; } return v + 1; }), speed);
    return () => clearInterval(t);
  }, [text, speed, reduce]);
  return <span className={caret && n < text.length ? "caret" : undefined}>{text.slice(0, n)}</span>;
}
