import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Rocket, Code, Terminal, Layout, ArrowRight, Loader2 } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import {
  listOnboardingSamples, pickOnboardingSample,
  type SampleSpecDto,
} from '@/lib/api';
import { capture } from '@/lib/analytics';

/**
 * Wave 2.2 — /welcome page. Shown on first login post-verification (and
 * any time the user lands here without a sandbox). Presents 3 sample
 * specs the user can pick → auto-provisions a sandbox + redirects to
 * the run-submit form with the spec pre-filled.
 *
 * TTFV target: under 3 min from signup → run_complete. This page is
 * step 1 of 3 (other two: prefilled run-submit + celebrate-on-complete).
 * Full plan: docs/plans/ttfv-reduction-plan.md §5.
 */
export default function WelcomePage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [samples, setSamples] = useState<SampleSpecDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [picking, setPicking] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listOnboardingSamples()
      .then(setSamples)
      .catch((e) => setError(e?.message ?? 'Failed to load samples'))
      .finally(() => setLoading(false));
  }, []);

  const pick = async (sampleId: string) => {
    setPicking(sampleId);
    setError(null);
    try {
      capture('welcome_sample_picked', { sampleId });
      const result = await pickOnboardingSample(sampleId);
      // Stash prefill in sessionStorage — the run-submit page reads + clears.
      sessionStorage.setItem('sandboxPrefill', JSON.stringify({
        sampleId,
        spec: result.spec,
        projectId: result.projectId,
        projectType: result.projectType,
        preferredProvider: result.preferredProvider,
      }));
      navigate('/runs/new?onboarding=1');
    } catch (e: any) {
      setError(e?.message ?? 'Failed to pick sample');
    } finally {
      setPicking(null);
    }
  };

  const skip = () => {
    capture('welcome_skipped');
    navigate('/');
  };

  const iconFor = (category: string) => {
    if (category.toLowerCase().includes('frontend')) return <Layout className="h-5 w-5" />;
    if (category.toLowerCase().includes('cli')) return <Terminal className="h-5 w-5" />;
    return <Code className="h-5 w-5" />;
  };

  return (
    <div className="min-h-screen bg-[var(--geist-bg)] flex items-center justify-center p-4 sm:p-6">
      <div className="max-w-3xl w-full">
        <header className="text-center mb-8">
          <div className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-accent-blue/15 border border-accent-blue/40 mb-4">
            <Rocket className="h-6 w-6 text-accent-blue" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-semibold text-primary tracking-[-0.015em]">
            Welcome{user?.firstName ? `, ${user.firstName}` : ''}.
          </h1>
          <p className="mt-2 text-sm text-secondary max-w-xl mx-auto">
            Pick a sample to see ExecuteSpec in action. We'll pre-fill a spec and
            drop you at the run form — takes under 3 minutes from here to a
            verified score.
          </p>
        </header>

        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="h-5 w-5 animate-spin text-muted" />
          </div>
        ) : error ? (
          <div className="p-4 bg-status-error/10 border border-status-error/30 text-status-error rounded-sm text-sm text-center">
            {error}
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {samples.map((s) => (
              <button
                key={s.id}
                onClick={() => pick(s.id)}
                disabled={picking !== null}
                className={`group flex flex-col items-start text-left p-5 rounded-md bg-[var(--gray-100)] border border-[var(--border-default)] hover:border-accent-blue/60 transition-colors disabled:opacity-50 disabled:cursor-not-allowed`}
              >
                <div className="flex items-center gap-2 text-accent-blue mb-3">
                  {iconFor(s.category)}
                  <span className="text-[10px] font-mono uppercase tracking-wide text-muted">
                    {s.category}
                  </span>
                </div>
                <h3 className="text-sm font-semibold text-primary">{s.title}</h3>
                <div className="mt-3 flex items-center gap-2 flex-wrap">
                  <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-sm bg-[var(--gray-200)] text-muted uppercase">
                    {s.complexity}
                  </span>
                  <span className="text-[10px] font-mono px-1.5 py-0.5 rounded-sm bg-[var(--gray-200)] text-muted">
                    {s.preferredProvider}
                  </span>
                </div>
                <div className="mt-auto pt-4 flex items-center text-xs text-secondary group-hover:text-accent-blue">
                  {picking === s.id ? (
                    <>
                      <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" />
                      Preparing workspace…
                    </>
                  ) : (
                    <>
                      Start here
                      <ArrowRight className="h-3.5 w-3.5 ml-1 group-hover:translate-x-0.5 transition-transform" />
                    </>
                  )}
                </div>
              </button>
            ))}
          </div>
        )}

        <div className="text-center mt-8">
          <button
            onClick={skip}
            className="text-xs text-muted hover:text-secondary underline"
            disabled={picking !== null}
          >
            Skip — I'll link my own repo instead
          </button>
        </div>
      </div>
    </div>
  );
}
