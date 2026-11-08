"use client";
import { motion, useScroll, useSpring } from "motion/react";

/** A thin line at the top that shows how far down the page you are. */
export function ScrollBar() {
  const { scrollYProgress } = useScroll();
  const x = useSpring(scrollYProgress, { stiffness: 140, damping: 28, mass: 0.3 });
  return <motion.div className="progress" style={{ scaleX: x }} aria-hidden="true" />;
}
