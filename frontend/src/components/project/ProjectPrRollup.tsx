import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronDown, ChevronRight, ExternalLink, Loader2 } from 'lucide-react';
import { executionApi } from '@/lib/execution-api';
import type { PrRollupEntry } from '@/types/execution';
import { getPullRequestSnapshot, type PullRequestSnapshot } from '@/lib/api';
import DiffView from '@/components/shared/DiffView';
import { cn } from '@/lib/utils';

export default function ProjectPrRollup({ projectId }: { projectId: string }) {
  const [prs, setPrs] = useState<PrRollupEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    executionApi.listProjectPrs(projectId)
        .then(setPrs)
        .catch((e: any) => setError(e?.message ?? 'Failed to load PRs'));
  }, [projectId]);

  if (error) return <div className="text-[11px] text-accent-red">{error}</div>;
  if (prs == null) return <div className="text-[11px] text-muted">Loading PRs…</div>;
  if (prs.length === 0) {
    return <div className="text-[12px] text-muted">No pull requests yet.</div>;
  }

  const launchEntries = prs.filter((p) => !p.repoFullName);
  const runEntries = prs.filter((p) => !!p.repoFullName);

  const byLaunch: Record<string, PrRollupEntry[]> = {};
  launchEntries.forEach((p) => { (byLaunch[p.launchId] ??= []).push(p); });
  const byRun: Record<string, PrRollupEntry[]> = {};
  runEntries.forEach((p) => { (byRun[p.launchId] ??= []).push(p); });

  return (
    <div className="space-y-4">
      {Object.entries(byLaunch).map(([launchId, group]) => (
        <PrGroup key={`launch-${launchId}`} label="Launch"
          idShort={launchId.substring(0, 8)}
          to={`/projects/${projectId}/executions/${launchId}`}
          prs={group} />
      ))}
      {Object.entries(byRun).map(([runId, group]) => (
        <PrGroup key={`run-${runId}`} label="Run"
          idShort={runId.substring(0, 8)}
          to={`/runs/${runId}`}
          prs={group} />
      ))}
    </div>
  );
}

function PrGroup({
  label, idShort, to, prs,
}: { label: string; idShort: string; to: string; prs: PrRollupEntry[] }) {
  return (
    <div>
      <div className="flex items-center gap-2 mb-1 text-[11px] text-muted">
        <span>{label}</span>
        <Link to={to} className="font-mono text-accent-blue hover:underline">{idShort}</Link>
        <span>· {prs.length} PR{prs.length > 1 ? 's' : ''}</span>
      </div>
      <div className="space-y-1">
        {prs.map((pr) => <PrRow key={pr.prUrl} pr={pr} />)}
      </div>
    </div>
  );
}

function statusPill(status: string): string {
  if (status === 'MERGED')
    return 'text-[10px] px-1.5 py-0.5 rounded border text-emerald-400 border-emerald-500/40';
  if (status === 'CLOSED')
    return 'text-[10px] px-1.5 py-0.5 rounded border text-muted border-[var(--border-default)]';
  return 'text-[10px] px-1.5 py-0.5 rounded border text-accent-blue border-accent-blue/40';
}

function PrRow({ pr }: { pr: PrRollupEntry }) {
  const [open, setOpen] = useState(false);
  const [snap, setSnap] = useState<PullRequestSnapshot | null>(null);
  const [loading, setLoading] = useState(false);
  const [snapError, setSnapError] = useState<string | null>(null);

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (!next || snap || loading || !pr.repoFullName || !pr.prNumber) return;
    setLoading(true);
    setSnapError(null);
    try {
      const s = await getPullRequestSnapshot(pr.repoFullName, pr.prNumber);
      setSnap(s);
    } catch (e) {
      setSnapError(e instanceof Error ? e.message : 'Failed to load PR');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]">
      <div className="flex items-center gap-2 px-3 py-1.5 text-[11px]">
        <button
          onClick={toggle}
          disabled={!pr.repoFullName || !pr.prNumber}
          className="text-muted hover:text-primary disabled:opacity-30"
          title={!pr.repoFullName ? 'Preview available only for project-linked repos' : 'Preview PR'}
        >
          {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        </button>
        <span className={statusPill(pr.status)}>{pr.status}</span>
        <span className="font-mono">#{pr.prNumber ?? '—'}</span>
        <span className="truncate flex-1 font-mono">{pr.repoFullName ?? pr.prUrl}</span>
        <a
          href={pr.prUrl}
          target="_blank"
          rel="noreferrer"
          className="text-muted hover:text-accent-blue inline-flex items-center gap-1"
          title="Open on GitHub"
        >
          <ExternalLink className="h-3 w-3" />
        </a>
      </div>
      {open && (
        <div className="border-t border-[var(--border-default)] px-3 py-2 space-y-2 text-[11px]">
          {loading && <div className="flex items-center gap-1 text-muted"><Loader2 className="h-3 w-3 animate-spin" /> Fetching PR from GitHub…</div>}
          {snapError && <div className="text-accent-red">{snapError}</div>}
          {snap && <PrPreview snap={snap} />}
          {!loading && !snap && !snapError && (
            <div className="text-muted">No preview available.</div>
          )}
        </div>
      )}
    </div>
  );
}

function PrPreview({ snap }: { snap: PullRequestSnapshot }) {
  return (
    <div className="space-y-2">
      {snap.title && (
        <div className="text-[12px] text-primary font-medium">{snap.title}</div>
      )}
      <div className="flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-muted font-mono">
        <span>branch: <span className="text-secondary">{snap.headBranch}</span> → <span className="text-secondary">{snap.baseBranch}</span></span>
        <span>files: <span className={cn(snap.changedFiles > 0 ? 'text-primary' : 'text-muted')}>{snap.changedFiles}</span></span>
        <span className="text-emerald-400">+{snap.additions}</span>
        <span className="text-accent-red">−{snap.deletions}</span>
      </div>
      {snap.body && snap.body.trim() && (
        <pre className="text-[11px] whitespace-pre-wrap text-secondary bg-[var(--gray-200)] rounded-sm px-2 py-1.5 max-h-32 overflow-y-auto">
          {snap.body}
        </pre>
      )}
      {snap.diffPreview && <DiffView diff={snap.diffPreview} />}
    </div>
  );
}
