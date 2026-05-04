import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {useV3ProjectStore, type Bubble} from '../stores/useV3ProjectStore';
import Composer from '../components/Composer';
import PlanCard from '../components/PlanCard';
import ResultCard from '../components/ResultCard';
import Pill from '../components/Pill';
import RepoAttachPopover from '../components/RepoAttachPopover';
import {PROVIDER_DISPLAY, STATUS_LABELS} from '../lib/terminology';

/**
 * R-120 — the dominant screen.
 *
 * Slack/Linear chat shape. The project's runs accumulate as bubbles in
 * a scrolling thread; the composer pins to the bottom. Empty projects
 * surface the "What do you want to build?" hero so the first action
 * is always submit-a-run.
 *
 * Renders:
 *   - One-row pulse summary across the top (status + provider + last run)
 *   - Conversation thread: user spec bubbles + assistant run bubbles
 *   - Plan cards inline on AWAITING_APPROVAL bubbles (R-121)
 *   - Result cards inline on COMPLETE/PARTIAL/FAILED bubbles (R-122)
 *   - Composer pinned to the bottom (R-123)
 *
 * SSE → bubble morph wiring lands with R-130; for now bubbles update
 * on next page load (the optimistic Composer push gives near-immediate
 * feedback even without SSE).
 */
export default function ProjectHomePage() {
  const {slug} = useParams<{slug: string}>();
  const navigate = useNavigate();
  const {loaded, loading, project, bubbles, error, load, reset: _reset} = useV3ProjectStore();
  const threadRef = useRef<HTMLDivElement>(null);
  // R-324 Checkpoint 2 — "Attach another repo" affordance state.
  // The popover handles the actual link call; we just rehydrate the
  // project store on success so the new repo lands in scope for the
  // next launch without a hard reload.
  const [attachOpen, setAttachOpen] = useState(false);

  // Load on mount + on slug change
  useEffect(() => {
    if (slug) void load(slug);
    return () => {
      // Don't reset on unmount — switching projects re-loads, and
      // navigating to /projects/{slug}/runs/{runId} should keep the thread
      // hot for fast back-navigation.
    };
  }, [slug, load]);

  // Auto-scroll to bottom when new bubbles arrive
  useEffect(() => {
    if (threadRef.current) {
      threadRef.current.scrollTop = threadRef.current.scrollHeight;
    }
  }, [bubbles.length]);

  const isEmpty = bubbles.length === 0 && !loading && loaded === slug;

  if (error && !loading) {
    return (
      <div
        className='v3-placeholder'
        style={{borderColor: 'var(--v3-error)', color: 'var(--v3-error)'}}
      >
        <h1>Couldn't load project</h1>
        <p>{error}</p>
        <div style={{display: 'flex', gap: 8, marginTop: 16}}>
          <button
            type='button'
            onClick={() => slug && load(slug)}
            style={{
              padding: '6px 14px',
              background: 'var(--v3-brand)',
              color: 'var(--v3-text-on-brand)',
              border: 'none',
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 12.5,
              fontWeight: 600,
            }}
          >
            Retry
          </button>
          {/* X-08 — give the user an escape from the error path. Without
              this they could only sit on the broken page or hit Retry. */}
          <button
            type='button'
            onClick={() => navigate('/org/projects')}
            style={{
              padding: '6px 14px',
              background: 'transparent',
              color: 'var(--v3-text-subtle)',
              border: '1px solid var(--v3-border)',
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 12.5,
            }}
          >
            ← Projects
          </button>
        </div>
      </div>
    );
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 56px - 48px)'}}>
      {/* X-08 (UX audit, 2026-05-03) — back-arrow to /org/projects.
          Pre-fix V3ProjectHome had no exit path beyond the V3TopBar
          (which only had Runs / Library / Migrations links). Users
          who landed here directly couldn't get back to the project
          list short of typing the URL. P-01 Option C kept this
          surface live, so the back-arrow is the proper fix rather
          than retiring the page. Renders before the project loads
          too, so error states still have an escape. */}
      <button
        type='button'
        onClick={() => navigate('/org/projects')}
        title='Back to project list'
        aria-label='Back to project list'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          padding: '4px 8px',
          marginBottom: 8,
          alignSelf: 'flex-start',
          background: 'transparent',
          color: 'var(--v3-text-subtle)',
          border: 'none',
          borderRadius: 4,
          cursor: 'pointer',
          fontSize: 11.5,
          fontFamily: 'inherit',
        }}
      >
        ← Projects
      </button>
      {/* Pulse summary — single row */}
      {project && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '10px 14px',
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            marginBottom: 16,
            flexWrap: 'wrap',
            fontSize: 12,
          }}
        >
          <span style={{color: 'var(--v3-text)', fontWeight: 600}}>{project.name}</span>
          <Pill tone='brand' size='sm'>
            {PROVIDER_DISPLAY[project.aiProvider] ?? project.aiProvider}
          </Pill>
          <Pill tone={project.status === 'ACTIVE' ? 'ok' : 'neutral'} size='sm'>
            {project.status?.toLowerCase()}
          </Pill>
          {bubbles.length > 0 && (
            <span style={{color: 'var(--v3-text-subtle)'}}>
              · {Math.floor(bubbles.length / 2)} runs in thread
            </span>
          )}
          {/* R-324 Checkpoint 2 — entry point for the "Attach another
              repo" flow. Opens RepoAttachPopover with targetProjectId
              set so the picked repo links into THIS project rather
              than spawning a new one. */}
          {slug && (
            <div style={{marginLeft: 'auto', display: 'flex', gap: 8}}>
              {/* P-01 (UX audit, 2026-05-04) — primary cross-link to the
                  4-tab dashboard surface. Pre-fix users on this page had
                  no way to reach Pulse / Repos / Runs / Code / Settings
                  short of typing the URL by hand. The audit's preferred
                  fix was to retire this surface entirely; the user
                  picked Option C — keep both, but make the bridge
                  obvious. The "Dashboard →" button is the one-click hop
                  to the canonical project surface. ProjectDashboardPage
                  has the matching reverse link back to /projects/{id}. */}
              <button
                type='button'
                onClick={() => navigate(`/projects/${slug}/dashboard`)}
                style={{
                  padding: '4px 10px',
                  background: 'transparent',
                  color: 'var(--v3-brand)',
                  border: '1px solid var(--v3-brand)',
                  borderRadius: 4,
                  cursor: 'pointer',
                  fontSize: 11.5,
                  fontFamily: 'inherit',
                  fontWeight: 500,
                }}
                title='Open the full project dashboard — Pulse, Repos, Runs, Code, Chat'
              >
                Dashboard →
              </button>
              {/* 2026-05-02 — ProjectHomePage is iteration-only (Re-launch
                  with a follow-up change). The actual chat surface, with
                  the new project-action tools (regenerate_architecture,
                  generate_project_doc, list_repos, list_recent_runs,
                  list_pull_requests, get_project_memory,
                  get_latest_architecture), lives on the dashboard's Chat
                  tab. Send the user there. */}
              <button
                type='button'
                onClick={() => navigate(`/projects/${slug}/dashboard?tab=chat`)}
                style={{
                  padding: '4px 10px',
                  background: 'transparent',
                  color: 'var(--v3-text-subtle)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  cursor: 'pointer',
                  fontSize: 11.5,
                  fontFamily: 'inherit',
                }}
                title='Open project chat — regenerate architecture, generate docs, list runs / PRs / repos, and more'
              >
                Chat with project →
              </button>
              <button
                type='button'
                onClick={() => setAttachOpen(true)}
                style={{
                  padding: '4px 10px',
                  background: 'transparent',
                  color: 'var(--v3-text-subtle)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  cursor: 'pointer',
                  fontSize: 11.5,
                  fontFamily: 'inherit',
                }}
                title='Attach another GitHub repo to this project'
              >
                + Attach repo
              </button>
            </div>
          )}
        </div>
      )}

      {/* R-324 Checkpoint 2 — popover for adding repos to an existing
          project. onAttached refreshes the project store so the new
          repo lands in scope for the next Launch without a hard
          reload. */}
      {slug && (
        <RepoAttachPopover
          open={attachOpen}
          onClose={() => setAttachOpen(false)}
          targetProjectId={slug}
          onAttached={() => {
            void load(slug);
          }}
        />
      )}

      {/* Thread */}
      {/* [A-04] (audit Sprint 4, 2026-05-01) — aria-live region for
          SSE-driven run state changes (PLANNING → WORKING → REVIEWING →
          COMPLETE). Without this, screen-reader users hear no
          announcement when a run progresses or a new bubble appears.
          aria-live="polite" so updates queue behind the current
          announcement instead of interrupting; aria-relevant="additions"
          limits noise to new bubbles (not text edits inside existing ones);
          aria-busy mirrors the loading flag so AT can suppress the
          inevitable "thread updated" chatter while the initial fetch
          paints. WCAG 4.1.3 Level AA. */}
      <div
        ref={threadRef}
        role='log'
        aria-live='polite'
        aria-relevant='additions'
        aria-busy={loading || undefined}
        aria-label='Run conversation thread'
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          gap: 14,
          padding: '4px 0 16px',
          overflowY: 'auto',
        }}
      >
        {loading && (
          <div
            style={{color: 'var(--v3-text-subtle)', fontSize: 12, textAlign: 'center', padding: 32}}
          >
            Loading conversation…
          </div>
        )}
        {bubbles.map((b) => (
          <BubbleView key={b.id} bubble={b} />
        ))}
      </div>

      {/* Composer */}
      <Composer projectId={slug ?? ''} isEmpty={isEmpty} defaultProvider={project?.aiProvider} />
    </div>
  );
}

