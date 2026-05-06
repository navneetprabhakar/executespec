import {useEffect, useState} from 'react';
import {createRun, estimateRun, type LaunchEstimate} from '@/lib/api';
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
  // Audit r5 (#5, 2026-05-06) — staged-PR-before-push toggle. When on,
  // the run completes normally but the auto-push to GitHub is skipped
  // — the user can inspect the diff in Run Detail and manually push if
  // they like it (existing /runs/{id}/push-github endpoint). Default
  // off so today's behaviour is unchanged.
  const [skipAutoPush, setSkipAutoPush] = useState(false);
  // 2026-05-06 (PA-6 Run Launch port) — port the 3 missing v2 NewRunModal
  // fields onto the v3 Composer's Advanced expander. All three are
  // already wired through createRun + RunRequest + the planner.
  const [figmaUrl, setFigmaUrl] = useState('');
  const [tddMode, setTddMode] = useState(false);
  const [userCostCap, setUserCostCap] = useState<string>('');

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

  // Audit r5 (#6, 2026-05-06) — wire up the per-project prefill key.
  // RunFailureSummary already wrote to `v3-composer-prefill-{projectId}`
  // for the "re-prompt with failure context" path, but no consumer ever
  // read it — the Composer would silently ignore the stash. PlanCard's
  // new Adjust flow (audit r5 #6) writes the same key when the user
  // wants to edit-and-retry instead of approving the current plan.
  // Both producers are now end-to-end: stash → custom event → here.
  useEffect(() => {
    if (!projectId) return;
    const key = `v3-composer-prefill-${projectId}`;
    const hydrate = () => {
      try {
        const stashed = sessionStorage.getItem(key);
        if (stashed && stashed.trim().length > 0) {
          setText(stashed);
          sessionStorage.removeItem(key);
        }
      } catch {
        /* private mode — silent */
      }
    };
    hydrate();
    const listener = () => hydrate();
    window.addEventListener('v3-composer-prefill', listener);
    return () => window.removeEventListener('v3-composer-prefill', listener);
  }, [projectId]);
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

  // Audit r5 (#1, 2026-05-06) — pre-submit cost estimate. Shows
  // "~M-N tasks · ~$X-$Y" debounced as the user types, so they go
  // into the Approve gate already calibrated on what to expect.
  // Endpoint is heuristic-only (no LLM call) so spamming it is cheap.
  const [estimate, setEstimate] = useState<LaunchEstimate | null>(null);
  const [estimating, setEstimating] = useState(false);
  useEffect(() => {
    setEstimate(null);
    const trimmed = text.trim();
    if (!projectId || trimmed.length < 12) {
      setEstimating(false);
      return;
    }
    setEstimating(true);
    const handle = setTimeout(() => {
      estimateRun(projectId, trimmed)
        .then((r) => {
          setEstimate(r);
          setEstimating(false);
        })
        .catch(() => {
          // Silent fallback — estimate is decoration; the real cost
          // shows up on the PlanCard a few seconds later.
          setEstimate(null);
          setEstimating(false);
        });
    }, 1500);
    return () => clearTimeout(handle);
  }, [projectId, text]);

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
      const parsedCap = userCostCap.trim() ? Number(userCostCap.trim()) : undefined;
      const resp = await createRun({
        userRequest: submittedText,
        executionMode: mode === 'quick' ? 'PROTOTYPE' : 'PRODUCTION',
        aiProvider: provider,
        projectId,
        // Audit r5 (#5, 2026-05-06) — staged-PR-before-push override.
        skipAutoPush: skipAutoPush || undefined,
        // 2026-05-06 (PA-6 Run Launch port) — Figma URL / TDD / Stop at $X
        // come from the Advanced expander; only forward when set so an
        // empty Advanced section keeps the request body minimal.
        ...(figmaUrl.trim() ? {figmaUrl: figmaUrl.trim()} : {}),
        ...(tddMode ? {tddMode: true} : {}),
        ...(parsedCap !== undefined && Number.isFinite(parsedCap) && parsedCap > 0
          ? {userCostCap: parsedCap}
          : {}),
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

      {/* Audit r5 (#9, 2026-05-06) — first-run coachmark. The empty-state
          composer used to drop a new user straight into a textarea + 3
          sample chips with no narrative for what happens next. Two lines
          of plain English up front prevent the "I typed something, now
          what?" moment when planner takes ~5–7s to come back. We only
          show this on the empty hero (isEmpty) — the inline-thread
          composer hides it because returning users have already seen
          the flow. */}
      {isEmpty && (
        <div
          style={{
            textAlign: 'center',
            color: 'var(--v3-text-muted)',
            fontSize: 13,
            lineHeight: 1.55,
            marginBottom: 14,
            maxWidth: 540,
            marginLeft: 'auto',
            marginRight: 'auto',
          }}
        >
          <span style={{color: 'var(--v3-text-subtle)'}}>
            <strong style={{color: 'var(--v3-text-muted)'}}>How it works:</strong> describe what
            you want →{' '}
          </span>
          <span style={{color: 'var(--v3-text-muted)'}}>
            we draft a plan + cost estimate →
          </span>{' '}
          <span style={{color: 'var(--v3-text-muted)'}}>
            you approve →
          </span>{' '}
          <span style={{color: 'var(--v3-text-muted)'}}>
            we ship a draft PR.
          </span>{' '}
          <span style={{color: 'var(--v3-text-subtle)'}}>
            Takes ~30–90s. Free plan, no card.
          </span>
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

        {/* Audit r5 (#1, 2026-05-06): pre-submit cost estimate.
            Heuristic, no LLM call — shows the user roughly what to
            expect at the Approve gate before they hit Submit. Hidden
            until the spec is at least 12 chars, which keeps the chip
            from flickering on every keystroke. */}
        {(estimating || estimate) && (
          <div
            style={{
              marginTop: 6,
              fontSize: 11,
              color: 'var(--v3-text-subtle)',
              fontFamily: 'inherit',
            }}
            aria-live='polite'
          >
            {estimating && !estimate ? (
              <span style={{opacity: 0.7}}>Estimating cost…</span>
            ) : estimate ? (
              <span>
                ~{estimate.estimatedTasksLow}–{estimate.estimatedTasksHigh} tasks · ~$
                {estimate.estimatedCostLow.toFixed(2)}–${estimate.estimatedCostHigh.toFixed(2)}
                <span
                  style={{marginLeft: 6, opacity: 0.65}}
                  title='Heuristic from historical run data; the Approve gate shows the real estimate once the planner finishes.'
                >
                  ({estimate.basis})
                </span>
              </span>
            ) : null}
          </div>
        )}

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
            <label
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 6,
                fontSize: 11,
                color: 'var(--v3-text-muted)',
                cursor: 'pointer',
              }}
              title='When on, we still execute the run end-to-end but skip pushing to GitHub on success. Use it to preview the diff before shipping.'
            >
              <input
                type='checkbox'
                checked={skipAutoPush}
                onChange={(e) => setSkipAutoPush(e.target.checked)}
                style={{marginTop: 2}}
              />
              <span>
                <span style={{fontWeight: 500}}>Stage as draft</span>
                <span style={{display: 'block', fontSize: 10.5, color: 'var(--v3-text-subtle)'}}>
                  Skip auto-push; review diff first.
                </span>
              </span>
            </label>

            <label
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 6,
                fontSize: 11,
                color: 'var(--v3-text-muted)',
                cursor: 'pointer',
              }}
              title='When on, the planner emits test tasks first and implementation tasks declare dependsOn the test ids. The worker writes the tests, the user approves, then implementation runs.'
            >
              <input
                type='checkbox'
                checked={tddMode}
                onChange={(e) => setTddMode(e.target.checked)}
                style={{marginTop: 2}}
              />
              <span>
                <span style={{fontWeight: 500}}>TDD mode</span>
                <span style={{display: 'block', fontSize: 10.5, color: 'var(--v3-text-subtle)'}}>
                  Tests first, then implementation.
                </span>
              </span>
            </label>

            <label style={{display: 'flex', flexDirection: 'column', gap: 4, fontSize: 11}}>
              <span style={{color: 'var(--v3-text-muted)', fontWeight: 500}}>
                Figma URL
                <span style={{color: 'var(--v3-text-subtle)', fontWeight: 400}}> (optional)</span>
              </span>
              <input
                type='url'
                value={figmaUrl}
                onChange={(e) => setFigmaUrl(e.target.value)}
                placeholder='https://www.figma.com/file/…'
                style={{
                  padding: '4px 6px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  fontSize: 12,
                  fontFamily: 'inherit',
                }}
              />
            </label>

            <label style={{display: 'flex', flexDirection: 'column', gap: 4, fontSize: 11}}>
              <span style={{color: 'var(--v3-text-muted)', fontWeight: 500}}>
                Stop at $
                <span style={{color: 'var(--v3-text-subtle)', fontWeight: 400}}> (optional)</span>
              </span>
              <input
                type='number'
                step='0.10'
                min='0.10'
                max='1000'
                value={userCostCap}
                onChange={(e) => setUserCostCap(e.target.value)}
                placeholder='e.g. 5.00'
                style={{
                  padding: '4px 6px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  fontSize: 12,
                  fontFamily: 'inherit',
                }}
              />
              <span style={{fontSize: 10.5, color: 'var(--v3-text-subtle)'}}>
                Hard ceiling — the run terminates when spend hits this.
              </span>
            </label>

            <div
              style={{
                gridColumn: '1 / -1',
                fontSize: 11,
                color: 'var(--v3-text-subtle)',
              }}
            >
              Branch · Confluence · JIRA · spec templates — more options coming soon.
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
