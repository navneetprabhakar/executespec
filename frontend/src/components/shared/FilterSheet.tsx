/**
 * 2026-05-14 (mobile-first redesign, P6) — Filter-sheet primitive.
 *
 * Filter / chip groups (e.g. InsightsPage's project-filter rail,
 * LibraryPage's 3 selects + search + "Hide locked" toggle, MigrationsPage's
 * category + tier + sort rail) wrap to 3–4 rows on phones, eating 30%+
 * of vertical real estate before any content shows.
 *
 * Best-in-class apps (Stripe, GitHub Mobile, Vercel) collapse filter rows
 * into a single "Filter (N)" button on phone that opens a bottom sheet.
 * This primitive does the same:
 *   - Desktop / ≥ md: renders the filter contents inline (passed as
 *     children).
 *   - Phone: hides them behind a 44px "Filter (N)" button at the top of
 *     the page; tap opens a bottom-sheet that hosts the same children
 *     with thumb-friendly spacing.
 *
 * Usage:
 *   <FilterSheet activeCount={appliedFilterCount}>
 *     <YourSelectOne />
 *     <YourSelectTwo />
 *     <YourSearchInput />
 *   </FilterSheet>
 *
 * The activeCount is the visible badge ("Filter (3)") so users know
 * something is applied without opening the sheet.
 */
import {useEffect, useRef, useState, type ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {SlidersHorizontal, X} from 'lucide-react';
import {useIsPhone} from '@/hooks/useIsMobile';

interface FilterSheetProps {
  children: ReactNode;
  /** Number of filters currently applied; renders next to "Filter". */
  activeCount?: number;
  /** Label for the trigger button. Default "Filter". */
  triggerLabel?: string;
  /** Sheet header title (defaults to "Filters"). */
  sheetTitle?: string;
  /** Optional reset button — clears filters; only renders when callback provided. */
  onReset?: () => void;
  /** Extra className for the desktop inline wrapper. */
  inlineClassName?: string;
}

export default function FilterSheet({
  children,
  activeCount,
  triggerLabel = 'Filter',
  sheetTitle = 'Filters',
  onReset,
  inlineClassName,
}: FilterSheetProps) {
  const isPhone = useIsPhone();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  // Body scroll lock + ESC dismissal while sheet is open
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      document.body.style.overflow = prev;
      window.removeEventListener('keydown', onKey);
    };
  }, [open]);

  if (!isPhone) {
    // Desktop: render the children inline. Caller controls layout.
    return <div className={inlineClassName}>{children}</div>;
  }

  return (
    <>
      <button
        ref={triggerRef}
        type='button'
        onClick={() => setOpen(true)}
        aria-haspopup='dialog'
        aria-expanded={open}
        className='inline-flex items-center gap-2 px-3 py-2 min-h-[44px] rounded-md border border-[var(--border-default)] text-sm text-secondary'
      >
        <SlidersHorizontal className='h-4 w-4' aria-hidden='true' />
        <span>{triggerLabel}</span>
        {activeCount != null && activeCount > 0 && (
          <span
            className='ml-1 inline-flex items-center justify-center rounded-full bg-accent-blue/20 text-accent-blue text-[11px] font-semibold px-1.5'
            aria-label={`${activeCount} active`}
          >
            {activeCount}
          </span>
        )}
      </button>

      {open &&
        createPortal(
          <>
            <div
              onClick={() => setOpen(false)}
              aria-hidden='true'
              style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(0,0,0,0.55)',
                backdropFilter: 'blur(2px)',
                WebkitBackdropFilter: 'blur(2px)',
                zIndex: 70,
              }}
            />
            <div
              role='dialog'
              aria-label={sheetTitle}
              aria-modal='true'
              style={{
                position: 'fixed',
                left: 0,
                right: 0,
                bottom: 0,
                zIndex: 71,
                background: 'var(--gray-100)',
                borderTopLeftRadius: 14,
                borderTopRightRadius: 14,
                borderTop: '1px solid var(--border-default)',
                paddingTop: 8,
                paddingBottom: 'max(8px, env(safe-area-inset-bottom))',
                maxHeight: '85vh',
                display: 'flex',
                flexDirection: 'column',
              }}
            >
              <div
                aria-hidden='true'
                style={{
                  width: 36,
                  height: 4,
                  background: 'var(--border-default)',
                  borderRadius: 999,
                  margin: '6px auto 8px',
                }}
              />
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '4px 12px 8px',
                  borderBottom: '1px solid var(--border-default)',
                }}
              >
                <div style={{fontSize: 14, fontWeight: 600, color: 'var(--text-primary)'}}>
                  {sheetTitle}
                </div>
                <button
                  type='button'
                  onClick={() => setOpen(false)}
                  aria-label='Close filters'
                  style={{
                    minWidth: 44,
                    minHeight: 44,
                    background: 'transparent',
                    border: 'none',
                    color: 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  <X size={20} aria-hidden='true' />
                </button>
              </div>
              <div
                style={{
                  flex: 1,
                  overflowY: 'auto',
                  padding: '12px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 12,
                }}
              >
                {children}
              </div>
              {onReset && (
                <div
                  style={{
                    padding: '8px 12px',
                    borderTop: '1px solid var(--border-default)',
                    display: 'flex',
                    gap: 8,
                  }}
                >
                  <button
                    type='button'
                    onClick={() => {
                      onReset();
                    }}
                    className='flex-1 min-h-[44px] rounded-md border border-[var(--border-default)] text-sm text-secondary'
                  >
                    Reset
                  </button>
                  <button
                    type='button'
                    onClick={() => setOpen(false)}
                    className='flex-1 min-h-[44px] rounded-md bg-accent-blue text-primary text-sm font-semibold'
                  >
                    Apply
                  </button>
                </div>
              )}
            </div>
          </>,
          document.body,
        )}
    </>
  );
}