// ─── Bubble renderer ─────────────────────────────────────────────────────────

function BubbleView({bubble}: {bubble: Bubble}) {
  const isUser = bubble.role === 'user';

  if (bubble.kind === 'run' && bubble.runId) {
    const isAwaitingApproval = bubble.status === 'AWAITING_APPROVAL';
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'flex-start',
          paddingLeft: 8,
        }}
      >
        {isAwaitingApproval ? (
          <PlanCard runId={bubble.runId} />
        ) : (
          <ResultCard runId={bubble.runId} initialStatus={bubble.status} />
        )}
      </div>
    );
  }

  // text or status bubble
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: isUser ? 'flex-end' : 'flex-start',
        paddingLeft: isUser ? 0 : 8,
        paddingRight: isUser ? 0 : 8,
      }}
    >
      <div
        style={{
          maxWidth: 640,
          padding: '8px 12px',
          background: isUser ? 'rgba(91,141,239,0.10)' : 'var(--v3-surface)',
          border: isUser ? '1px solid rgba(91,141,239,0.36)' : '1px solid var(--v3-border)',
          borderRadius: isUser ? '8px 8px 0 8px' : '8px 8px 8px 0',
          color: 'var(--v3-text)',
          fontSize: 13,
          lineHeight: 1.55,
          whiteSpace: 'pre-wrap',
        }}
      >
        {bubble.kind === 'status' && bubble.status === 'planning' ? (
          <span style={{display: 'inline-flex', alignItems: 'center', gap: 6}}>
            <Pill tone='info' dot size='sm'>
              {STATUS_LABELS.PLANNING}…
            </Pill>
          </span>
        ) : (
          bubble.text
        )}
      </div>
    </div>
  );
}
