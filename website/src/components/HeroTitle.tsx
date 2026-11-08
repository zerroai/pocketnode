"use client";
import { motion, useReducedMotion } from "motion/react";

/** The headline, one word at a time. */
export function HeroTitle({ text }: { text: string }) {
  const reduce = useReducedMotion();
  return (
    <h1 className="display">
      {text.split(" ").map((w, i) => (
        <span key={i} style={{ display: "inline-block", overflow: "hidden", verticalAlign: "top", paddingBottom: "0.08em" }}>
          <motion.span className="word" initial={reduce ? false : { y: "105%" }} animate={{ y: 0 }} transition={{ duration: 0.8, delay: 0.08 * i, ease: [0.2, 0.7, 0.2, 1] }}>{w}&nbsp;</motion.span>
        </span>
      ))}
    </h1>
  );
}
