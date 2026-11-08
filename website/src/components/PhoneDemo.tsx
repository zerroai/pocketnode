"use client";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useEffect, useState } from "react";
import { Typewriter } from "./Typewriter";
import { asset } from "@/lib/config";

/** (milliseconds from the start of the loop, what appears). The words are the same as in the drawn design in brand/mockups. */
const TIMELINE: number[] = [500, 1500, 3000, 3600, 6200, 7400, 9000, 9600];
const LAST = TIMELINE.length;
const LOOP_MS = 14500;

const item = { initial: { opacity: 0, y: 14, scale: 0.98 }, animate: { opacity: 1, y: 0, scale: 1 }, exit: { opacity: 0, y: -8 }, transition: { duration: 0.4, ease: [0.2, 0.7, 0.2, 1] as [number, number, number, number] } };

/**
 * A phone that plays a short chat on its own: a question, a tool card that checks itself off, the answer, then an agent that is added.
 * It is a drawing of the app, not the real app, and it says so to screen readers.
 */
export function PhoneDemo() {
  const reduce = useReducedMotion();
  const [stage, setStage] = useState(0);
  const [run, setRun] = useState(0);

  useEffect(() => {
    if (reduce) { setStage(LAST); return; }
    setStage(0);
    const timers = TIMELINE.map((t, i) => setTimeout(() => setStage(i + 1), t));
    timers.push(setTimeout(() => setRun((r) => r + 1), LOOP_MS));
    return () => timers.forEach(clearTimeout);
  }, [run, reduce]);

  return (
    <div className="phone" role="img" aria-label="An illustration of a chat on the phone. The assistant checks the battery, then sets up an agent that tells you the weather each morning if it will rain.">
      <div className="screen" aria-hidden="true">
        <div className="status"><span>9:41</span><span>5G</span></div>
        <div className="bar">Battery and weather</div>
        <div className="thread">
          <AnimatePresence initial={false}>
            {stage >= 1 && <motion.div key="u1" className="bubble me" {...item}>How much battery do I have left?</motion.div>}
            {stage >= 2 && (
              <motion.div key="t1" className="toolcard" {...item}>
                <img src={asset("/brand/icons/phone.svg")} alt="" />
                <div><b>Phone</b><span>{stage >= 3 ? "Battery 59%, on wifi" : "Checking"}</span></div>
                <div className="state">{stage >= 3 ? <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#F5F6F8" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="m5 12.5 4.5 4.5L19 7.5" /></svg> : <span className="spin" />}</div>
              </motion.div>
            )}
            {stage >= 4 && <motion.div key="a1" className="bubble ai" {...item}><Typewriter text="You have 59% left. I looked it up just now." speed={20} /></motion.div>}
            {stage >= 5 && <motion.div key="u2" className="bubble me" {...item}>Every morning at 7:30 tell me the weather in Accra, but only if it will rain</motion.div>}
            {stage >= 6 && (
              <motion.div key="c1" className="agentcard" {...item}>
                <h4><img src={asset("/brand/icons/shield.svg")} alt="" />{stage >= 8 ? "Agent added" : "Add this agent?"}</h4>
                <p>Weather alert. Every day at 7:30. Checks the Accra forecast and tells you only if it will rain.</p>
                {stage < 8 ? <div className="row"><span className="pill">Not now</span><span className={`pill go${stage >= 7 ? " pressed" : ""}`}>Add agent</span></div> : <div className="row"><span className="pill">Runs at 7:30</span></div>}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
        <div className="composer"><span>+</span><span>Message</span><i /></div>
      </div>
    </div>
  );
}
