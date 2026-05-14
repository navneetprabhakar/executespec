import {useEffect, useRef, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {createRun, estimateRun, type LaunchEstimate} from '@/lib/api';
import ConversationModal from './ConversationModal';
import {useV3ProjectStore} from '../stores/useV3ProjectStore';
import {useV3Ui} from '../stores/useV3Ui';
import {trackV3, V3_EVENTS} from '../lib/events';
import {PROVIDER_DISPLAY, RUN_MODE_PLAN_REVIEW, RUN_MODE_QUICK} from '../lib/terminology';
import {useIsPhone} from '@/hooks/useIsMobile';

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
  /**
   * 2026-05-07 — projectId is optional. When undefined the composer
   * submits a project-less run (the backend's `createRun` accepts a
   * null projectId — runs created this way appear under /runs but
   * not inside any project shell). Used by the new /runs/new page so
   * users can launch one-shot runs without picking a project first.
   */
  projectId?: string;
  /** When true, the composer renders the "first message" empty-state framing
   *  ("What do you want to build?"). When false, it renders the inline-thread
   *  framing ("Reply or describe a follow-up change…"). */
  isEmpty?: boolean;
  /** Default provider for the project; overridable in Advanced. */
  defaultProvider?: string;
}

export default function Composer({projectId, isEmpty = false, defaultProvider}: ComposerProps) {
  const isPhone = useIsPhone();
  const [text, setText] = useState('');
  const [mode, setMode] = useState<'plan' | 'quick'>('plan');
  const [provider, setProvider] = useState(defaultProvider ?? 'ANTHROPIC');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 2026-05-14 (mobile-first redesign) — recent-specs are a sheet on
  // phones (was: an inline 3-chip wrap-row eating ~120px of fold). The
  // user complaint was "chat history covered by composer + suggestions"
  // — pulling these out frees up the thread by default; the user can
  // tap "Recent" to surface them when they actually want re-launch.
  const [recentSheetOpen, setRecentSheetOpen] = useState(false);
  // 2026-05-14 (iOS keyboard avoidance) — when iOS Safari shows the
  // keyboard, window.innerHeight stays constant but visualViewport.height
  // shrinks. The composer is `position: sticky; bottom: 0` inside a flex
  // column whose height is calc(100dvh - 60px), and dvh updates lag the
  // keyboard. Result: textarea sits BEHIND the keyboard; user types blind.
  // Listening to visualViewport.resize lets us translate the composer up
  // by exactly the keyboard height (innerHeight - visualViewport.height).
  const [kbOffset, setKbOffset] = useState(0);
  const formRef = useRef<HTMLFormElement>(null);
  useEffect(() => {
    if (!isPhone) {
      setKbOffset(0);
      return;
    }
    const vv = window.visualViewport;
    if (!vv) return;
    const sync = () => {
      const kb = Math.max(0, window.innerHeight - vv.height - vv.offsetTop);
      setKbOffset(kb);
    };
    vv.addEventListener('resize', sync);
    vv.addEventListener('scroll', sync);
    sync();
    return () => {
      vv.removeEventListener('resize', sync);
      vv.removeEventListener('scroll', sync);
    };
  }, [isPhone]);
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
  // 2026-05-06 — pre-run conversation surface. Opens the requirements-
  // analyst chat (POST /api/conversation, available to ALL tiers per
  // ConversationController) so users can refine a fuzzy idea into a
  // tight spec before spending credits on a run. Available regardless
  // of project context.
  const [refineOpen, setRefineOpen] = useState(false);

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
  const navigate = useNavigate();
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
  // When projectId is undefined (standalone /runs/new flow) we skip
  // the estimate — the endpoint requires a projectId for the
  // historical-run lookback.
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
      // chat feels immediate even when the planner takes 5-7s. Skipped
      // in the project-less flow (/runs/new) since there's no chat
      // surface to mount the bubbles into — we redirect to /runs/{id}
      // on success instead.
      const tempId = `tmp-${Date.now()}`;
      if (projectId) {
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
      }
      const submittedText = text.trim();
      setText('');
      const parsedCap = userCostCap.trim() ? Number(userCostCap.trim()) : undefined;
      const resp = await createRun({
        userRequest: submittedText,
        executionMode: mode === 'quick' ? 'PROTOTYPE' : 'PRODUCTION',
        aiProvider: provider,
        // Only forward projectId when set — backend treats omitted as
        // a standalone unlinked run.
        ...(projectId ? {projectId} : {}),
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
      if (projectId) {
        // Mark the planning bubble with the real runId so SSE updates can
        // morph it into a PlanCard / ResultCard inside the project chat.
        pushBubble({
          id: `run-${resp.runId}`,
          role: 'assistant',
          kind: 'run',
          runId: resp.runId,
          status: 'PLANNING',
          createdAt: new Date().toISOString(),
          replacesId: `${tempId}-assistant`,
        });
      } else {
        // Standalone run — there's no chat thread to morph into a
        // PlanCard, so jump directly to the run detail page where the
        // user can watch the planner come through SSE.
        navigate(`/runs/${resp.runId}`);
      }
    } catch (err) {
      const msg = (err as Error)?.message ?? 'Submit failed';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form
      ref={formRef}
      onSubmit={onSubmit}
      style={{
        position: isEmpty ? 'static' : 'sticky',
        bottom: 0,
        background: 'var(--v3-bg)',
        paddingTop: 12,
        // 2026-05-14 (responsive UX parity audit, iOS pass) — pad below the
        // home-indicator on iPhones with notch / Dynamic Island. Pre-fix the
        // Send button + Quick-run chip sat IN the home-indicator zone, which
        // both made them harder to tap accurately AND caused iOS to interpret
        // a tap-and-drag as a "swipe up to home" gesture. `max(12px, env(...))`
        // preserves the desktop / non-notched height.
        paddingBottom: 'max(12px, env(safe-area-inset-bottom))',
        // 2026-05-14 (iOS keyboard avoidance) — when the soft keyboard is
        // open, lift the composer by `kbOffset` so the textarea + Submit
        // button stay above the keyboard. transform avoids re-layout (the
        // sticky bottom:0 anchor stays put, we just push it up visually).
        transform: kbOffset > 0 ? `translateY(${-kbOffset}px)` : undefined,
        transition: 'transform 0.18s ease-out',
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

      {/* Audit r5 (#9, 2026-05-06) — first-run coachmark. Hidden on
          phone (2026-05-14 mobile-first redesign): a 60-80px four-line
          paragraph above the textarea is wasted fold space when the
          user can read the same explanation from the marketing site.
          Desktop keeps the hand-hold; phones go straight to the
          textarea + Submit so the thread (when present) stays in view. */}
      {isEmpty && !isPhone && (
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
            <strong style={{color: 'var(--v3-text-muted)'}}>How it works:</strong> describe what you
            want →{' '}
          </span>
          <span style={{color: 'var(--v3-text-muted)'}}>we draft a plan + cost estimate →</span>{' '}
          <span style={{color: 'var(--v3-text-muted)'}}>you approve →</span>{' '}
          <span style={{color: 'var(--v3-text-muted)'}}>we ship a draft PR.</span>{' '}
          <span style={{color: 'var(--v3-text-subtle)'}}>Takes ~30–90s. Free plan, no card.</span>
        </div>
      )}

      {/* R-343 (P3) — recent-specs chip rail. Desktop only: a 1-3-pill
          inline rail above the textarea. 2026-05-14 mobile-first
          redesign: the same rail was eating ~120px of vertical fold on
          phones AND covering up the chat history above. Phones get the
          "Recent" button + bottom sheet instead (rendered further down
          in this form). */}
      {recentSpecs.length > 0 && text.trim().length === 0 && !isEmpty && !isPhone && (
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

        {/* 2026-05-14 (mobile-first redesign) — action row layout.
            DESKTOP: secondary chips (Mode / Refine / Advanced) on the
            left, Submit pill on the right (the muscle-memory layout
            our existing users learned).
            PHONE: Submit goes full-width FIRST (the visually heaviest
            element on the screen, 48px tall — matches Linear / Stripe
            / GitHub Mobile composer patterns), with secondary actions
            as compact text-only links beneath it. Plus a "↻ Recent"
            link when there are previous specs to re-launch (the same
            chip rail that's been moved out of inline rendering above).
            One row of links is ~32px vs the ~120px the chip rail used.
        */}
        <div
          style={{
            display: 'flex',
            flexDirection: isPhone ? 'column' : 'row',
            justifyContent: isPhone ? 'flex-start' : 'space-between',
            alignItems: isPhone ? 'stretch' : 'center',
            gap: 8,
            marginTop: 8,
            paddingTop: 8,
            borderTop: '1px solid var(--v3-border)',
          }}
        >
          {isPhone && (
            <button
              type='submit'
              disabled={!canSubmit}
              style={{
                width: '100%',
                minHeight: 48,
                fontSize: 15,
                fontWeight: 600,
                background: canSubmit ? 'var(--v3-brand)' : 'var(--v3-surface-elevated)',
                color: canSubmit ? 'var(--v3-text-on-brand)' : 'var(--v3-text-subtle)',
                border: 'none',
                borderRadius: 10,
                cursor: canSubmit ? 'pointer' : 'not-allowed',
                fontFamily: 'inherit',
              }}
            >
              {submitting ? 'Submitting…' : 'Submit'}
            </button>
          )}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: isPhone ? 12 : 6,
              flexWrap: 'wrap',
              justifyContent: isPhone ? 'space-between' : 'flex-start',
            }}
          >
            <button
              type='button'
              onClick={() => setMode((m) => (m === 'plan' ? 'quick' : 'plan'))}
              title={
                mode === 'plan'
                  ? 'Read the plan and approve cost first.'
                  : 'Skip plan review and start immediately.'
              }
              style={{
                padding: isPhone ? '6px 10px' : '3px 8px',
                minHeight: isPhone ? 36 : undefined,
                fontSize: isPhone ? 12 : 11,
                fontWeight: 600,
                background: mode === 'quick' ? 'rgba(245,158,11,0.16)' : 'var(--v3-bg)',
                color: mode === 'quick' ? 'var(--v3-warning)' : 'var(--v3-text-muted)',
                border:
                  '1px solid ' + (mode === 'quick' ? 'rgba(245,158,11,0.4)' : 'var(--v3-border)'),
                borderRadius: 6,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {mode === 'quick' ? RUN_MODE_QUICK : RUN_MODE_PLAN_REVIEW}
            </button>
            <button
              type='button'
              onClick={() => setRefineOpen(true)}
              title='Chat with a requirements analyst to refine your spec before submitting.'
              style={{
                padding: isPhone ? '6px 10px' : '3px 8px',
                minHeight: isPhone ? 36 : undefined,
                fontSize: isPhone ? 12 : 11,
                fontWeight: 500,
                background: 'transparent',
                color: 'var(--accent-blue-text, var(--v3-brand))',
                border: '1px solid color-mix(in srgb, var(--v3-brand) 40%, transparent)',
                borderRadius: 999,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {isPhone ? '✨ Refine' : '✨ Refine with assistant'}
            </button>
            {isPhone && recentSpecs.length > 0 && (
              <button
                type='button'
                onClick={() => setRecentSheetOpen(true)}
                style={{
                  padding: '6px 10px',
                  minHeight: 36,
                  fontSize: 12,
                  fontWeight: 500,
                  background: 'transparent',
                  color: 'var(--v3-text-subtle)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  cursor: 'pointer',
                  fontFamily: 'inherit',
                }}
                aria-haspopup='dialog'
              >
                ↻ Recent
              </button>
            )}
            <button
              type='button'
              onClick={() => setAdvancedOpen((a) => !a)}
              style={{
                padding: isPhone ? '6px 10px' : '3px 8px',
                minHeight: isPhone ? 36 : undefined,
                fontSize: isPhone ? 12 : 11,
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
          {!isPhone && (
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
          )}
        </div>

        {/* Quick-mode inline warning. Amber chip alone signaled "danger" but
            only via tooltip; this visible single-line caption tells the user
            exactly what they're trading off. Kept to one tight line so the
            composer stays compact. */}
        {mode === 'quick' && (
          <div
            role='status'
            aria-live='polite'
            style={{
              marginTop: 6,
              fontSize: 11,
              color: 'var(--v3-warning)',
              display: 'flex',
              alignItems: 'center',
              gap: 4,
            }}
          >
            <span aria-hidden='true'>⚡</span>
            Quick run skips the plan-approval gate — cost runs to the platform cap, no preview.
          </div>
        )}

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

      {refineOpen && (
        <ConversationModal
          initialText={text}
          onUseSpec={(spec) => setText(spec)}
          onClose={() => setRefineOpen(false)}
        />
      )}

      {/* 2026-05-14 (mobile-first redesign) — Recent-specs bottom sheet.
          Replaces the inline 3-chip wrap-row on phones (kept on desktop).
          The sheet opens from the bottom, dims the page, and shows the
          recent specs as full-width 48px-tappable rows. Tap one →
          seed the textarea + close the sheet. Backdrop tap or the X
          button closes. */}
      {isPhone && recentSheetOpen && (
        <div
          role='dialog'
          aria-modal='true'
          aria-label='Re-launch a recent spec'
          style={{
            position: 'fixed',
            inset: 0,
            zIndex: 60,
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'flex-end',
          }}
        >
          <div
            aria-hidden='true'
            onClick={() => setRecentSheetOpen(false)}
            style={{
              position: 'absolute',
              inset: 0,
              background: 'rgba(0,0,0,0.55)',
              backdropFilter: 'blur(2px)',
              WebkitBackdropFilter: 'blur(2px)',
            }}
          />
          <div
            style={{
              position: 'relative',
              background: 'var(--v3-surface)',
              borderTopLeftRadius: 14,
              borderTopRightRadius: 14,
              borderTop: '1px solid var(--v3-border)',
              paddingTop: 14,
              paddingBottom: 'max(14px, env(safe-area-inset-bottom))',
              paddingLeft: 12,
              paddingRight: 12,
              maxHeight: '70vh',
              overflowY: 'auto',
            }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                paddingBottom: 10,
                borderBottom: '1px solid var(--v3-border)',
                marginBottom: 8,
              }}
            >
              <div style={{fontSize: 14, fontWeight: 600, color: 'var(--v3-text)'}}>
                Recent specs
              </div>
              <button
                type='button'
                onClick={() => setRecentSheetOpen(false)}
                aria-label='Close'
                style={{
                  minWidth: 44,
                  minHeight: 44,
                  background: 'transparent',
                  border: 'none',
                  color: 'var(--v3-text-muted)',
                  fontSize: 22,
                  cursor: 'pointer',
                  fontFamily: 'inherit',
                }}
              >
                ×
              </button>
            </div>
            <ul style={{listStyle: 'none', padding: 0, margin: 0}}>
              {recentSpecs.map((s, i) => (
                <li key={i}>
                  <button
                    type='button'
                    onClick={() => {
                      setText(s);
                      setRecentSheetOpen(false);
                    }}
                    style={{
                      width: '100%',
                      textAlign: 'left',
                      padding: '12px 12px',
                      minHeight: 48,
                      background: 'transparent',
                      color: 'var(--v3-text)',
                      border: 'none',
                      borderBottom: '1px solid var(--v3-border)',
                      fontSize: 13,
                      lineHeight: 1.5,
                      cursor: 'pointer',
                      fontFamily: 'inherit',
                    }}
                  >
                    {s}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </form>
  );
}
