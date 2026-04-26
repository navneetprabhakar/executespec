import { ReactNode, useEffect, useRef, useState } from 'react';
import { cn } from '@/lib/utils';

interface TooltipProps {
  /** Text to display. If empty, no tooltip is rendered. */
  text: string | undefined | null;
  /** Element being annotated (typically an icon button). */
  children: ReactNode;
  /** Side relative to the trigger. Default: top. */
  side?: 'top' | 'bottom' | 'left' | 'right';
  /** Delay in ms before show. Default: 250 — long enough to ignore drive-by hovers. */
  delay?: number;
  /** Optional className applied to the trigger wrapper (a `<span>`). */
  className?: string;
  /** Allow the tooltip to wrap onto multiple lines. Default: false (single-line nowrap). */
  wrap?: boolean;
  /** Max width when `wrap` is true. Default: 16rem. */
  maxWidthClass?: string;
}

/**
 * Lightweight tooltip primitive — CSS+state only, no portal, no Radix.
 * Reveals on hover/focus after a short delay; hides on leave/blur.
 *
 * Usage: wrap an icon-only button to surface its purpose.
 *
 * <Tooltip text="Reset chat history">
 *   <button onClick={...}><RotateCcw /></button>
 * </Tooltip>
 *
 * Falls through cleanly when `text` is falsy — useful when the label is
 * conditional and you don't want to add boilerplate around it.
 */
export function Tooltip({
  text,
  children,
  side = 'top',
  delay = 250,
  className,
  wrap = false,
  maxWidthClass = 'max-w-64',
}: TooltipProps) {
  const [open, setOpen] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const show = () => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => setOpen(true), delay);
  };
  const hide = () => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    setOpen(false);
  };

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current);
  }, []);

  if (!text) return <>{children}</>;

  return (
    <span
      className={cn('relative inline-flex', className)}
      onMouseEnter={show}
      onMouseLeave={hide}
      onFocus={show}
      onBlur={hide}
    >
      {children}
      {open && (
        <span
          role="tooltip"
          className={cn(
            'absolute z-50 px-2 py-1 text-[11px] font-medium pointer-events-none',
            wrap ? `${maxWidthClass} leading-snug whitespace-normal` : 'whitespace-nowrap',
            'rounded-sm border border-[var(--border-default)] bg-[var(--gray-400)] text-primary shadow-md',
            'animate-in fade-in-0 zoom-in-95 duration-100',
            side === 'top' && 'bottom-full left-1/2 -translate-x-1/2 mb-1.5',
            side === 'bottom' && 'top-full left-1/2 -translate-x-1/2 mt-1.5',
            side === 'left' && 'right-full top-1/2 -translate-y-1/2 mr-1.5',
            side === 'right' && 'left-full top-1/2 -translate-y-1/2 ml-1.5',
          )}
        >
          {text}
        </span>
      )}
    </span>
  );
}
