import {useEffect, useState} from 'react';
import {createRun} from '@/lib/api';
import {useV3ProjectStore} from '../stores/useV3ProjectStore';
import {useV3Ui} from '../stores/useV3Ui';
import {trackV3, V3_EVENTS} from '../lib/events';
import {PROVIDER_DISPLAY, RUN_MODE_PLAN_REVIEW, RUN_MODE_QUICK} from '../lib/terminology';

/**
 * R-123 — single-textarea composer.
 *
 * The strict-review case: the v2 new-run modal asks 8 decisions
 * (textarea, mode, provider, repo, batch, TDD, Figma, branch, JIRA).
 * The v3 composer asks ONE — the textarea — plus a [Quick run]
 * chip and an Advanced expander for everything else.
 *
 * Defaults applied (D-002/D-001 unchanged):
 *   - mode = "Plan & review" (Production wire value)
 *   - provider = project default → user default → ANTHROPIC
 *   - branch / batch / TDD / Figma / JIRA = unset
 *
 * On submit:
 *   1. POST /api/runs with { userRequest, projectId, executionMode, aiProvider }
 *   2. Optimistically push a USER bubble + an ASSISTANT "Planning…" bubble
 *      into the project store so the conversation reflects intent immediately
 *   3. Subscribe to the new runId via SSE so the bubbles morph into PlanCard
 *      and ResultCard as the run progresses (SSE wiring lands with R-130).
 */
interface ComposerProps {
  projectId: string;
  /** When true, the composer renders the "first message" empty-state framing
   *  ("What do you want to build?"). When false, it renders the inline-thread
   *  framing ("Reply or describe a follow-up change…"). */
  isEmpty?: boolean;
  /** Default provider for the project; overridable in Advanced. */
  defaultProvider?: string;
}

