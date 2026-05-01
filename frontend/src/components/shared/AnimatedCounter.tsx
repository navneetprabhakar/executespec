import {useEffect, useRef} from 'react';
import {useMotionValue, useSpring, useTransform, motion} from 'framer-motion';

interface AnimatedCounterProps {
  value: number;
  format?: (n: number) => string;
  className?: string;
}

export default function AnimatedCounter({value, format, className}: AnimatedCounterProps) {
  const mv = useMotionValue(0);
  const spring = useSpring(mv, {stiffness: 80, damping: 20});
  const display = useTransform(spring, (v) =>
    format ? format(v) : Math.round(v).toLocaleString(),
  );

  useEffect(() => {
    mv.set(value);
  }, [value, mv]);

  return <motion.span className={className}>{display}</motion.span>;
}
