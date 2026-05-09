import {useEffect, useRef, type RefObject} from 'react';

/**
 * F-01 fix (2026-05-09) — extract the focus-trap / Esc / body-scroll-lock
 * / focus-restore-on-close essentials from `<ResponsiveModal>` into a
 * standalone hook so that bespoke v3 modals (ConversationModal,
 * FreeCapBlockingModal, RepoAttachPopover, RunPreviewPane, CmdK) can
 * keep their custom layouts while getting the same a11y wins as the
 * v2 ResponsiveModal-based modals.
 *
 * Migrating wholesale to ResponsiveModal would force the v3 modals to
 * fit its `flex-1 overflow-y-auto p-4` body; most have bespoke split
 * layouts (header + scroll + composer) that don't compose with that
 * shape. This hook covers the 5 a11y essentials independent of layout.
 *
 * Usage:
 *   const panelRef = useRef<HTMLDivElement>(null);
 *   useModalA11y(open, onClose, {panelRef, disableDismiss: false});
 *   return <div ref={panelRef} role='dialog' aria-modal='true'>...</div>;
 *
 * NOTE: The caller is still responsible for `role='dialog'` +
 * `aria-modal='true'` + `aria-label` (or `aria-labelledby`) attributes
 * on the panel root — that markup belongs in the modal, not here.
 */
export interface UseModalA11yOptions {
  /** Ref to the modal panel root. Used for the focus trap. Required. */
  panelRef: RefObject<HTMLElement | null>;
  /** When true, suppresses Esc-to-close. Backdrop click is the caller's
   *  responsibility — this hook doesn't render the backdrop. */
  disableDismiss?: boolean;
}

const FOCUSABLE =
  'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function useModalA11y(
  open: boolean,
  onClose: () => void,
  {panelRef, disableDismiss = false}: UseModalA11yOptions,
): void {
  const restoreFocusRef = useRef<HTMLElement | null>(null);

  // Esc to close.
  useEffect(() => {
    if (!open || disableDismiss) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose, disableDismiss]);

  // Body scroll lock.
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  // Focus trap + initial focus + focus restore.
  useEffect(() => {
    if (!open) {
      if (restoreFocusRef.current && typeof restoreFocusRef.current.focus === 'function') {
        restoreFocusRef.current.focus();
        restoreFocusRef.current = null;
      }
      return;
    }
    restoreFocusRef.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    if (!panel) return;
    // Defer initial focus so children with autoFocus + this hook don't
    // race; if the modal already focused something itself, we don't
    // override it.
    requestAnimationFrame(() => {
      const active = document.activeElement;
      if (active && panel.contains(active)) return;
      const first = panel.querySelector<HTMLElement>(FOCUSABLE);
      first?.focus();
    });

    const trap = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      const nodes = panel.querySelectorAll<HTMLElement>(FOCUSABLE);
      if (nodes.length === 0) return;
      const first = nodes[0];
      const last = nodes[nodes.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', trap);
    return () => document.removeEventListener('keydown', trap);
  }, [open, panelRef]);
}
