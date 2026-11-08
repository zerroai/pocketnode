"use client";
import { Orb } from "./Orb";

/** The mark, slowly breathing, for the end of the page. */
export function MiniOrb() {
  return <div className="mini-orb"><Orb phase="idle" /></div>;
}
