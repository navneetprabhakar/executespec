import {forwardRef, useId, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes} from 'react';
import {AlertCircle} from 'lucide-react';

/**
 * F-10 fix (2026-05-09) — `<Field>` + `<FieldError>` primitives that
 * give every form a consistent label + error + hint shape.
 *
 * Pre-fix every form (NewRunModal, ConversationModal, ChangePassword,
 * billing, register/login, etc.) implemented its own validation UX:
 *   - Toast-only errors with no field-level highlight
 *   - Some forms cleared the input on send-failure (lost user text)
 *   - Required fields had no asterisk, no aria-required, no contract
 *
 * `<Field>` is just a wrapper that:
 *   - links a <label> to the rendered control via id (auto-generated)
 *   - renders a leading required asterisk + sets aria-required
 *   - renders a description as `aria-describedby` so SR users hear it
 *   - renders an inline error block (`role='alert'`) with leading icon
 *     when `error` is set
 *   - tints the control border red when `error` is set
 *
 * Two control variants:
 *   <Field.Input ... />    — standard <input>
 *   <Field.Textarea ... /> — standard <textarea>
 *
 * Both forward refs and accept any standard HTML attributes the caller
 * needs (type, autoComplete, rows, etc.).
 *
 * Migrating consumers (NewRunModal, ConversationModal, FreeCapNudge
 * invite, etc.) follows in subsequent commits — landing the primitive
 * first so each migration stays small.
 */

export interface FieldShellProps {
  label: ReactNode;
  /** Helper text shown below the label, before the control. */
  description?: ReactNode;
  /** Inline error message. When set, the control gets a red border +
   *  the error block renders with role='alert' so SR announces it. */
  error?: string | null;
  /** Marks the field as required (renders asterisk + sets aria-required). */
  required?: boolean;
  /** Inline element id for the control. Auto-generated when omitted. */
  htmlFor?: string;
  className?: string;
  children: (childProps: {
    id: string;
    'aria-describedby': string | undefined;
    'aria-invalid': boolean | undefined;
    'aria-required': boolean | undefined;
    'data-error': boolean;
  }) => ReactNode;
}

function FieldShell({label, description, error, required, htmlFor, className, children}: FieldShellProps) {
  const auto = useId();
  const id = htmlFor ?? auto;
  const descId = description ? `${id}-desc` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const describedBy = [descId, errId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={className} style={{display: 'flex', flexDirection: 'column', gap: 4}}>
      <label
        htmlFor={id}
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text)',
          letterSpacing: 0.1,
        }}
      >
        {label}
        {required && (
          <span aria-hidden='true' style={{color: 'var(--v3-error)', marginLeft: 4}}>
            *
          </span>
        )}
      </label>
      {description && (
        <div id={descId} style={{fontSize: 11, color: 'var(--v3-text-muted)', lineHeight: 1.4}}>
          {description}
        </div>
      )}
      {children({
        id,
        'aria-describedby': describedBy,
        'aria-invalid': error ? true : undefined,
        'aria-required': required ? true : undefined,
        'data-error': !!error,
      })}
      {error && (
        <FieldError id={errId} message={error} />
      )}
    </div>
  );
}

export interface FieldErrorProps {
  id?: string;
  message: string;
}

export function FieldError({id, message}: FieldErrorProps) {
  return (
    <div
      id={id}
      role='alert'
      style={{
        display: 'inline-flex',
        alignItems: 'flex-start',
        gap: 5,
        marginTop: 2,
        fontSize: 11,
        lineHeight: 1.4,
        color: 'var(--v3-error)',
      }}
    >
      <AlertCircle size={11} strokeWidth={2.5} aria-hidden='true' style={{marginTop: 1, flexShrink: 0}} />
      <span>{message}</span>
    </div>
  );
}

const baseControlStyle: React.CSSProperties = {
  background: 'var(--v3-bg)',
  color: 'var(--v3-text)',
  border: '1px solid var(--v3-border)',
  borderRadius: 6,
  fontSize: 13,
  padding: '8px 10px',
  fontFamily: 'inherit',
  outline: 'none',
};

export type FieldInputProps = Omit<FieldShellProps, 'children'> &
  InputHTMLAttributes<HTMLInputElement>;

const FieldInput = forwardRef<HTMLInputElement, FieldInputProps>(function FieldInput(
  {label, description, error, required, htmlFor, className, ...inputProps},
  ref,
) {
  return (
    <FieldShell label={label} description={description} error={error} required={required} htmlFor={htmlFor} className={className}>
      {(c) => (
        <input
          ref={ref}
          {...c}
          {...inputProps}
          style={{
            ...baseControlStyle,
            borderColor: c['data-error']
              ? 'var(--v3-error)'
              : (inputProps.style?.borderColor as string | undefined) ?? baseControlStyle.border,
            ...(inputProps.style ?? {}),
          }}
        />
      )}
    </FieldShell>
  );
});

export type FieldTextareaProps = Omit<FieldShellProps, 'children'> &
  TextareaHTMLAttributes<HTMLTextAreaElement>;

const FieldTextarea = forwardRef<HTMLTextAreaElement, FieldTextareaProps>(function FieldTextarea(
  {label, description, error, required, htmlFor, className, ...textareaProps},
  ref,
) {
  return (
    <FieldShell label={label} description={description} error={error} required={required} htmlFor={htmlFor} className={className}>
      {(c) => (
        <textarea
          ref={ref}
          {...c}
          {...textareaProps}
          style={{
            ...baseControlStyle,
            borderColor: c['data-error']
              ? 'var(--v3-error)'
              : (textareaProps.style?.borderColor as string | undefined) ?? baseControlStyle.border,
            resize: 'vertical',
            minHeight: 80,
            ...(textareaProps.style ?? {}),
          }}
        />
      )}
    </FieldShell>
  );
});

const Field = Object.assign(FieldShell, {
  Input: FieldInput,
  Textarea: FieldTextarea,
});

export default Field;
