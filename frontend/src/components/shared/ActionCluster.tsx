/**
 * 2026-05-14 (mobile-first redesign, P2) — Action-cluster primitive.
 *
 * Action button rows on cards (e.g. InlineRunDetail's 7-button cluster:
 * Pause · Cancel · Re-run · View · Push · Download · Open PR) wrap to
 * 2-3 lines on phones, putting destructive buttons (Cancel) inline with
 * primary actions (Open PR) → accidental-tap risk. The cluster is also
 * the heaviest visual element on a card, dominating the title.
 *
 * This primitive collapses the cluster on phone:
 *   • One primary action stays visible (the most important verb).
 *   • Everything else moves into a `…` overflow menu (bottom sheet on
 *     mobile so it's thumb-friendly).
 *   • Desktop renders the full inline row unchanged.
 *
 * Usage:
 *   <ActionCluster
 *     primary={{label: 'Open PR', onClick: openPr}}
 *     actions={[
 *       {label: 'Re-run', onClick: rerun},
 *       {label: 'Download', onClick: download},
 *       {label: 'Cancel', onClick: cancel, danger: true},
 *     ]}
 *   />
 *
 * The primary action renders as a Submit-sized button on phone. Secondary
 * actions stay in the overflow menu (54px tap-friendly rows). On desktop
 * everything is inline with hover affordances.
 */
import {useEffect, useRef, useState} from 'react';
import {createPortal} from 'react-dom';
import {MoreHorizontal} from 'lucide-react';
import {useIsPhone} from '@/hooks/useIsMobile';
import {cn} from '@/lib/utils';

export interface ActionItem {
  label: string;
  onClick: () => void;
  /** Render with the destructive accent (red text). */
  danger?: boolean;
  /** Disable the action. */
  disabled?: boolean;
  /** Optional icon to show beside the label. */
  icon?: React.ReactNode;
  /** Optional title attribute for hover tooltip. */
  title?: string;
}

interface ActionClusterProps {
  /** The single most-important action; always visible on phone. */
  primary?: ActionItem;
  /** Secondary actions; surface inline on desktop, in an overflow sheet on phone. */
  actions: ActionItem[];
  /** Extra className for the outer wrapper. */
  className?: string;
  /** ARIA label for the overflow trigger button. */
  ariaLabel?: string;
}

export default function ActionCluster({
  primary,
  actions,
  className,
  ariaLabel = 'More actions',
}: ActionClusterProps) {
  const isPhone = useIsPhone();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);

  // Body scroll lock while the sheet is open
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // ESC closes the sheet
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const ActionBtn = ({a, primaryVariant = false}: {a: ActionItem; primaryVariant?: boolean}) => (
    <button
      type='button'
      onClick={() => {
        a.onClick();
        setOpen(false);
      }}
      disabled={a.disabled}
      title={a.title}
      className={cn(
        'inline-flex items-center justify-center gap-1.5 rounded-md text-xs font-medium transition-colors',
        primaryVariant
          ? 'px-3 py-2 bg-accent-blue text-primary hover:bg-accent-blue-hover'
          : 'px-2.5 py-1.5 border border-[var(--border-default)]',
        !primaryVariant && a.danger && 'text-accent-red hover:bg-accent-red/10',
        !primaryVariant && !a.danger && 'text-secondary hover:bg-[var(--gray-200)]',
        a.disabled && 'opacity-50 cursor-not-allowed',
      )}
    >
      {a.icon}
      {a.label}
    </button>
  );

  // ── Desktop: everything inline ──────────────────────────────────────────
  if (!isPhone) {
    return (
      <div className={cn('flex items-center gap-1.5 flex-wrap', className)}>
        {actions.map((a, i) => (
          <ActionBtn key={`d-${i}`} a={a} />
        ))}
        {primary && <ActionBtn a={primary} primaryVariant />}
      </div>
    );
  }

  // ── Phone: primary + ⋯ overflow ─────────────────────────────────────────
  return (
    <>
      <div className={cn('flex items-center gap-2', className)}>
        {primary && (
          <button
            type='button'
            onClick={primary.onClick}
            disabled={primary.disabled}
            className={cn(
              'flex-1 min-h-[44px] px-4 rounded-md text-sm font-semibold bg-accent-blue text-primary',
              'inline-flex items-center justify-center gap-1.5',
              primary.disabled && 'opacity-50 cursor-not-allowed',
            )}
          >
            {primary.icon}
            {primary.label}
          </button>
        )}
        {actions.length > 0 && (
          <button
            ref={triggerRef}
            type='button'
            onClick={() => setOpen(true)}
            aria-label={ariaLabel}
            aria-haspopup='menu'
            aria-expanded={open}
            className='inline-flex items-center justify-center min-w-[44px] min-h-[44px] rounded-md border border-[var(--border-default)] text-secondary'
          >
            <MoreHorizontal className='h-5 w-5' aria-hidden='true' />
          </button>
        )}
      </div>

      {open &&
        createPortal(
          <>
            <div
              onClick={() => setOpen(false)}
              style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(0,0,0,0.55)',
                backdropFilter: 'blur(2px)',
                WebkitBackdropFilter: 'blur(2px)',
                zIndex: 70,
              }}
              aria-hidden='true'
            />
            <div
              role='menu'
              aria-label={ariaLabel}
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
              }}
            >
              {/* Drag handle */}
              <div
                aria-hidden='true'
                style={{
                  width: 36,
                  height: 4,
                  background: 'var(--border-default)',
                  borderRadius: 999,
                  margin: '6px auto 10px',
                }}
              />
              <ul style={{listStyle: 'none', padding: 0, margin: 0}}>
                {actions.map((a, i) => (
                  <li key={`m-${i}`}>
                    <button
                      type='button'
                      onClick={() => {
                        a.onClick();
                        setOpen(false);
                      }}
                      disabled={a.disabled}
                      role='menuitem'
                      className={cn(
                        'w-full text-left px-4 py-3 min-h-[54px] text-sm flex items-center gap-2 border-b border-[var(--border-default)]',
                        a.danger ? 'text-accent-red' : 'text-secondary',
                        a.disabled && 'opacity-50 cursor-not-allowed',
                      )}
                    >
                      {a.icon}
                      <span>{a.label}</span>
                    </button>
                  </li>
                ))}
              </ul>
              <button
                type='button'
                onClick={() => setOpen(false)}
                className='block w-full text-center py-3 mt-1 min-h-[44px] text-sm text-muted'
              >
                Cancel
              </button>
            </div>
          </>,
          document.body,
        )}
    </>
  );
}
