/**
 * The agent input — the always-reachable composer.
 *
 * Mobile: pinned to the viewport bottom, sits above the BottomNav with a
 *   safe-area-inset-bottom pad. Glass surface. Single line at rest;
 *   expands into a multi-line composer on focus.
 *
 * Desktop: floats at bottom-center of the viewport, max-width 720px,
 *   centered on the v4-main column.
 *
 * Behaviour:
 *   - Tap / focus → expand to multi-line, show send button.
 *   - ⌘+Enter / Ctrl+Enter → submit + collapse back.
 *   - Blur with empty text → collapse.
 *   - Pressing Escape closes the expanded state.
 *
 * Submission: navigates to /v4/runs/new with the prefilled spec OR (if
 * a `onSubmit` callback is provided) calls the callback. For the v1 of
 * this primitive we just navigate; the rest of the new-run flow lives in
 * the existing v3 surfaces we'll wire in next iteration.
 */
import {useEffect, useRef, useState, type KeyboardEvent} from 'react';
import {useNavigate} from 'react-router-dom';
import {ArrowUp, Sparkles} from 'lucide-react';
import {useV4Context} from '../lib/useV4Context';

interface Props {
  /** Optional placeholder; defaults to "Ask the agent…". */
  placeholder?: string;
  /** Called instead of default-navigation on submit. */
  onSubmit?: (text: string) => void;
  /** Context label shown left of the input. If undefined, falls back to
   *  whatever the current page set via useV4Context. */
  contextLabel?: string;
}

export default function AgentInput({
  placeholder = 'Ask the agent…',
  onSubmit,
  contextLabel,
}: Props) {
  const ctxLabel = useV4Context((s) => s.label);
  const ctxProjectId = useV4Context((s) => s.projectId);
  const effectiveLabel = contextLabel ?? ctxLabel;
  const [text, setText] = useState('');
  const [focused, setFocused] = useState(false);
  const taRef = useRef<HTMLTextAreaElement | null>(null);
  const navigate = useNavigate();

  // Auto-grow textarea on input (cap at 6 rows).
  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    const max = 6 * 22; // line-height ≈ 22px
    ta.style.height = Math.min(ta.scrollHeight, max) + 'px';
  }, [text, focused]);

  const canSubmit = text.trim().length > 0;

  const submit = () => {
    if (!canSubmit) return;
    const value = text.trim();
    setText('');
    setFocused(false);
    taRef.current?.blur();
    if (onSubmit) onSubmit(value);
    else navigate('/runs/new', {state: {prefillSpec: value, projectId: ctxProjectId}});
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape') {
      setFocused(false);
      taRef.current?.blur();
    } else if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
      e.preventDefault();
      submit();
    }
  };

  return (
    <div
      role='form'
      aria-label='Agent input'
      style={{
        position: 'fixed',
        left: 0,
        right: 0,
        bottom: 'var(--v4-bottom)',
        zIndex: 35,
        padding: '8px clamp(12px, 4vw, 24px) calc(env(safe-area-inset-bottom, 0px) + 8px)',
        pointerEvents: 'none',
      }}
    >
      <div
        style={{
          maxWidth: 720,
          margin: '0 auto',
          pointerEvents: 'auto',
          background: 'var(--v4-glass-strong)',
          backdropFilter: 'blur(18px) saturate(180%)',
          WebkitBackdropFilter: 'blur(18px) saturate(180%)',
          border: '1px solid var(--v4-border)',
          borderRadius: focused ? 18 : 22,
          boxShadow: focused ? 'var(--v4-shadow-hover)' : 'var(--v4-shadow-rest)',
          transition:
            'border-radius 280ms var(--v4-spring), box-shadow 280ms var(--v4-spring), transform 280ms var(--v4-spring)',
          transform: focused ? 'translateY(-2px)' : 'none',
        }}
      >
        {effectiveLabel && (
          <div
            style={{
              padding: '6px 14px 0',
              fontSize: 'var(--v4-text-2xs)',
              fontWeight: 600,
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              color: 'var(--v4-text-subtle)',
            }}
          >
            <span style={{opacity: 0.7}}>In</span>{' '}
            <span style={{color: 'var(--v4-text-muted)'}}>{effectiveLabel}</span>
          </div>
        )}
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-end',
            gap: 8,
            padding: '8px 8px 8px 14px',
          }}
        >
          <Sparkles
            size={16}
            aria-hidden='true'
            style={{
              color: 'var(--v4-brand)',
              flexShrink: 0,
              marginBottom: 10,
              opacity: focused || text ? 1 : 0.7,
              transition: 'opacity 200ms var(--v4-spring)',
            }}
          />
          <textarea
            ref={taRef}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => {
              if (text.trim().length === 0) setFocused(false);
            }}
            onKeyDown={onKey}
            rows={1}
            placeholder={placeholder}
            aria-label={placeholder}
            style={{
              flex: 1,
              minWidth: 0,
              background: 'transparent',
              border: 'none',
              outline: 'none',
              resize: 'none',
              fontFamily: 'inherit',
              fontSize: 'var(--v4-text-base)',
              lineHeight: 1.5,
              color: 'var(--v4-text)',
              padding: '8px 0',
              maxHeight: 132,
              overflowY: text.split('\n').length > 4 ? 'auto' : 'hidden',
            }}
          />
          <button
            type='button'
            onClick={submit}
            disabled={!canSubmit}
            aria-label='Submit to agent'
            className='v4-press'
            style={{
              flexShrink: 0,
              width: 34,
              height: 34,
              borderRadius: 999,
              border: 'none',
              background: canSubmit ? 'var(--v4-brand)' : 'var(--v4-surface-up)',
              color: canSubmit ? '#fff' : 'var(--v4-text-subtle)',
              cursor: canSubmit ? 'pointer' : 'not-allowed',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'background 220ms var(--v4-spring), transform 80ms var(--v4-spring)',
            }}
          >
            <ArrowUp size={16} aria-hidden='true' />
          </button>
        </div>
      </div>
    </div>
  );
}
