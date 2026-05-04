/**
 * EmptyProjectLanding — single-question empty state shown on the project
 * dashboard when zero runs exist (per UX & Wireframe Report §5.1).
 *
 * Pattern stolen from emergent.sh's zero-state — but optimized for our
 * "ship verified code into a real repo" north star instead of theirs
 * (the "10-minute throwaway demo" framing):
 *
 *   ┌────────────────────────────────────────┐
 *   │  Ship verified code from a spec.       │
 *   │  Either start with a repo, or paste    │
 *   │  a spec.                               │
 *   │                                        │
 *   │  [ Start a run ]   [ Pick a sample ]   │
 *   └────────────────────────────────────────┘
 *
 * Both CTAs link out: "Start a run" goes to the existing project Launch
 * flow (which already handles repo-link + spec textarea); "Pick a sample"
 * surfaces the SampleSpecRegistry items (already shipped under the
 * v2.6.1 Wave 2 onboarding bundle) so cold users don't have to write
 * their first spec from scratch.
 */
import {useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router-dom';
import {FileText, ArrowRight, Sparkles} from 'lucide-react';
import * as api from '@/lib/api';
import {useProjectStore} from '@/stores/useProjectStore';
import {cn} from '@/lib/utils';
import TemplateGrid from '@/components/execution/TemplateGrid';
import FirstRunChecklist from './FirstRunChecklist';
import type {StackInfo} from '@/types/project';

// P-12 (UX audit, 2026-05-03) — heuristic mapping from a sample's
// `projectType` slug to the dominant language(s) it produces. Used to score
// sample relevance against the languages already detected in the project's
// repos. Conservative — anything unmapped scores neutral so we don't drop
// a sample just because we don't know its language.
const PROJECT_TYPE_LANGUAGES: Record<string, string[]> = {
  'spring-boot-sqlite': ['java', 'kotlin'],
  'spring-boot': ['java', 'kotlin'],
  'node-backend': ['javascript', 'typescript'],
  'node-frontend': ['javascript', 'typescript'],
  'react-native-expo': ['javascript', 'typescript'],
  'react-vite': ['javascript', 'typescript'],
  python: ['python'],
  fastapi: ['python'],
  flask: ['python'],
  go: ['go'],
  rust: ['rust'],
};

/** Return the projectType's mapped languages, lower-cased; never null. */
function languagesForProjectType(projectType: string): string[] {
  const key = (projectType ?? '').toLowerCase();
  return PROJECT_TYPE_LANGUAGES[key] ?? [];
}

interface EmptyProjectLandingProps {
  projectId: string;
  repoCount: number;
  /**
   * Called when the user clicks "Link a repo" in the empty state. The parent
   * (ProjectDashboardPage) is expected to switch to the Code → Repos sub-tab
   * AND dispatch the {@code open-add-repo-modal} event so ProjectRepoManager
   * pops the AddRepoModal.
   *
   * <p>If omitted (legacy callers), falls back to the pre-v2.7.1 behavior
   * which incorrectly opened the Settings drawer — kept only so existing
   * tests + storybook stories don't break before they're updated.
   */
  onLinkRepo?: () => void;
}

export default function EmptyProjectLanding({
  projectId,
  repoCount,
  onLinkRepo,
}: EmptyProjectLandingProps) {
  const navigate = useNavigate();
  const [samples, setSamples] = useState<api.SampleSpecDto[]>([]);
  const [loading, setLoading] = useState(true);
  // P-02 (UX audit, 2026-05-03) — pull dashboard so the FirstRunChecklist can
  // tell which steps are already done. Project store is shared with PulseTab
  // so the data is already fetched by the time we render here; if it's not
  // (e.g., direct deep-link), the checklist falls back to "all pending"
  // gracefully because every step's done-flag is null-safe.
  const dashboard = useProjectStore((s) => s.dashboard);
  const repos = useProjectStore((s) => s.repos);

  useEffect(() => {
    api
      .listOnboardingSamples()
      .then((s) => setSamples(s))
      .catch(() => setSamples([]))
      .finally(() => setLoading(false));
  }, []);

  const canLaunch = repoCount > 0;
  const runCount =
    (dashboard?.activeRuns ?? 0) + (dashboard?.completedRuns ?? 0) + (dashboard?.failedRuns ?? 0);

  // P-12 — derive the set of languages this project's repos use. Tolerant
  // to malformed / missing detectedStack JSON: any error per-row falls
  // through to "no contribution from that repo". Empty set means no
  // re-ordering happens (samples render in the registry-default order),
  // which is the safe behaviour for a brand-new project before its repos
  // have been analysed.
  const projectLanguages = useMemo(() => {
    const set = new Set<string>();
    if (!repos) return set;
    for (const link of repos) {
      // Project store carries ProjectRepoLink rows; the per-repo metadata
      // (including detectedStack) lives on link.repo.
      const stack = link.repo?.detectedStack;
      if (!stack) continue;
      try {
        const parsed = JSON.parse(stack) as StackInfo;
        if (parsed?.language) set.add(parsed.language.toLowerCase());
      } catch {
        // Ignore — partial detection is normal pre-analyse.
      }
    }
    return set;
  }, [repos]);

  // P-12 — context-rank the samples. Stable sort: matches first, otherwise
  // preserve registry order. We don't drop unmatched samples — the user
  // might still want a Python sample on a TypeScript-heavy project, for
  // example. The "Tailored to your stack" hint only shows when ranking
  // actually applied (i.e. we know at least one project language).
  const rankedSamples = useMemo(() => {
    if (projectLanguages.size === 0) return samples;
    const scored = samples.map((s, idx) => {
      const langs = languagesForProjectType(s.projectType);
      const matches = langs.some((l) => projectLanguages.has(l));
      return {sample: s, idx, matches};
    });
    scored.sort((a, b) => {
      if (a.matches !== b.matches) return a.matches ? -1 : 1;
      return a.idx - b.idx;
    });
    return scored.map((s) => s.sample);
  }, [samples, projectLanguages]);

  const tailoredHintVisible = projectLanguages.size > 0 && rankedSamples.length > 0;

  return (
    <div className='max-w-2xl mx-auto py-8 space-y-6'>
      <div className='text-center space-y-2'>
        <h2 className='text-xl font-semibold text-primary tracking-[-0.015em]'>
          Ship verified code from a spec.
        </h2>
        <p className='text-sm text-secondary max-w-md mx-auto'>
          Four quick steps to your first run. Everything below it works the same once you're set up.
        </p>
      </div>

      {/* P-02 — 4-step progress checklist replaces the legacy two-CTA hero.
          Always renders here because EmptyProjectLanding only mounts when
          noRuns && repoCount === 0 (= step 1 is the user's current job).
          If they somehow land here with repoCount > 0 (race after linking),
          the checklist auto-advances the active step to whatever's next. */}
      <FirstRunChecklist
        projectId={projectId}
        repoCount={repoCount}
        archStatus={dashboard?.architectureDocStatus ?? null}
        runCount={runCount}
        onLinkRepo={onLinkRepo}
        onGenerateArch={() => {
          // No direct hook here — switch to Code → Architecture sub-tab where
          // the existing Generate button lives. The dashboard's tab/sub URL
          // sync (P-23) makes this a clean back-button restore point.
          const url = new URL(window.location.href);
          url.searchParams.set('tab', 'code');
          url.searchParams.set('codeSub', 'architecture');
          window.history.pushState(null, '', url.toString());
          window.dispatchEvent(new PopStateEvent('popstate'));
        }}
      />

      {/* X-11 (UX audit, 2026-05-03) — share-link affordance promoted above
          the sample gallery. Pre-fix this lived at the bottom of the page,
          which meant users who arrived via a teammate's Slack-shared report
          URL had to scroll past sample starters before realising they could
          paste the link. Now it sits between the checklist and the gallery,
          and auto-pre-fills if the clipboard already holds a /reports/shared/
          link (the most common arrival path for a shared report). */}
      <ShareLinkContinue />

      {/* Sample gallery — P-12 (UX audit) re-ranks by detected stack so a
          Python project sees Python starters first, etc. Falls back to
          registry order when no language is detected yet. */}
      {!loading && rankedSamples.length > 0 && (
        <div className='space-y-2'>
          <div className='flex items-center justify-center gap-2 text-[11px] uppercase tracking-wider text-muted font-mono'>
            <Sparkles className='h-3 w-3' />
            {tailoredHintVisible ? 'Starters tailored to your stack' : 'Or pick a starter'}
          </div>
          <div className='grid grid-cols-1 sm:grid-cols-3 gap-2'>
            {rankedSamples.slice(0, 6).map((s) => {
              const langs = languagesForProjectType(s.projectType);
              const isMatch = tailoredHintVisible && langs.some((l) => projectLanguages.has(l));
              return (
                <button
                  key={s.id}
                  disabled={!canLaunch}
                  onClick={() =>
                    navigate(`/projects/${projectId}/executions/new`, {
                      state: {sampleSpec: s},
                    })
                  }
                  className={cn(
                    'rounded-sm border p-3 text-left transition-colors relative',
                    canLaunch
                      ? isMatch
                        ? 'border-accent-blue/40 bg-accent-blue/5 hover:border-accent-blue/60 hover:bg-accent-blue/10'
                        : 'border-[var(--border-default)] bg-[var(--gray-100)] hover:border-accent-blue/40 hover:bg-accent-blue/5'
                      : 'border-[var(--border-default)] bg-[var(--gray-100)] opacity-50 cursor-not-allowed',
                  )}
                  title={
                    isMatch
                      ? `${s.category} · ${s.complexity} · matches your stack`
                      : `${s.category} · ${s.complexity}`
                  }
                >
                  {isMatch && (
                    <span
                      className='absolute top-1.5 right-1.5 text-[9px] font-mono uppercase px-1 py-0.5 rounded-sm bg-accent-blue/15 text-accent-blue'
                      aria-label='matches your stack'
                    >
                      match
                    </span>
                  )}
                  <div className='flex items-center gap-1.5 mb-1'>
                    <FileText className='h-3 w-3 text-accent-blue' />
                    <span className='text-xs font-medium text-primary'>{s.title}</span>
                  </div>
                  <p className='text-[10.5px] text-muted'>
                    {s.category} · {s.complexity.toLowerCase()}
                  </p>
                  {canLaunch && (
                    <div className='mt-2 inline-flex items-center gap-0.5 text-[10px] text-accent-blue'>
                      Use this spec <ArrowRight className='h-2.5 w-2.5' />
                    </div>
                  )}
                </button>
              );
            })}
          </div>
        </div>
      )}

      {/* M-023 (Wave 2) — saved spec templates (project + org-shared) for
          users who already have a library. Loads silently and renders
          nothing when there are no templates yet, so cold projects keep
          their clean empty-state. */}
      {canLaunch && (
        <TemplateGrid
          projectId={projectId}
          heading='Or start from a saved template'
          onPick={(text) =>
            navigate(`/projects/${projectId}/executions/new`, {
              state: {templateRequirement: text},
            })
          }
        />
      )}
    </div>
  );
}

/**
 * ShareLinkContinue — small affordance per UX report §6.3 #2: paste a
 * shared report link to fork it into a new run. Backend's share endpoint
 * already returns a token; this resolves to the run id and pre-fills
 * the launch form.
 */
function ShareLinkContinue() {
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState('');
  const [autoDetected, setAutoDetected] = useState(false);

  // X-11 (UX audit, 2026-05-03) — best-effort clipboard read. Many browsers
  // gate clipboard.readText() behind a user gesture; in that case the
  // promise rejects silently and we fall back to the manual button. When
  // the clipboard does hold a /reports/shared/ URL we auto-open the input
  // pre-filled so the next click is "Open" not "paste, then Open". The
  // permission prompt isn't surfaced — failure is silent.
  useEffect(() => {
    let cancelled = false;
    const tryClipboard = async () => {
      try {
        if (typeof navigator === 'undefined' || !navigator.clipboard?.readText) return;
        const text = await navigator.clipboard.readText();
        if (cancelled) return;
        if (text && /\/reports\/shared\/[A-Za-z0-9_-]+/.test(text)) {
          setUrl(text.trim());
          setOpen(true);
          setAutoDetected(true);
        }
      } catch {
        // No permission, no clipboard, no problem — manual entry still works.
      }
    };
    tryClipboard();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className='text-center text-[11px]'>
      {!open ? (
        <button
          onClick={() => setOpen(true)}
          className='text-muted hover:text-accent-blue transition-colors'
        >
          Have a share link from a teammate? Continue from it →
        </button>
      ) : (
        <div className='space-y-1 max-w-md mx-auto'>
          {autoDetected && (
            <div className='text-[10.5px] text-status-success'>
              We detected a share link in your clipboard.
            </div>
          )}
          <div className='inline-flex items-center gap-1 w-full'>
            <input
              type='url'
              value={url}
              onChange={(e) => {
                setUrl(e.target.value);
                setAutoDetected(false);
              }}
              placeholder='https://app.executespec.ai/reports/shared/...'
              className='flex-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--bg-primary)] text-primary placeholder:text-muted/50 focus:outline-none focus:border-accent-blue'
            />
            <button
              onClick={() => {
                if (!url) return;
                const m = url.match(/\/reports\/shared\/([^/?]+)/);
                if (m) {
                  window.location.href = `/reports/shared/${m[1]}`;
                }
              }}
              className='text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20'
            >
              Open
            </button>
            <button
              onClick={() => {
                setOpen(false);
                setAutoDetected(false);
              }}
              className='text-[11px] text-muted hover:text-primary px-1'
            >
              ✕
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
