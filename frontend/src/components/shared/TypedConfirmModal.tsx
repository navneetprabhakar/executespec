import {useEffect, useState} from 'react';
import ResponsiveModal from './ResponsiveModal';

/**
 * Typed-confirmation modal for destructive operations.
 *
 * Task 10 of the 2026-05-01 fix list. The pattern is industry standard
 * (Stripe, GitHub, Vercel) for irreversible global ops: the user has to
 * type a specific keyword in addition to clicking a button. Cancel is
 * always one click away; Submit is disabled until the typed input
 * matches.
 *
 * Reusable beyond `Halt all runs` — wire it up to any global destructive
 * op (delete account, revoke all sessions, etc).
 */
export interface TypedConfirmModalProps {
  open: boolean;
  /** Modal title — short, action-shaped ("Halt all running operations?"). */
  title: string;
  /** Body copy — describe the consequence in one paragraph. */
  description: React.ReactNode;
  /** Keyword the user must type (case-sensitive). E.g. "HALT", "DELETE". */
  confirmKeyword: string;
  /** Optional secondary input — collected and passed back via onConfirm. */
  reasonLabel?: string;
  reasonRequired?: boolean;
  /** Submit button label. Defaults to the keyword. */
  submitLabel?: string;
  /** True while the destructive action is in flight. Disables both
   *  buttons and shows a spinner-shape on the submit. */
  busy?: boolean;
  onCancel: () => void;
  /** Receives the optional reason text when the user submits. */
  onConfirm: (reason?: string) => void | Promise<void>;
}

export default function TypedConfirmModal({
  open,
  title,
  description,
  confirmKeyword,
  reasonLabel,
  reasonRequired = false,
  submitLabel,
  busy = false,
  onCancel,
  onConfirm,
}: TypedConfirmModalProps) {
  const [typed, setTyped] = useState('');
  const [reason, setReason] = useState('');

  // Clear inputs whenever the modal closes/re-opens so an old keyword
  // doesn't carry over between invocations.
  useEffect(() => {
    if (!open) {
      setTyped('');
      setReason('');
    }
  }, [open]);

  const keywordOk = typed === confirmKeyword;
  const reasonOk = !reasonRequired || reason.trim().length > 0;
  const canSubmit = keywordOk && reasonOk && !busy;

  return (
    <ResponsiveModal
      open={open}
      onClose={busy ? () => {} : onCancel}
      title={title}
      maxWidth='max-w-md'
      disableDismiss={busy}
    >
      <div style={{padding: 16, display: 'flex', flexDirection: 'column', gap: 14}}>
        <div style={{fontSize: 13, lineHeight: 1.55, color: 'var(--text-secondary, #C9D1DB)'}}>
          {description}
        </div>

        {reasonLabel && (
          <label style={{display: 'flex', flexDirection: 'column', gap: 6}}>
            <span style={{fontSize: 11.5, fontWeight: 600, color: 'var(--text-muted, #8A94A6)'}}>
              {reasonLabel}
              {reasonRequired ? ' (required)' : ' (optional)'}
            </span>
            <input
              type='text'
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              disabled={busy}
              placeholder='One-line reason for the audit log'
              style={{
                padding: '8px 10px',
                fontSize: 12.5,
                background: 'var(--bg-base, #0B0D10)',
                border: '1px solid var(--border-default, #242A33)',
                borderRadius: 6,
                color: 'var(--text-primary, #ECEEF2)',
                fontFamily: 'inherit',
              }}
            />
          </label>
        )}

        <label style={{display: 'flex', flexDirection: 'column', gap: 6}}>
          <span style={{fontSize: 11.5, fontWeight: 600, color: 'var(--text-muted, #8A94A6)'}}>
            Type{' '}
            <code style={{color: 'var(--status-error, #EF4444)', fontWeight: 700}}>
              {confirmKeyword}
            </code>{' '}
            to confirm
          </span>
          <input
            type='text'
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            disabled={busy}
            autoFocus
            spellCheck={false}
            autoCapitalize='none'
            autoCorrect='off'
            placeholder={confirmKeyword}
            aria-label={`Type ${confirmKeyword} to confirm`}
            style={{
              padding: '8px 10px',
              fontSize: 13,
              // 2026-05-10 (font-consistency audit) — was generic
              // `'monospace'` which renders OS-default mono (Menlo /
              // Consolas / Liberation Mono). Use the registered mono
              // chain so the typed-confirm input matches every other
              // mono surface in the app.
              fontFamily:
                "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
              background: 'var(--bg-base, #0B0D10)',
              border: `1px solid ${keywordOk ? 'var(--status-success, #10B981)' : 'var(--border-default, #242A33)'}`,
              borderRadius: 6,
              color: 'var(--text-primary, #ECEEF2)',
            }}
          />
        </label>

        <div style={{display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 4}}>
          <button
            type='button'
            onClick={onCancel}
            disabled={busy}
            style={{
              padding: '8px 14px',
              fontSize: 12.5,
              fontWeight: 500,
              background: 'transparent',
              color: 'var(--text-primary, #ECEEF2)',
              border: '1px solid var(--border-default, #242A33)',
              borderRadius: 6,
              cursor: busy ? 'not-allowed' : 'pointer',
              fontFamily: 'inherit',
            }}
          >
            Cancel
          </button>
          <button
            type='button'
            onClick={() => onConfirm(reason.trim() || undefined)}
            disabled={!canSubmit}
            style={{
              padding: '8px 14px',
              fontSize: 12.5,
              fontWeight: 600,
              background: canSubmit ? 'var(--status-error, #EF4444)' : 'var(--gray-400, #333B46)',
              color: '#FFFFFF',
              border: 'none',
              borderRadius: 6,
              cursor: canSubmit ? 'pointer' : 'not-allowed',
              opacity: canSubmit ? 1 : 0.6,
              fontFamily: 'inherit',
            }}
          >
            {busy ? 'Working…' : (submitLabel ?? confirmKeyword)}
          </button>
        </div>
      </div>
    </ResponsiveModal>
  );
}
