import {useEffect, useState} from 'react';
import {Loader2, Sparkles} from 'lucide-react';
import * as api from '@/lib/api';
import type {StalenessReport} from '@/lib/api';
import type {ArchitectureDoc} from '@/types/project';
import {MarkdownContent} from '@/components/shared/MarkdownContent';
import {useIsProjectAdmin} from '@/hooks/useIsProjectAdmin';
import {cn} from '@/lib/utils';

export default function ArchitectureViewer({
  projectId,
  repoCount = null,
}: {
  projectId: string;
  repoCount?: number | null;
}) {
  // v2.5.14 (Phase 1.5) — Generate button now knows whether repos are linked
  // so we can disable it with an inline reason instead of letting it fail in
  // the backend and bubble up a useless "Generation failed" error.
  const noRepos = repoCount === 0;
  // P-25 (UX audit, 2026-05-03) — Generate is admin-only on the backend.
  // Pre-fix, DEVELOPER / VIEWER roles saw the button live, clicked it, and
  // got a 403. Now we disable + tooltip the cause — non-admins see the same
  // "this is admin-only" treatment as everywhere else (Launch, Add Repo).
  const isAdmin = useIsProjectAdmin();
  const [doc, setDoc] = useState<ArchitectureDoc | null>(null);
  const [versions, setVersions] = useState<ArchitectureDoc[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [staleness, setStaleness] = useState<StalenessReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [annotationDraft, setAnnotationDraft] = useState('');
  const [editingAnnotation, setEditingAnnotation] = useState(false);
  const [branchOverride, setBranchOverride] = useState('');

  const refresh = async () => {
    setLoading(true);
    try {
      const [latest, list, stale] = await Promise.all([
        api.getArchitectureDoc(projectId).catch(() => null),
        api.getArchitectureVersions(projectId).catch(() => []),
        api.getArchitectureStaleness(projectId).catch(() => null),
      ]);
      setDoc(latest);
      setVersions(list);
      setSelectedVersion(latest?.version ?? null);
      setAnnotationDraft(latest?.userAnnotations ?? '');
      setStaleness(stale);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    refresh();
  }, [projectId]);

  const generate = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.analyseProject(projectId, branchOverride.trim() || undefined);
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Generation failed');
    } finally {
      setBusy(false);
    }
  };

  const saveAnnotation = async () => {
    setBusy(true);
    try {
      await api.annotateArchitecture(projectId, annotationDraft);
      setEditingAnnotation(false);
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const shown =
    selectedVersion != null ? (versions.find((v) => v.version === selectedVersion) ?? doc) : doc;
  const [diffOpen, setDiffOpen] = useState(false);
  const previous = shown ? versions.find((v) => v.version === (shown.version ?? 0) - 1) : undefined;

  return (
    <div className='space-y-3 text-xs'>
      {/* Toolbar */}
      <div className='flex flex-col sm:flex-row sm:items-center justify-between gap-2 sm:gap-3'>
        <div className='flex items-center gap-2 flex-wrap min-w-0'>
          <h2 className='text-[13px] font-medium text-primary'>Architecture document</h2>
          {staleness?.stale && (
            <span
              className='text-[11px] uppercase tracking-wide bg-accent-amber/10 text-accent-amber px-1.5 py-0.5 rounded-sm'
              title={staleness.repos
                .filter((r) => r.stale)
                .map((r) => r.repoFullName)
                .join('\n')}
            >
              Stale — {staleness.staleCount}/{staleness.totalCount} repos ahead
            </span>
          )}
          {versions.length > 0 && (
            <select
              className='text-xs rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary px-2 py-0.5 focus:outline-none focus:border-accent-blue'
              value={selectedVersion ?? ''}
              onChange={(e) => setSelectedVersion(Number(e.target.value))}
            >
              {versions.map((v) => (
                <option key={v.version} value={v.version}>
                  v{v.version} · {new Date(v.generatedAt).toLocaleDateString()}
                </option>
              ))}
            </select>
          )}
        </div>
        {shown && previous && (
          <button
            onClick={() => setDiffOpen((o) => !o)}
            className='inline-flex items-center gap-1 text-[11px] font-medium px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary hover:border-accent-blue/40'
            title={`Compare v${shown.version} against v${previous.version}`}
          >
            {diffOpen ? 'Hide diff' : `Diff vs v${previous.version}`}
          </button>
        )}
        <input
          type='text'
          placeholder='branch (default)'
          value={branchOverride}
          onChange={(e) => setBranchOverride(e.target.value)}
          className='w-32 text-[11px] font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary px-1.5 py-0.5 focus:outline-none focus:border-accent-blue'
          title="Optional branch override (applies to all linked repos). Leave empty for each repo's default."
        />
        <div className='flex flex-col items-end gap-0.5'>
          {(() => {
            // Combine the two disable reasons into one tooltip — admin-gate
            // takes priority because non-admins can't fix the repo gap themselves.
            const adminGated = !isAdmin;
            const disabled = busy || noRepos || adminGated;
            const title = adminGated
              ? 'Only org admins can regenerate the architecture. Ask an admin.'
              : noRepos
                ? 'Link at least one repository under the Repos tab first.'
                : undefined;
            const inlineReason = adminGated
              ? 'Admin only'
              : noRepos
                ? 'Link a repo to enable'
                : null;
            return (
              <>
                <button
                  onClick={generate}
                  disabled={disabled}
                  title={title}
                  aria-label={
                    adminGated
                      ? 'Generate architecture (admin only)'
                      : noRepos
                        ? 'Generate architecture (link a repo first)'
                        : 'Generate architecture'
                  }
                  className={cn(
                    'inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-sm border transition-colors',
                    disabled
                      ? 'border-[var(--border-default)] text-muted opacity-50 cursor-not-allowed'
                      : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20',
                  )}
                >
                  {busy ? (
                    <>
                      <Loader2 className='h-3 w-3 animate-spin' /> Generating…
                    </>
                  ) : (
                    <>
                      <Sparkles className='h-3 w-3' /> {doc ? 'Regenerate' : 'Generate'}
                    </>
                  )}
                </button>
                {inlineReason && (
                  <span className='text-[10px] text-muted italic'>{inlineReason}</span>
                )}
              </>
            );
          })()}
        </div>
      </div>

      {error && <div className='text-xs text-accent-red'>{error}</div>}
      {loading && <div className='text-xs text-muted'>Loading…</div>}

      {!loading && !shown && (
        <div className='text-xs text-muted border border-dashed border-[var(--border-default)] rounded-sm p-6 text-center bg-[var(--gray-100)]'>
          No architecture document yet. Link some repos under the Repos tab and click Generate.
        </div>
      )}

      {shown && diffOpen && previous && (
        <>
          <ArchDiffBlock
            oldText={previous.content}
            newText={shown.content}
            oldV={previous.version}
            newV={shown.version}
          />
          <ArchSourceChanges
            projectId={projectId}
            fromVersion={previous.version}
            toVersion={shown.version}
          />
        </>
      )}

      {shown && !diffOpen && (
        <>
          {/* Doc body */}
          <div
            className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 prose prose-sm prose-invert max-w-none
                          prose-headings:text-primary prose-headings:font-medium
                          prose-h1:text-[13px] prose-h2:text-sm prose-h3:text-sm
                          prose-p:text-secondary prose-p:text-xs
                          prose-li:text-secondary prose-li:text-xs
                          prose-strong:text-primary
                          prose-code:text-accent-blue prose-code:bg-[var(--gray-200)] prose-code:px-1 prose-code:rounded-sm prose-code:before:content-none prose-code:after:content-none
                          prose-a:text-accent-blue prose-a:no-underline hover:prose-a:underline
                          prose-hr:border-[var(--border-default)]'
          >
            <MarkdownContent content={shown.content} />
          </div>

          {/* Annotations */}
          <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
            <div className='flex items-center justify-between mb-2'>
              <h3 className='text-xs font-medium text-primary'>Annotations</h3>
              {!editingAnnotation && (
                <button
                  onClick={() => setEditingAnnotation(true)}
                  className='text-[11px] text-accent-blue hover:underline'
                >
                  edit
                </button>
              )}
            </div>
            {editingAnnotation ? (
              <div className='space-y-2'>
                <textarea
                  value={annotationDraft}
                  onChange={(e) => setAnnotationDraft(e.target.value)}
                  rows={5}
                  placeholder="Cross-cutting context the planner should know about (e.g. 'auth handled by external Keycloak')."
                  className='w-full text-xs font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted px-2 py-1.5 focus:outline-none focus:border-accent-blue resize-y'
                />
                <div className='flex justify-end gap-2'>
                  <button
                    onClick={() => {
                      setEditingAnnotation(false);
                      setAnnotationDraft(shown.userAnnotations ?? '');
                    }}
                    className='text-xs px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary'
                  >
                    Cancel
                  </button>
                  <button
                    onClick={saveAnnotation}
                    disabled={busy}
                    className='text-xs px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50'
                  >
                    Save
                  </button>
                </div>
              </div>
            ) : shown.userAnnotations ? (
              <pre className='text-xs whitespace-pre-wrap font-mono text-secondary'>
                {shown.userAnnotations}
              </pre>
            ) : (
              <p className='text-xs text-muted'>No annotations yet.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}

/** Minimal line-diff (LCS-based) rendered GitHub-style. */
function ArchDiffBlock({
  oldText,
  newText,
  oldV,
  newV,
}: {
  oldText: string;
  newText: string;
  oldV: number;
  newV: number;
}) {
  const rows = diffLines(oldText.split('\n'), newText.split('\n'));
  const adds = rows.filter((r) => r.kind === 'add').length;
  const dels = rows.filter((r) => r.kind === 'del').length;
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden'>
      <div className='px-3 py-1.5 bg-[var(--gray-200)] border-b border-[var(--border-default)] text-[11px] font-mono flex items-center gap-3'>
        <span className='text-secondary'>
          v{oldV} → v{newV}
        </span>
        <span className='text-status-success'>+{adds}</span>
        <span className='text-accent-red'>-{dels}</span>
      </div>
      <pre className='text-xs font-mono leading-relaxed overflow-x-auto m-0'>
        {rows.map((r, i) => (
          <div
            key={i}
            className={
              r.kind === 'add'
                ? 'bg-status-success/10 text-status-success px-3'
                : r.kind === 'del'
                  ? 'bg-status-error/10 text-status-error px-3'
                  : 'text-secondary px-3'
            }
          >
            <span className='inline-block w-4 select-none text-muted'>
              {r.kind === 'add' ? '+' : r.kind === 'del' ? '-' : ' '}
            </span>
            {r.text || '\u00A0'}
          </div>
        ))}
      </pre>
    </div>
  );
}

type DiffRow = {kind: 'add' | 'del' | 'ctx'; text: string};

function diffLines(a: string[], b: string[]): DiffRow[] {
  const n = a.length,
    m = b.length;
  const lcs: number[][] = Array.from({length: n + 1}, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffRow[] = [];
  let i = 0,
    j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({kind: 'ctx', text: a[i]});
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({kind: 'del', text: a[i++]});
    } else {
      out.push({kind: 'add', text: b[j++]});
    }
  }
  while (i < n) out.push({kind: 'del', text: a[i++]});
  while (j < m) out.push({kind: 'add', text: b[j++]});
  return out;
}

/**
 * v2.5.5 (UX-4.2) — source-file changes section that sits below the text diff.
 * Hits the diff endpoint which calls GitHub's compare API against each repo's
 * SHAs captured at the two architecture-doc versions. Empty/silent when the
 * call fails (private repo without PAT, SHA missing, etc.) so it never breaks
 * the primary text-diff view.
 */
function ArchSourceChanges({
  projectId,
  fromVersion,
  toVersion,
}: {
  projectId: string;
  fromVersion: number;
  toVersion: number;
}) {
  const [report, setReport] = useState<import('@/lib/api').ArchDiffReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    api
      .getArchitectureDiff(projectId, fromVersion, toVersion)
      .then((r) => {
        if (alive) setReport(r);
      })
      .catch((e) => {
        if (alive) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [projectId, fromVersion, toVersion]);

  if (loading) {
    return (
      <div className='text-xs text-muted rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2'>
        Loading source file changes…
      </div>
    );
  }
  if (error) {
    return (
      <div className='text-[11px] text-muted rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2'>
        Source changes unavailable ({error}).
      </div>
    );
  }
  if (!report || report.totalFiles === 0) {
    return (
      <div className='text-[11px] text-muted rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2'>
        No source file changes between v{fromVersion} and v{toVersion}.
      </div>
    );
  }

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-2 space-y-2'>
      <div className='text-[11px] uppercase tracking-wide text-muted'>
        Source file changes · {report.totalFiles} file{report.totalFiles === 1 ? '' : 's'}
      </div>
      {report.perRepo.map((rp) => {
        const added = rp.files.filter((f) => f.status === 'added');
        const modified = rp.files.filter((f) => f.status === 'modified');
        const removed = rp.files.filter((f) => f.status === 'removed');
        const renamed = rp.files.filter((f) => f.status === 'renamed');
        return (
          <div key={rp.repoFullName} className='space-y-1'>
            <div className='text-xs font-mono text-primary'>{rp.repoFullName}</div>
            <FileGroup label='Added' files={added} tone='text-status-success' />
            <FileGroup label='Modified' files={modified} tone='text-accent-amber' />
            <FileGroup label='Removed' files={removed} tone='text-accent-red' />
            <FileGroup label='Renamed' files={renamed} tone='text-accent-blue' />
          </div>
        );
      })}
    </div>
  );
}

function FileGroup({
  label,
  files,
  tone,
}: {
  label: string;
  files: {filename: string; additions: number; deletions: number}[];
  tone: string;
}) {
  if (files.length === 0) return null;
  return (
    <div>
      <div className={`text-[11px] ${tone}`}>
        {label} ({files.length})
      </div>
      <ul className='pl-2 space-y-0.5'>
        {files.map((f) => (
          <li key={f.filename} className='text-[11px] font-mono text-muted flex items-center gap-2'>
            <span className='truncate flex-1'>{f.filename}</span>
            {(f.additions > 0 || f.deletions > 0) && (
              <span className='shrink-0'>
                <span className='text-status-success'>+{f.additions}</span>{' '}
                <span className='text-accent-red'>-{f.deletions}</span>
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
