/**
 * 2026-05-14 UX overhaul — SegmentedTabs.
 *
 * iOS-style segmented tab control with an animated underline indicator
 * that slides between the active tab.
 *
 * Replaces the three ad-hoc tab strips on the project dashboard, run
 * detail, and library detail pages (each of which independently solved
 * the horizontal-scroll problem with different overflow logic, P0-5 in
 * the assessment).
 *
 * Mobile behaviour:
 *   - At ≤ 768 px AND the rendered tab row is wider than the container,
 *     this primitive switches to a horizontal-scroll mode with edge fades
 *     to advertise the overflow visually.
 *   - For lists of 6+ tabs, callers should pass `overflowAs="menu"` so
 *     tabs 5+ collapse into a "More ▾" overflow menu.
 *
 * Indicator:
 *   - Renders as a 2-px brand-blue bar that slides via `left + width`
 *     transition (CSS only, no JS rAF loop).
 *   - Position is measured on mount + every active-tab change + every
 *     resize.
 *
 * A11y:
 *   - role="tablist" on the container.
 *   - role="tab" + aria-selected on each tab.
 *   - Left/Right arrow keys move focus between tabs (Apple HIG segmented
 *     control parity).
 */
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
} from 'react';
import {ChevronDown} from 'lucide-react';

export type SegmentedTab = {
  key: string;
  label: string;
  badge?: number | string | null;
};

type Props = {
  tabs: SegmentedTab[];
  active: string;
  onChange: (key: string) => void;
  // overflowAs="scroll" — let the tab row scroll horizontally on small
  // viewports. overflowAs="menu" — collapse later tabs into a More menu.
  overflowAs?: 'scroll' | 'menu';
  // How many tabs are visible before "More" kicks in (only used when
  // overflowAs === 'menu'). Default 4 (Apple HIG iOS segmented control
  // sweet spot at 375 px viewport.)
  visibleOnPhone?: number;
  className?: string;
  ariaLabel?: string;
};

