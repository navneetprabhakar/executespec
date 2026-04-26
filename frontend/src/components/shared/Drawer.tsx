/**
 * Drawer — right-side slide-over for inline settings flows.
 *
 * Phase 1.1 #13: replaces "modal that bounces to a separate page" pattern.
 * NewRunModal asking "you need to add a GitHub PAT first" used to
 * navigate the user to /settings, losing every field they'd typed. The
 * drawer pattern keeps the parent context alive — open the drawer over
 * NewRunModal, save, close, parent picks up the new value.
 *
 * Usage:
 *
 *   <Drawer
 *     open={drawerOpen}
 *     title="Add GitHub PAT"
 *     onClose={() => setDrawerOpen(false)}
 *   >
 *     <PatForm onSaved={() => { reloadSettings(); setDrawerOpen(false); }} />
 *   </Drawer>
 *
 * Body-scroll lock + Escape key close + first-element focus on open.
 * Stacks above modals (z-50 + backdrop). Does not rely on any portal /
 * external library — pure React + Tailwind, matching the rest of the
 * codebase's primitive style (Tooltip, ResponsiveModal).
 *
 * Cross-component pattern: a parent that wants to embed a drawer for
 * "Add API key", "Add GitHub PAT", or "Edit reviewer list" should hold
 * its own form state, render the trigger button, and pass `open` +
 * `onClose` here. The drawer doesn't manage its own visibility.
 */

import { ReactNode, useEffect, useRef } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';

interface DrawerProps {
  open: boolean;
  /** Visible header title — required for a11y. */
  title: string;
  /** Optional sub-line under the title (one short sentence). */
  description?: string;
  /** Slot for the form / panel body. */
  children: ReactNode;
  onClose: () => void;
  /** Width class. Default `w-[420px]` (snug for a single form column). */
  widthClass?: string;
  /**
   * If false, clicking the backdrop or pressing Escape will NOT close
   * the drawer — useful when a form has unsaved changes and the parent
   * wants its own confirm-discard prompt. Default true.
   */
  dismissOnBackdrop?: boolean;
}

export function Drawer({
  open,
  title,
  description,
  children,
  onClose,
  widthClass = 'w-[420px]',
  dismissOnBackdrop = true,
}: DrawerProps) {
  const closeBtnRef = useRef<HTMLButtonElement>(null);

  // Body scroll lock + initial focus + Escape-to-close.
  useEffect(() => {
    if (!open) return;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    closeBtnRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && dismissOnBackdrop) onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      document.body.style.overflow = prevOverflow;
      window.removeEventListener('keydown', onKey);
    };
  }, [open, dismissOnBackdrop, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex" role="dialog" aria-modal="true" aria-label={title}>
      {/* Backdrop */}
      <div
        className="flex-1 bg-black/40 transition-opacity"
        onClick={dismissOnBackdrop ? onClose : undefined}
        aria-hidden
      />
      {/* Panel */}
      <div
        className={cn(
          'flex flex-col bg-[var(--bg-primary)] border-l border-[var(--border-default)] shadow-2xl',
          'animate-in slide-in-from-right duration-200',
          widthClass,
          'max-w-full'
        )}
      >
        <div className="flex items-start justify-between gap-3 px-5 py-4 border-b border-[var(--border-default)]">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-primary truncate">{title}</h2>
            {description && (
              <p className="text-xs text-muted mt-1 leading-snug">{description}</p>
            )}
          </div>
          <button
            ref={closeBtnRef}
            onClick={onClose}
            className="rounded-sm p-1 text-muted hover:text-primary hover:bg-[var(--gray-200)] transition-colors flex-shrink-0"
            aria-label="Close"
            title="Close (Esc)"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
      </div>
    </div>
  );
}
