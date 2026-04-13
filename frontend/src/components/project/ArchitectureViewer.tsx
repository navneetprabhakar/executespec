import { useEffect, useState } from 'react';
import * as api from '@/lib/api';
import type { StalenessReport } from '@/lib/api';
import type { ArchitectureDoc } from '@/types/project';
import { MarkdownContent } from '@/components/shared/MarkdownContent';

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
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-2 flex-wrap">
          <h2 className="text-lg font-medium">Architecture document</h2>
          {staleness?.stale && (
            <span
              className="text-[10px] uppercase tracking-wide bg-amber-100 text-amber-700 px-2 py-0.5 rounded"
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
              className="text-xs border rounded px-2 py-1"
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
          className="text-sm px-3 py-1.5 bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
        >
          {busy ? 'Generating…' : doc ? 'Regenerate' : 'Generate'}
        </button>
      </div>

      {error && <div className="text-sm text-red-600">{error}</div>}
      {loading && <div className="text-sm text-gray-500">Loading…</div>}

      {!loading && !shown && (
        <div className="text-sm text-gray-500 border-2 border-dashed rounded-lg p-6 text-center">
          No architecture document yet. Link some repos under the Repos tab and click Generate.
        </div>
      )}

      {shown && (
        <>
          <div className="border rounded-lg p-4 bg-white shadow-sm prose prose-sm max-w-none">
            <MarkdownContent content={shown.content} />
          </div>

          <div className="border rounded-lg p-4 bg-white shadow-sm">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-medium">Annotations</h3>
              {!editingAnnotation && (
                <button
                  onClick={() => setEditingAnnotation(true)}
                  className="text-xs text-blue-600 hover:underline"
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
                  className="w-full border rounded p-2 text-sm font-mono"
                />
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => {
                      setEditingAnnotation(false);
                      setAnnotationDraft(shown.userAnnotations ?? '');
                    }}
                    className="text-xs px-3 py-1 border rounded"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={saveAnnotation}
                    disabled={busy}
                    className="text-xs px-3 py-1 bg-blue-600 text-white rounded"
                  >
                    Save
                  </button>
                </div>
              </div>
            ) : shown.userAnnotations ? (
              <pre className="text-xs whitespace-pre-wrap font-mono text-gray-700">
                {shown.userAnnotations}
              </pre>
            ) : (
              <p className="text-xs text-gray-400">No annotations yet.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