export default function SegmentedTabs({
  tabs,
  active,
  onChange,
  overflowAs = 'scroll',
  visibleOnPhone = 4,
  className,
  ariaLabel = 'Tabs',
}: Props) {
  const listRef = useRef<HTMLDivElement | null>(null);
  const tabRefs = useRef<Map<string, HTMLButtonElement | null>>(new Map());
  const [indicator, setIndicator] = useState<{left: number; width: number}>({
    left: 0,
    width: 0,
  });
  const [menuOpen, setMenuOpen] = useState(false);
  const [isPhone, setIsPhone] = useState<boolean>(
    () => typeof window !== 'undefined' && window.innerWidth < 768,
  );

  useEffect(() => {
    const onResize = () => setIsPhone(window.innerWidth < 768);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // Decide whether to collapse: only on phone AND mode === menu AND there
  // are more tabs than the visible slot count.
  const collapse = overflowAs === 'menu' && isPhone && tabs.length > visibleOnPhone;
  const visibleTabs = useMemo(
    () => (collapse ? tabs.slice(0, visibleOnPhone) : tabs),
    [tabs, collapse, visibleOnPhone],
  );
  const overflowTabs = useMemo(
    () => (collapse ? tabs.slice(visibleOnPhone) : []),
    [tabs, collapse, visibleOnPhone],
  );

  // If the active tab is in the overflow set, surface it in the visible
  // row by swapping it with the last visible non-active tab. Keeps the
  // user oriented when they navigate via deep-link.
  const surfacedActive = collapse && overflowTabs.some((t) => t.key === active);
  const renderedVisibleTabs = useMemo(() => {
    if (!surfacedActive) return visibleTabs;
    const activeTab = overflowTabs.find((t) => t.key === active);
    if (!activeTab) return visibleTabs;
    const cloned = [...visibleTabs];
    cloned[cloned.length - 1] = activeTab;
    return cloned;
  }, [visibleTabs, overflowTabs, active, surfacedActive]);

  const updateIndicator = useCallback(() => {
    const list = listRef.current;
    const el = tabRefs.current.get(active);
    if (!list || !el) return;
    const listRect = list.getBoundingClientRect();
    const elRect = el.getBoundingClientRect();
    setIndicator({
      left: elRect.left - listRect.left + list.scrollLeft,
      width: elRect.width,
    });
  }, [active]);

  useLayoutEffect(() => {
    updateIndicator();
  }, [updateIndicator, renderedVisibleTabs.length]);

  useEffect(() => {
    const onResize = () => updateIndicator();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [updateIndicator]);

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    e.preventDefault();
    const idx = renderedVisibleTabs.findIndex((t) => t.key === active);
    if (idx < 0) return;
    const delta = e.key === 'ArrowRight' ? 1 : -1;
    const next =
      renderedVisibleTabs[(idx + delta + renderedVisibleTabs.length) % renderedVisibleTabs.length];
    if (next) {
      onChange(next.key);
      tabRefs.current.get(next.key)?.focus();
    }
  };

  const handleTabClick = (key: string) => (e: MouseEvent<HTMLButtonElement>) => {
    e.preventDefault();
    onChange(key);
  };

  return (
    <div className={'segmented-tabs ' + (className || '')} style={{position: 'relative'}}>
      <div
        ref={listRef}
        role='tablist'
        aria-label={ariaLabel}
        onKeyDown={onKey}
        style={{
          position: 'relative',
          display: 'flex',
          alignItems: 'flex-end',
          gap: 0,
          borderBottom: '1px solid var(--hairline)',
          overflowX: overflowAs === 'scroll' ? 'auto' : 'visible',
          scrollbarWidth: 'none',
          msOverflowStyle: 'none',
          touchAction: 'pan-x',
        }}
      >
        {renderedVisibleTabs.map((tab) => {
          const isActive = tab.key === active;
          return (
            <button
              type='button'
              key={tab.key}
              ref={(el) => {
                if (el) tabRefs.current.set(tab.key, el);
                else tabRefs.current.delete(tab.key);
              }}
              role='tab'
              aria-selected={isActive}
              tabIndex={isActive ? 0 : -1}
              onClick={handleTabClick(tab.key)}
              className='press-effect'
              style={{
                position: 'relative',
                appearance: 'none',
                background: 'transparent',
                border: 'none',
                color: isActive ? 'var(--text-primary)' : 'var(--text-muted)',
                fontSize: 14,
                fontWeight: isActive ? 600 : 500,
                fontFamily: 'inherit',
                padding: '12px 14px',
                cursor: 'pointer',
                minHeight: 'var(--hit-min-mobile)',
                whiteSpace: 'nowrap',
                outline: 'none',
              }}
            >
              {tab.label}
              {tab.badge != null && tab.badge !== '' && (
                <span
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginLeft: 6,
                    padding: '0 6px',
                    minWidth: 18,
                    height: 18,
                    fontSize: 11,
                    fontWeight: 600,
                    color: isActive ? 'var(--text-primary)' : 'var(--text-muted)',
                    background: 'var(--surface-sunken)',
                    border: '1px solid var(--hairline)',
                    borderRadius: 'var(--radius-pill)',
                  }}
                >
                  {tab.badge}
                </span>
              )}
            </button>
          );
        })}
        {collapse && (
          <button
            type='button'
            onClick={() => setMenuOpen((v) => !v)}
            aria-haspopup='menu'
            aria-expanded={menuOpen}
            className='press-effect'
            style={{
              appearance: 'none',
              background: 'transparent',
              border: 'none',
              color: 'var(--text-muted)',
              fontSize: 14,
              fontWeight: 500,
              fontFamily: 'inherit',
              padding: '12px 12px',
              cursor: 'pointer',
              minHeight: 'var(--hit-min-mobile)',
              whiteSpace: 'nowrap',
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            More
            <ChevronDown size={14} aria-hidden='true' />
          </button>
        )}
        <span
          className='segmented-tab-indicator'
          style={{
            left: indicator.left,
            width: indicator.width,
          }}
          aria-hidden='true'
        />
      </div>
      {collapse && menuOpen && (
        <div
          role='menu'
          style={{
            position: 'absolute',
            top: '100%',
            right: 0,
            marginTop: 4,
            minWidth: 180,
            background: 'var(--surface-raised)',
            border: '1px solid var(--border-default)',
            borderRadius: 'var(--radius-card)',
            boxShadow: 'var(--shadow-overlay)',
            padding: 4,
            zIndex: 30,
          }}
          onMouseLeave={() => setMenuOpen(false)}
        >
          {overflowTabs.map((tab) => {
            const isActive = tab.key === active;
            return (
              <button
                key={tab.key}
                type='button'
                role='menuitem'
                onClick={() => {
                  onChange(tab.key);
                  setMenuOpen(false);
                }}
                style={{
                  width: '100%',
                  textAlign: 'left',
                  padding: '10px 12px',
                  border: 'none',
                  background: isActive ? 'var(--surface-sunken)' : 'transparent',
                  color: 'var(--text-primary)',
                  fontFamily: 'inherit',
                  fontSize: 14,
                  fontWeight: isActive ? 600 : 500,
                  cursor: 'pointer',
                  borderRadius: 'var(--radius-control)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  gap: 12,
                  minHeight: 'var(--hit-min-mobile)',
                }}
              >
                <span>{tab.label}</span>
                {tab.badge != null && tab.badge !== '' && (
                  <span
                    style={{
                      fontSize: 11,
                      color: 'var(--text-muted)',
                    }}
                  >
                    {tab.badge}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
