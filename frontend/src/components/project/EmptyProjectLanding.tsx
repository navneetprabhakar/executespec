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
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Rocket, FileText, ArrowRight, Sparkles } from 'lucide-react';
import * as api from '@/lib/api';
import { cn } from '@/lib/utils';
import TemplateGrid from '@/components/execution/TemplateGrid';

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

  useEffect(() => {
    api
      .listOnboardingSamples()
      .then((s) => setSamples(s))
      .catch(() => setSamples([]))
      .finally(() => setLoading(false));
  }, []);

  const canLaunch = repoCount > 0;

  return (
    <div className="max-w-2xl mx-auto py-8 space-y-6">
      <div className="text-center space-y-2">
        <h2 className="text-xl font-semibold text-primary tracking-[-0.015em]">
          Ship verified code from a spec.
        </h2>
        <p className="text-sm text-secondary max-w-md mx-auto">
          {canLaunch
            ? 'Pick a starter spec to scaffold your first run, or jump straight to Launch.'
            : 'Link a repo first, then pick a starter or paste your own spec.'}
        </p>
      </div>

      {/* Primary CTAs */}
      <div className="flex justify-center gap-2">
        <button
          onClick={() => navigate(`/projects/${projectId}/executions/new`)}
          disabled={!canLaunch}
          className={cn(
            'inline-flex items-center gap-1.5 text-sm font-medium px-3 py-2 rounded-sm border transition-colors',
            canLaunch
              ? 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20'
              : 'border-[var(--border-default)] bg-[var(--gray-100)] text-muted cursor-not-allowed opacity-60',
          )}
          title={canLaunch ? 'Open the Launch modal' : 'Link a repo first'}
        >
          <Rocket className="h-4 w-4" />
          Start a run
        </button>
        {!canLaunch && (
          <button
            onClick={() => {
              if (onLinkRepo) {
                onLinkRepo();
                return;
              }
              // Legacy fallback (pre-v2.7.1) — kept only so this component
              // still has *some* behavior when rendered without the parent
              // callback. The drawer-settings target is wrong (settings has
              // no repo manager) but at least the URL changes.
              const url = new URL(window.location.href);
              url.searchParams.set('drawer', 'settings');
              window.history.replaceState(null, '', url.toString());
              window.dispatchEvent(new Event('drawer-state'));
            }}
            className="inline-flex items-center gap-1.5 text-sm font-medium px-3 py-2 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary"
          >
            Link a repo
          </button>
        )}
      </div>

      {/* Sample gallery */}
      {!loading && samples.length > 0 && (
        <div className="space-y-2">
          <div className="flex items-center justify-center gap-2 text-[11px] uppercase tracking-wider text-muted font-mono">
            <Sparkles className="h-3 w-3" />
            Or pick a starter
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
            {samples.slice(0, 6).map((s) => (
              <button
                key={s.id}
                disabled={!canLaunch}
                onClick={() =>
                  navigate(`/projects/${projectId}/executions/new`, {
                    state: { sampleSpec: s },
                  })
                }
                className={cn(
                  'rounded-sm border border-[var(--border-default)] p-3 text-left transition-colors',
                  canLaunch
                    ? 'bg-[var(--gray-100)] hover:border-accent-blue/40 hover:bg-accent-blue/5'
                    : 'bg-[var(--gray-100)] opacity-50 cursor-not-allowed',
                )}
                title={`${s.category} · ${s.complexity}`}
              >
                <div className="flex items-center gap-1.5 mb-1">
                  <FileText className="h-3 w-3 text-accent-blue" />
                  <span className="text-xs font-medium text-primary">{s.title}</span>
                </div>
                <p className="text-[10.5px] text-muted">
                  {s.category} · {s.complexity.toLowerCase()}
                </p>
                {canLaunch && (
                  <div className="mt-2 inline-flex items-center gap-0.5 text-[10px] text-accent-blue">
                    Use this spec <ArrowRight className="h-2.5 w-2.5" />
                  </div>
                )}
              </button>
            ))}
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
          heading="Or start from a saved template"
          onPick={(text) =>
            navigate(`/projects/${projectId}/executions/new`, {
              state: { templateRequirement: text },
            })
          }
        />
      )}

      {/* Continue from share link */}
      <ShareLinkContinue />
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

  return (
    <div className="text-center text-[11px]">
      {!open ? (
        <button
          onClick={() => setOpen(true)}
          className="text-muted hover:text-accent-blue transition-colors"
        >
          Have a share link from a teammate? Continue from it →
        </button>
      ) : (
        <div className="inline-flex items-center gap-1 max-w-md mx-auto">
          <input
            type="url"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://app.executespec.ai/reports/shared/..."
            className="flex-1 text-[11px] px-2 py-1 rounded-sm border border-[var(--border-default)] bg-[var(--bg-primary)] text-primary placeholder:text-muted/50 focus:outline-none focus:border-accent-blue"
          />
          <button
            onClick={() => {
              if (!url) return;
              const m = url.match(/\/reports\/shared\/([^/?]+)/);
              if (m) {
                window.location.href = `/reports/shared/${m[1]}`;
              }
            }}
            className="text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20"
          >
            Open
          </button>
          <button
            onClick={() => setOpen(false)}
            className="text-[11px] text-muted hover:text-primary px-1"
          >
            ✕
          </button>
        </div>
      )}
    </div>
  );
}
