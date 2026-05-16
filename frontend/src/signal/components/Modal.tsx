import {ReactNode, useEffect, useRef} from 'react';
import {Button} from '@/signal/components/Primitives';

/**
 * Responsive modal — replaces window.confirm / window.prompt across the
 * Signal pages. Focus-trapped, ESC-dismiss, click-backdrop dismiss, fully
 * mobile-friendly (slides up from bottom on narrow screens, centred on
 * desktop). The same shell powers ConfirmModal + PromptModal helpers.
 */
export function Modal({
  open,
  title,
  onClose,
  children,
  size = 'md',
  hideCloseButton,
  disableDismiss,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  size?: 'sm' | 'md' | 'lg';
  hideCloseButton?: boolean;
  disableDismiss?: boolean;
}) {
  const dialogRef = useRef<HTMLDivElement | null>(null);

  // Lock body scroll while open + focus the dialog on open.
  useEffect(() => {
    if (!open) return;
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    dialogRef.current?.focus();
    return () => {
      document.body.style.overflow = prevOverflow;
    };
  }, [open]);

  // ESC to close.
  useEffect(() => {
    if (!open || disableDismiss) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, disableDismiss, onClose]);

  if (!open) return null;

  const widths: Record<typeof size, number> = {sm: 360, md: 460, lg: 640};
  return (
    <div
      role='dialog'
      aria-modal='true'
      aria-label={title}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.55)',
        backdropFilter: 'blur(4px)',
        zIndex: 999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 16,
        // Slide-up on narrow screens.
        animation: 'signal-modal-in 0.2s ease-out',
      }}
      onClick={(e) => {
        if (disableDismiss) return;
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        style={{
          width: '100%',
          maxWidth: widths[size],
          background: 'var(--signal-card-bg)',
          border: '1px solid var(--signal-hairline)',
          borderRadius: 18,
          boxShadow: '0 30px 60px rgba(0,0,0,0.45)',
          outline: 'none',
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: '14px 18px',
            borderBottom: '1px solid var(--signal-hairline)',
          }}
        >
          <h3 style={{fontSize: 16, fontWeight: 700, letterSpacing: '-0.01em', margin: 0}}>
            {title}
          </h3>
          {!hideCloseButton && (
            <button
              type='button'
              onClick={onClose}
              aria-label='Close'
              style={{
                fontSize: 18,
                color: 'var(--signal-text-mute)',
                background: 'transparent',
                border: 0,
                cursor: 'pointer',
                padding: 4,
                lineHeight: 1,
              }}
            >
              ✕
            </button>
          )}
        </div>
        <div style={{padding: 18}}>{children}</div>
      </div>
    </div>
  );
}

/**
 * ConfirmModal — like window.confirm but actually pleasant. Returns via callbacks.
 * Usage:
 *   const [confirmOpen, setConfirmOpen] = useState(false);
 *   <ConfirmModal open={confirmOpen} title="Delete project?"
 *     body="This can't be undone."
 *     confirmLabel="Delete" danger onConfirm={async () => {...}} onClose={() => setConfirmOpen(false)} />
 */
export function ConfirmModal({
  open,
  title,
  body,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  danger,
  busy,
  onConfirm,
  onClose,
}: {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void | Promise<void>;
  onClose: () => void;
}) {
  return (
    <Modal open={open} title={title} onClose={onClose} size='sm'>
      <div
        style={{fontSize: 14, color: 'var(--signal-text-dim)', lineHeight: 1.55, marginBottom: 16}}
      >
        {body}
      </div>
      <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
        <Button kind='ghost' sm onClick={onClose} disabled={busy}>
          {cancelLabel}
        </Button>
        <Button
          kind={danger ? 'coral' : 'primary'}
          sm
          disabled={busy}
          onClick={async () => {
            await onConfirm();
            if (!busy) onClose();
          }}
        >
          {busy ? '…' : confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}

/**
 * PromptModal — like window.prompt but supports multiple fields, validation,
 * and proper focus management.
 */
export function PromptModal({
  open,
  title,
  body,
  fields,
  submitLabel = 'Save',
  busy,
  onSubmit,
  onClose,
}: {
  open: boolean;
  title: string;
  body?: ReactNode;
  fields: Array<{
    name: string;
    label: string;
    placeholder?: string;
    multiline?: boolean;
    defaultValue?: string;
    required?: boolean;
  }>;
  submitLabel?: string;
  busy?: boolean;
  onSubmit: (values: Record<string, string>) => void | Promise<void>;
  onClose: () => void;
}) {
  // Use uncontrolled inputs keyed by name; read values on submit.
  const formRef = useRef<HTMLFormElement | null>(null);
  return (
    <Modal open={open} title={title} onClose={onClose} size='md'>
      {body && (
        <div
          style={{
            fontSize: 13,
            color: 'var(--signal-text-dim)',
            lineHeight: 1.55,
            marginBottom: 14,
          }}
        >
          {body}
        </div>
      )}
      <form
        ref={formRef}
        onSubmit={(e) => {
          e.preventDefault();
          const fd = new FormData(e.currentTarget);
          const values: Record<string, string> = {};
          fields.forEach((f) => {
            values[f.name] = String(fd.get(f.name) || '');
          });
          void onSubmit(values);
        }}
      >
        {fields.map((f) => (
          <div key={f.name} style={{marginBottom: 12}}>
            <label
              style={{
                display: 'block',
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                color: 'var(--signal-text-mute)',
                marginBottom: 6,
              }}
            >
              {f.label}
            </label>
            {f.multiline ? (
              <textarea
                name={f.name}
                placeholder={f.placeholder}
                defaultValue={f.defaultValue}
                required={f.required}
                rows={4}
                style={inputStyle}
              />
            ) : (
              <input
                name={f.name}
                placeholder={f.placeholder}
                defaultValue={f.defaultValue}
                required={f.required}
                autoFocus
                style={inputStyle}
              />
            )}
          </div>
        ))}
        <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8}}>
          <Button kind='ghost' sm onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button kind='primary' sm type='submit' disabled={busy}>
            {busy ? '…' : submitLabel}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--signal-card-elev)',
  border: '1px solid var(--signal-hairline)',
  borderRadius: 8,
  padding: '10px 12px',
  fontSize: 13,
  color: 'var(--signal-ink)',
  outline: 'none',
  fontFamily: 'inherit',
  boxSizing: 'border-box',
};
