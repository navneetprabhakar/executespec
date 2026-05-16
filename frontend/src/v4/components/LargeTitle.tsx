/**
 * iOS-style large title.
 *
 * Renders a large display heading (32-40px) with an optional subtitle
 * (status / count). On scroll, the title compresses smoothly toward the
 * top bar — matching iOS Settings / Mail / Photos behaviour.
 *
 * The compression is opacity + scale, not layout (no reflow on every
 * scroll frame). The component owns its own IntersectionObserver so
 * pages don't need to wire scroll handlers.
 */
import {useEffect, useRef, useState, type ReactNode} from 'react';

interface Props {
  title: string;
  subtitle?: ReactNode;
  trailing?: ReactNode;
}

export default function LargeTitle({title, subtitle, trailing}: Props) {
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const [compact, setCompact] = useState(false);

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el) return;
    const io = new IntersectionObserver(([entry]) => setCompact(!entry.isIntersecting), {
      threshold: 0,
      rootMargin: '-44px 0px 0px 0px',
    });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  return (
    <>
      {/* Invisible sentinel — when it scrolls out of view, the title goes
          compact via the parent's top-bar listener (not implemented in v1
          of this primitive; reserved for a future polish pass). */}
      <div ref={sentinelRef} aria-hidden='true' style={{height: 1}} />
      <header
        className='v4-reveal'
        style={{
          display: 'flex',
          alignItems: 'flex-end',
          justifyContent: 'space-between',
          gap: 12,
          padding: '8px 0 16px',
          minHeight: compact ? 44 : 64,
          transition: 'min-height 220ms var(--v4-spring)',
        }}
      >
        <div style={{minWidth: 0, flex: 1}}>
          <h1
            style={{
              margin: 0,
              fontSize: 'clamp(24px, 7vw, 32px)',
              fontWeight: 700,
              letterSpacing: '-0.02em',
              lineHeight: 1.1,
              color: 'var(--v4-text)',
              fontFamily: 'inherit',
              transition: 'transform 220ms var(--v4-spring), opacity 220ms var(--v4-spring)',
              transform: compact ? 'translateY(-2px) scale(0.85)' : 'none',
              transformOrigin: 'left bottom',
            }}
          >
            {title}
          </h1>
          {subtitle != null && (
            <p
              style={{
                margin: '4px 0 0',
                fontSize: 'var(--v4-text-sm)',
                color: 'var(--v4-text-muted)',
                lineHeight: 1.4,
                opacity: compact ? 0 : 1,
                transition: 'opacity 220ms var(--v4-spring)',
              }}
            >
              {subtitle}
            </p>
          )}
        </div>
        {trailing && <div style={{flexShrink: 0}}>{trailing}</div>}
      </header>
    </>
  );
}