export default function Composer({projectId, isEmpty = false, defaultProvider}: ComposerProps) {
  const [text, setText] = useState('');
  const [mode, setMode] = useState<'plan' | 'quick'>('plan');
  const [provider, setProvider] = useState(defaultProvider ?? 'ANTHROPIC');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // R-343 (P0 from UX persona review) — hydrate the composer with a
  // spec the user typed on executespec.ai's marketing-site hero.
  // The submit handler on the marketing site stashes the text under
  // `sessionStorage["v3-anon-spec"]`, the auth flow preserves it,
  // and AuthPage.claimAndForward routes the user to /home → first
  // project (which mounts this Composer).  We read the key on mount
  // and clear it so a refresh doesn't re-hydrate.  Empty composer
  // only — never overwrite text the user has already typed.
  useEffect(() => {
    if (!isEmpty) return; // only hydrate the first-load empty composer
    try {
      const stashed = sessionStorage.getItem('v3-anon-spec');
      if (stashed && stashed.trim().length > 0) {
        setText(stashed.trim());
        sessionStorage.removeItem('v3-anon-spec');
      }
    } catch (_) {
      /* private mode — silent fallback */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const pushBubble = useV3ProjectStore((s) => s.pushBubble);
  const bubbles = useV3ProjectStore((s) => s.bubbles);
  const cap = useV3Ui((s) => s.cap);
  const openCapModal = useV3Ui((s) => s.openCapModal);

  // R-343 (P3 from UX persona review) — recent-specs re-launch chips.
  // Marcus runs the same spec across forks often.  We pull the last
  // 3 distinct user-typed text bubbles from the project's thread,
  // deduped + reverse-chronological, and show them as compact chips
  // above the textarea.  Click → seed the textarea (user can edit
  // before submit).  Chips hide when the user has typed something
  // (no point re-launching a stale spec while composing a new one).
  const recentSpecs: string[] = (() => {
    const seen = new Set<string>();
    const out: string[] = [];
    for (let i = bubbles.length - 1; i >= 0 && out.length < 3; i--) {
      const b = bubbles[i];
      if (b.role !== 'user' || b.kind !== 'text') continue;
      const t = (b.text ?? '').trim();
      if (!t || seen.has(t)) continue;
      seen.add(t);
      out.push(t);
    }
    return out;
  })();

  const placeholder = isEmpty
    ? "What do you want to build? Describe it in plain English — we'll ship a draft PR you can merge."
    : 'Reply or describe a follow-up change…';
  const heroLabel = isEmpty ? 'What do you want to build?' : '';

  const canSubmit = text.trim().length > 0 && !submitting;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    // R-163 — block submit when free-tier balance is exhausted; the
    // shell's CapModalMount picks this up and renders the upgrade-or-
    // invite modal. Paid tiers (isUnlimited) bypass the gate.
    if (cap && !cap.isUnlimited && cap.tierKey === 'FREE' && cap.balance <= 0) {
      openCapModal();
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      // Optimistic bubble — visible BEFORE the network round-trip so the
      // chat feels immediate even when the planner takes 5-7s.
      const tempId = `tmp-${Date.now()}`;
      pushBubble({
        id: tempId,
        role: 'user',
        text: text.trim(),
        createdAt: new Date().toISOString(),
      });
      pushBubble({
        id: `${tempId}-assistant`,
        role: 'assistant',
        kind: 'status',
        status: 'planning',
        text: 'Planning…',
        createdAt: new Date().toISOString(),
      });
      const submittedText = text.trim();
      setText('');
      const resp = await createRun({
        userRequest: submittedText,
        executionMode: mode === 'quick' ? 'PROTOTYPE' : 'PRODUCTION',
        aiProvider: provider,
        projectId,
      });
      // R-160 — every run submission counts; the backend dedupes the
      // first-time vs repeat distinction via EventTrackingService.
      trackV3(V3_EVENTS.REPEAT_RUN_SUBMITTED, {
        projectId,
        provider,
        mode,
        textLen: submittedText.length,
      });
      // Mark the planning bubble with the real runId so SSE updates can
      // morph it into a PlanCard / ResultCard.
      pushBubble({
        id: `run-${resp.runId}`,
        role: 'assistant',
        kind: 'run',
        runId: resp.runId,
        status: 'PLANNING',
        createdAt: new Date().toISOString(),
        replacesId: `${tempId}-assistant`,
      });
    } catch (err) {
      const msg = (err as Error)?.message ?? 'Submit failed';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      style={{
        position: isEmpty ? 'static' : 'sticky',
        bottom: 0,
        background: 'var(--v3-bg)',
        paddingTop: 12,
        paddingBottom: 12,
        zIndex: 10,
      }}
      aria-label='New run composer'
    >
      {heroLabel && (
        <div
          style={{
            textAlign: 'center',
            color: 'var(--v3-text)',
            fontSize: 22,
            fontWeight: 600,
            marginBottom: 16,
          }}
        >
          {heroLabel}
        </div>
      )}

      {/* R-343 (P3) — recent-specs chip rail.  Renders only when the
          textarea is empty (so we don't suggest re-launching a stale
          spec while the user composes a new one) and the project has
          at least one prior user-text bubble.  Click → seed the
          textarea; the user can edit + submit. */}
      {recentSpecs.length > 0 && text.trim().length === 0 && !isEmpty && (
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 6,
            marginBottom: 8,
            alignItems: 'center',
          }}
        >
          <span
            style={{
              fontSize: 10.5,
              fontWeight: 600,
              color: 'var(--v3-text-subtle)',
              letterSpacing: 0.4,
              textTransform: 'uppercase',
              marginRight: 4,
            }}
          >
            Re-launch
          </span>
          {recentSpecs.map((s, i) => {
            const preview = s.length > 52 ? s.slice(0, 52) + '…' : s;
            return (
              <button
                key={i}
                type='button'
                onClick={() => setText(s)}
                title={s}
                style={{
                  padding: '4px 10px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text-muted)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 999,
                  fontSize: 11.5,
                  fontFamily: 'inherit',
                  cursor: 'pointer',
                  whiteSpace: 'nowrap',
                  maxWidth: 320,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {preview}
              </button>
            );
          })}
        </div>
      )}

      <div
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          padding: 12,
        }}
      >
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          placeholder={placeholder}
          rows={isEmpty ? 4 : 2}
          aria-label='What do you want to build'
          style={{
            width: '100%',
            background: 'transparent',
            color: 'var(--v3-text)',
            border: 'none',
            outline: 'none',
            resize: 'vertical',
            fontFamily: 'inherit',
            fontSize: 14,
            lineHeight: 1.55,
            minHeight: isEmpty ? 96 : 56,
          }}
          onKeyDown={(e) => {
            // Submit on Cmd/Ctrl + Enter to match Linear / Slack.
            if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
              e.preventDefault();
              if (canSubmit) onSubmit(e as unknown as React.FormEvent);
            }
          }}
        />

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
            marginTop: 8,
            paddingTop: 8,
            borderTop: '1px solid var(--v3-border)',
          }}
        >
          <div style={{display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap'}}>
            <button
              type='button'
              onClick={() => setMode((m) => (m === 'plan' ? 'quick' : 'plan'))}
              title={
                mode === 'plan'
                  ? 'Read the plan and approve cost first.'
                  : 'Skip plan review and start immediately.'
              }
              style={{
                padding: '3px 8px',
                fontSize: 11,
                fontWeight: 600,
                background: mode === 'quick' ? 'rgba(245,158,11,0.16)' : 'var(--v3-bg)',
                color: mode === 'quick' ? 'var(--v3-warning)' : 'var(--v3-text-muted)',
                border:
                  '1px solid ' + (mode === 'quick' ? 'rgba(245,158,11,0.4)' : 'var(--v3-border)'),
                borderRadius: 4,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {mode === 'quick' ? RUN_MODE_QUICK : RUN_MODE_PLAN_REVIEW}
            </button>
            <button
              type='button'
              onClick={() => setAdvancedOpen((a) => !a)}
              style={{
                padding: '3px 8px',
                fontSize: 11,
                fontWeight: 500,
                background: 'transparent',
                color: 'var(--v3-text-subtle)',
                border: 'none',
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
              aria-expanded={advancedOpen}
            >
              {advancedOpen ? '▾ Less' : '▾ Advanced'}
            </button>
          </div>
          <button
            type='submit'
            disabled={!canSubmit}
            style={{
              padding: '6px 16px',
              fontSize: 13,
              fontWeight: 600,
              background: canSubmit ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
              color: canSubmit ? 'var(--v3-text-on-brand)' : 'var(--v3-text-subtle)',
              border: 'none',
              borderRadius: 6,
              cursor: canSubmit ? 'pointer' : 'not-allowed',
              fontFamily: 'inherit',
            }}
          >
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>

        {advancedOpen && (
          <div
            style={{
              marginTop: 8,
              paddingTop: 8,
              borderTop: '1px solid var(--v3-border)',
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
              gap: 10,
            }}
          >
            <label style={{display: 'flex', flexDirection: 'column', gap: 4, fontSize: 11}}>
              <span style={{color: 'var(--v3-text-muted)', fontWeight: 500}}>AI provider</span>
              <select
                value={provider}
                onChange={(e) => setProvider(e.target.value)}
                style={{
                  padding: '4px 6px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  fontSize: 12,
                  fontFamily: 'inherit',
                }}
              >
                {Object.entries(PROVIDER_DISPLAY).map(([k, v]) => (
                  <option key={k} value={k}>
                    {v}
                  </option>
                ))}
              </select>
            </label>
            <div
              style={{
                gridColumn: '1 / -1',
                fontSize: 11,
                color: 'var(--v3-text-subtle)',
              }}
            >
              Branch · Figma · Confluence · JIRA · TDD — more options coming soon.
            </div>
          </div>
        )}
      </div>

      {error && (
        <div
          role='alert'
          style={{
            marginTop: 8,
            padding: '6px 10px',
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 4,
            fontSize: 12,
          }}
        >
          {error}
        </div>
      )}

      {isEmpty && (
        <div
          style={{
            marginTop: 16,
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: 8,
          }}
        >
          {[
            'Add OAuth login to my Next.js app',
            'Refactor a React component to TypeScript',
            'Build a Stripe checkout endpoint',
          ].map((sample) => (
            <button
              key={sample}
              type='button'
              onClick={() => {
                setText(sample);
                trackV3(V3_EVENTS.COMPOSER_SAMPLE_CHIP_CLICKED, {sample});
              }}
              style={{
                padding: '10px 12px',
                background: 'var(--v3-surface)',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 6,
                fontSize: 12.5,
                cursor: 'pointer',
                textAlign: 'left',
                fontFamily: 'inherit',
              }}
            >
              {sample}
            </button>
          ))}
        </div>
      )}

      {!error && (
        <div
          style={{
            marginTop: 8,
            fontSize: 10.5,
            color: 'var(--v3-text-subtle)',
            textAlign: 'right',
          }}
        >
          ⌘ + Enter to submit
        </div>
      )}
    </form>
  );
}
