import { useEffect, useState } from 'react';
import { Loader2, Sparkles } from 'lucide-react';
import * as api from '@/lib/api';
import type { StalenessReport } from '@/lib/api';
import type { ArchitectureDoc } from '@/types/project';
import { MarkdownContent } from '@/components/shared/MarkdownContent';
import { cn } from '@/lib/utils';

export default function ArchitectureViewer({ projectId }: { projectId: string }) {
  const [doc, setDoc] = useState<ArchitectureDoc | null>(null);
  const [versions, setVersions] = useState<ArchitectureDoc[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [staleness, setStaleness] = useState<StalenessReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [annotationDraft, setAnnotationDraft] = useState('');
  const [editingAnnotation, setEditingAnnotation] = useState(false);

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
      await api.generateArchitecture(projectId);
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

  const shown = selectedVersion != null
    ? versions.find((v) => v.version === selectedVersion) ?? doc
    : doc;

  return (
    <div className="space-y-3 text-[12px]">
      {/* Toolbar */}
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <div className="flex items-center gap-2 flex-wrap">
          <h2 className="text-[13px] font-medium text-primary">Architecture document</h2>
          {staleness?.stale && (
            <span
              className="text-[10px] uppercase tracking-wide bg-accent-amber/10 text-accent-amber px-1.5 py-0.5 rounded-sm"
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
              className="text-[11px] rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary px-2 py-0.5 focus:outline-none focus:border-accent-blue"
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
        <button
          onClick={generate}
          disabled={busy}
          className={cn(
            'inline-flex items-center gap-1 text-[11px] font-medium px-2.5 py-1 rounded-sm border transition-colors',
            busy
              ? 'border-[var(--border-default)] text-muted opacity-50 cursor-not-allowed'
              : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20',
          )}
        >
          {busy
            ? <><Loader2 className="h-3 w-3 animate-spin" /> Generating…</>
            : <><Sparkles className="h-3 w-3" /> {doc ? 'Regenerate' : 'Generate'}</>}
        </button>
      </div>

      {error && <div className="text-[11px] text-accent-red">{error}</div>}
      {loading && <div className="text-[11px] text-muted">Loading…</div>}

      {!loading && !shown && (
        <div className="text-[11px] text-muted border border-dashed border-[var(--border-default)] rounded-sm p-6 text-center bg-[var(--gray-100)]">
          No architecture document yet. Link some repos under the Repos tab and click Generate.
        </div>
      )}

      {shown && (
        <>
          {/* Doc body */}
          <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 prose prose-sm prose-invert max-w-none
                          prose-headings:text-primary prose-headings:font-medium
                          prose-h1:text-[13px] prose-h2:text-[12px] prose-h3:text-[12px]
                          prose-p:text-secondary prose-p:text-[11px]
                          prose-li:text-secondary prose-li:text-[11px]
                          prose-strong:text-primary
                          prose-code:text-accent-blue prose-code:bg-[var(--gray-200)] prose-code:px-1 prose-code:rounded-sm prose-code:before:content-none prose-code:after:content-none
                          prose-a:text-accent-blue prose-a:no-underline hover:prose-a:underline
                          prose-hr:border-[var(--border-default)]">
            <MarkdownContent content={shown.content} />
          </div>

          {/* Annotations */}
          <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-[11px] font-medium text-primary">Annotations</h3>
              {!editingAnnotation && (
                <button
                  onClick={() => setEditingAnnotation(true)}
                  className="text-[10px] text-accent-blue hover:underline"
                >
                  edit
                </button>
              )}
            </div>
            {editingAnnotation ? (
              <div className="space-y-2">
                <textarea
                  value={annotationDraft}
                  onChange={(e) => setAnnotationDraft(e.target.value)}
                  rows={5}
                  placeholder="Cross-cutting context the planner should know about (e.g. 'auth handled by external Keycloak')."
                  className="w-full text-[11px] font-mono rounded-sm border border-[var(--border-default)] bg-[var(--gray-200)] text-primary placeholder:text-muted px-2 py-1.5 focus:outline-none focus:border-accent-blue resize-y"
                />
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => {
                      setEditingAnnotation(false);
                      setAnnotationDraft(shown.userAnnotations ?? '');
                    }}
                    className="text-[11px] px-2.5 py-1 rounded-sm border border-[var(--border-default)] text-muted hover:text-primary"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={saveAnnotation}
                    disabled={busy}
                    className="text-[11px] px-2.5 py-1 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20 disabled:opacity-50"
                  >
                    Save
                  </button>
                </div>
              </div>
            ) : shown.userAnnotations ? (
              <pre className="text-[11px] whitespace-pre-wrap font-mono text-secondary">
                {shown.userAnnotations}
              </pre>
            ) : (
              <p className="text-[11px] text-muted">No annotations yet.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
