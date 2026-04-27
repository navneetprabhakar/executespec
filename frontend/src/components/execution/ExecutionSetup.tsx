import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useProjectStore } from '@/stores/useProjectStore';
import { useExecutionStore } from '@/stores/useExecutionStore';
import { executionApi } from '@/lib/execution-api';
import { getProject } from '@/lib/api';
import TemplatePicker from './TemplatePicker';
import StalenessWarning from './StalenessWarning';
import EstimateChip from '@/components/runs/EstimateChip';
import type { NewRepoSpec, Scaffold, ContinueOnPrConfig } from '@/types/execution';

const PROVIDERS = [
  { key: '',          label: 'Use project default' },
  { key: 'ANTHROPIC', label: 'Anthropic · Claude' },
  { key: 'OPENAI',    label: 'OpenAI · GPT' },
  { key: 'GEMINI',    label: 'Google · Gemini' },
];

interface Props {
  projectId: string;
  onSubmitted: (executionId: string) => void;
  /** v2.5.7 — pre-fill for the "Address review" flow. */
  continueOnPr?: ContinueOnPrConfig;
}

const DEFAULT_TECH = 'node-backend';

export default function ExecutionSetup({ projectId, onSubmitted, continueOnPr }: Props) {
  const location = useLocation();
  const { repos, fetchRepos } = useProjectStore();
  const { createExecution, loading, error } = useExecutionStore();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [requirement, setRequirement] = useState('');
  const [jiraEnabled, setJiraEnabled] = useState(false);
  const [confluenceUrl, setConfluenceUrl] = useState('');
  const [figmaUrl, setFigmaUrl] = useState('');
  const [branch, setBranch] = useState('');
  const [providerOverride, setProviderOverride] = useState('');
  const [projectDefaultProvider, setProjectDefaultProvider] = useState<string | null>(null);
  const [projectGithubOwner, setProjectGithubOwner] = useState<string>('');
  const [newRepos, setNewRepos] = useState<NewRepoSpec[]>([]);
  const [scaffolds, setScaffolds] = useState<Scaffold[]>([]);
  const [ownerChecks, setOwnerChecks] = useState<Record<number, string>>({});
  const [showConfirm, setShowConfirm] = useState(false);

  // M-023 (Wave 2) — pre-fill from a TemplateGrid pick on the project
  // EmptyProjectLanding. Carries the requirement text via React Router
  // state, applied once on mount.
  useEffect(() => {
    const tmpl = (location.state as { templateRequirement?: string } | null)?.templateRequirement;
    if (tmpl && tmpl.trim()) {
      setRequirement(tmpl);
    }
    // Intentionally not depending on `location.state` so re-renders don't
    // overwrite typed-over edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Pre-fill from continue-on-PR config (Address Review flow).
  useEffect(() => {
    if (!continueOnPr) return;
    setRequirement(`Address PR review comments for #${continueOnPr.prNumber}:\n\n${continueOnPr.reviewComments}`);
    setBranch(continueOnPr.headBranch);
    // Auto-select the repo that owns the PR.
    const match = repos.find((r) => r.repo?.githubRepoFullName === continueOnPr.repoFullName);
    if (match) setSelected(new Set([match.repoId]));
  }, [continueOnPr, repos]);

  useEffect(() => { fetchRepos(projectId); }, [projectId, fetchRepos]);
  useEffect(() => {
    executionApi.listScaffolds().then(setScaffolds).catch(() => setScaffolds([]));
  }, []);
  useEffect(() => {
    getProject(projectId)
        .then((p) => {
          setProjectDefaultProvider(p.aiProvider ?? null);
          setProjectGithubOwner(p.githubOwner ?? '');
        })
        .catch(() => setProjectDefaultProvider(null));
  }, [projectId]);

  const toggle = (id: string) => {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelected(next);
  };

  const addNewRepo = () => {
    setNewRepos([...newRepos, {
      name: '', description: '', techHint: DEFAULT_TECH,
      githubOwner: projectGithubOwner, visibility: 'private',
    }]);
  };
  const patchNewRepo = (i: number, patch: Partial<NewRepoSpec>) => {
    const next = [...newRepos];
    next[i] = { ...next[i], ...patch };
    setNewRepos(next);
  };
  const removeNewRepo = (i: number) => {
    setNewRepos(newRepos.filter((_, idx) => idx !== i));
    const next = { ...ownerChecks };
    delete next[i];
    setOwnerChecks(next);
  };
  const validateOwner = async (i: number) => {
    const spec = newRepos[i];
    if (!spec.githubOwner) return;
    try {
      const res = await executionApi.validateOwner(projectId, spec.githubOwner);
      setOwnerChecks({ ...ownerChecks, [i]: (res.canCreate ? '✓ ' : '✗ ') + res.reason });
    } catch {
      setOwnerChecks({ ...ownerChecks, [i]: '✗ validation failed' });
    }
  };

  const modePreview = useMemo(() => {
    const existing = selected.size;
    const created = newRepos.length;
    const total = existing + created;
    if (total === 0) return 'Select at least one repo';
    if (total === 1) return '1 repo → single-repo run (classification may decompose)';
    const parts = [];
    if (existing) parts.push(`${existing} existing`);
    if (created) parts.push(`${created} new`);
    return `${parts.join(' + ')} → ${total}-solution execution`;
  }, [selected, newRepos]);

  const canSubmit = requirement.trim().length > 0
      && (selected.size > 0 || newRepos.length > 0) && !loading
      && newRepos.every(r => r.name.trim() && r.githubOwner.trim());

  const submit = async () => {
    setShowConfirm(true);
  };
  const confirmLaunch = async () => {
    setShowConfirm(false);
    const id = await createExecution(projectId, {
      requirement: requirement.trim(),
      repoIds: Array.from(selected),
      newRepos,
      jiraEnabled,
      confluenceUrl: confluenceUrl.trim() || undefined,
      figmaUrl: figmaUrl.trim() || undefined,
      aiProvider: providerOverride || undefined,
      branch: branch.trim() || undefined,
      ...(continueOnPr ? {
        sourceExecutionId: continueOnPr.sourceExecutionId,
        continuePrRepoFullName: continueOnPr.repoFullName,
        continuePrNumber: continueOnPr.prNumber,
      } : {}),
    });
    onSubmitted(id);
  };

  return (
    <div className="space-y-4">
      <div>
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-medium text-primary">Select existing repos</h3>
          <Link
            to={`/projects/${projectId}/dashboard`}
            className="text-xs text-accent-blue hover:underline"
          >
            + Link repo
          </Link>
        </div>
        <div className="space-y-1.5">
          {(repos ?? []).map((r: any) => {
            // Store returns ProjectRepoLink ({repo: {...}, repoId, projectId}); fall back to
            // flat shapes for robustness.
            const rid = r.repo?.id ?? r.repoId ?? r.id;
            const name = r.repo?.githubRepoFullName ?? r.githubRepoFullName ?? r.repoName ?? rid;
            const scope = r.repo?.scope;
            return (
              <label key={rid} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={selected.has(rid)} onChange={() => toggle(rid)} />
                <span className="font-mono">{name}</span>
                {scope && (
                  <span className="text-[11px] uppercase tracking-wide text-muted">{scope}</span>
                )}
              </label>
            );
          })}
          {(!repos || repos.length === 0) && (
            <div className="text-xs text-muted">
              No repos linked to this project yet. Use "+ Link repo" above (opens the Repos tab)
              or "+ Create new repo" below to scaffold a fresh one.
            </div>
          )}
        </div>
        {selected.size > 0 && (
          <div className="mt-2">
            <StalenessWarning projectId={projectId} selectedRepoIds={Array.from(selected)} />
          </div>
        )}
      </div>

      <div>
        <div className="flex items-center justify-between mb-1">
          <h3 className="text-sm font-medium text-primary">Create new repos</h3>
          <button
            onClick={addNewRepo}
            className="text-xs text-accent-blue hover:underline"
          >+ Create new repo</button>
        </div>
        <div className="space-y-2">
          {newRepos.map((spec, i) => (
            <div key={i} className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2.5 text-xs space-y-1.5">
              <div className="flex items-center gap-2">
                <span className="text-muted">New #{i + 1}</span>
                <button className="ml-auto text-accent-red hover:underline"
                  onClick={() => removeNewRepo(i)}>Remove</button>
              </div>
              <input
                placeholder="repo name"
                value={spec.name}
                onChange={(e) => patchNewRepo(i, { name: e.target.value })}
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 font-mono"
              />
              <input
                placeholder="description"
                value={spec.description ?? ''}
                onChange={(e) => patchNewRepo(i, { description: e.target.value })}
                className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1"
              />
              <div className="flex gap-2">
                <select
                  value={spec.techHint}
                  onChange={(e) => patchNewRepo(i, { techHint: e.target.value })}
                  className="flex-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1"
                >
                  {scaffolds.map(s => (
                    <option key={s.key} value={s.key}>{s.label}</option>
                  ))}
                </select>
                <select
                  value={spec.visibility}
                  onChange={(e) => patchNewRepo(i, { visibility: e.target.value as 'private' | 'public' })}
                  className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1"
                >
                  <option value="private">private</option>
                  <option value="public">public</option>
                </select>
              </div>
              <div className="flex gap-2 items-center">
                <input
                  placeholder="github owner / org"
                  value={spec.githubOwner}
                  onChange={(e) => patchNewRepo(i, { githubOwner: e.target.value })}
                  onBlur={() => validateOwner(i)}
                  className="flex-1 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 font-mono"
                />
                <span className={ownerChecks[i]?.startsWith('✓')
                    ? 'text-status-success text-[11px]'
                    : 'text-accent-red text-[11px]'}>
                  {ownerChecks[i] ?? ''}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-sm font-medium text-primary">Requirement</h3>
          <TemplatePicker
            projectId={projectId}
            currentRequirement={requirement}
            onPick={(text) => setRequirement(text)}
          />
        </div>
        <textarea
          value={requirement}
          onChange={(e) => setRequirement(e.target.value)}
          rows={8}
          className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm font-mono"
          placeholder="Describe the cross-repo change in plain English…"
        />
        <EstimateChip projectId={projectId} requirement={requirement} minLength={50} className="mt-1" />
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
        <label className="text-xs text-secondary space-y-1">
          <span>Confluence spec URL <span className="text-muted">(optional)</span></span>
          <input
            type="url"
            value={confluenceUrl}
            onChange={(e) => setConfluenceUrl(e.target.value)}
            placeholder="https://your-org.atlassian.net/wiki/…"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm font-mono"
          />
        </label>
        <label className="text-xs text-secondary space-y-1">
          <span>Figma design URL <span className="text-muted">(optional)</span></span>
          <input
            type="url"
            value={figmaUrl}
            onChange={(e) => setFigmaUrl(e.target.value)}
            placeholder="https://www.figma.com/file/…"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm font-mono"
          />
        </label>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3 max-w-2xl">
        <label className="text-xs text-secondary space-y-1 block">
          <span>AI provider</span>
          <select
            value={providerOverride}
            onChange={(e) => setProviderOverride(e.target.value)}
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm"
          >
            {PROVIDERS.map((p) => (
              <option key={p.key} value={p.key}>
                {p.key === '' && projectDefaultProvider
                    ? `Use project default (${projectDefaultProvider})`
                    : p.label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-xs text-secondary space-y-1 block">
          <span>Branch <span className="text-muted">(optional — default: repo's main/default)</span></span>
          <input
            type="text"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            placeholder="main"
            className="w-full rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1 text-sm font-mono"
          />
        </label>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={jiraEnabled} onChange={(e) => setJiraEnabled(e.target.checked)} />
        <span>Sync this execution with JIRA (requires org-level JIRA integration + project key)</span>
      </label>

      <div className="text-xs text-muted">{modePreview}</div>
      {error && <div className="text-xs text-accent-red">{error}</div>}

      <button
        disabled={!canSubmit}
        onClick={submit}
        className="rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-3 py-1.5 text-sm disabled:opacity-40"
      >
        Launch
      </button>

      {showConfirm && (
        <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
          <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md p-4 w-full max-w-lg space-y-3 text-sm">
            <h3 className="text-[13px] font-medium text-primary">Confirm Launch</h3>
            <div>{modePreview}</div>
            {newRepos.length > 0 && (
              <div>
                <div className="text-xs uppercase tracking-wide text-muted">Will create on GitHub:</div>
                <ul className="list-disc ml-5 text-xs font-mono">
                  {newRepos.map((r, i) => (
                    <li key={i}>{r.githubOwner}/{r.name} · {r.visibility} · {r.techHint}</li>
                  ))}
                </ul>
              </div>
            )}
            <div className="text-xs text-muted">
              Estimated cost will be computed by the planner after decomposition.
              You can still abort before execution.
            </div>
            <div className="flex justify-end gap-2">
              <button onClick={() => setShowConfirm(false)} className="text-muted px-2 py-1">Cancel</button>
              <button
                onClick={confirmLaunch}
                className="rounded-sm border border-status-success/40 bg-status-success/10 text-status-success px-3 py-1"
              >
                Confirm & Launch
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
