"use client";
import type { ReactNode, PointerEvent } from "react";

/** A card with a faint glow that follows the pointer. */
export function SpotCard({ children, className = "card" }: { children: ReactNode; className?: string }) {
  const move = (e: PointerEvent<HTMLDivElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    e.currentTarget.style.setProperty("--mx", `${e.clientX - r.left}px`);
    e.currentTarget.style.setProperty("--my", `${e.clientY - r.top}px`);
  };
  return <div className={`${className} spot`} onPointerMove={move}>{children}</div>;
}
