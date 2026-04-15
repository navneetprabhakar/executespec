import { useEffect, useRef, useState } from 'react';
import { Upload, FileText, Trash2, Loader2, CheckCircle2, AlertCircle } from 'lucide-react';
import * as api from '@/lib/api';
import type { ProjectDocumentRow } from '@/lib/api';
import { cn } from '@/lib/utils';

const STATUS_PILL: Record<ProjectDocumentRow['status'], string> = {
  UPLOADED: 'text-muted',
  EXTRACTING: 'text-accent-blue',
  EMBEDDING: 'text-accent-blue',
  READY: 'text-emerald-400',
  FAILED: 'text-accent-red',
};

export default function ProjectDocumentsPanel({ projectId }: { projectId: string }) {
  const [docs, setDocs] = useState<ProjectDocumentRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  const refresh = () => {
    setLoading(true);
    api.listProjectDocuments(projectId)
      .then(setDocs)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(refresh, [projectId]);

  // Poll while any doc is non-terminal (uploaded / extracting / embedding).
  useEffect(() => {
    const pending = docs.some((d) => d.status === 'UPLOADED' || d.status === 'EXTRACTING' || d.status === 'EMBEDDING');
    if (!pending) return;
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [docs]); // eslint-disable-line react-hooks/exhaustive-deps

  const onPick = async (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await api.uploadProjectDocument(projectId, file);
      refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Upload failed');
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const onDelete = async (id: string) => {
    if (!confirm('Delete this document? Embeddings will be removed.')) return;
    await api.deleteProjectDocument(projectId, id);
    refresh();
  };

  return (
    <div className="space-y-3 max-w-3xl">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-[13px] font-medium text-primary">Project documents</h2>
          <p className="text-[11px] text-muted mt-0.5">
            PDFs / docs uploaded here are chunked + embedded; the planner and chat retrieve them automatically.
          </p>
        </div>
        <label
          className={cn(
            'inline-flex items-center gap-1 text-[11px] font-medium px-2.5 py-1 rounded-sm border cursor-pointer',
            busy
              ? 'border-[var(--border-default)] text-muted opacity-50 cursor-not-allowed'
              : 'border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20',
          )}
        >
          {busy ? <><Loader2 className="h-3 w-3 animate-spin" /> Uploading…</> : <><Upload className="h-3 w-3" /> Upload</>}
          <input
            ref={fileInput}
            type="file"
            className="hidden"
            disabled={busy}
            onChange={(e) => onPick(e.target.files?.[0])}
            accept=".pdf,.txt,.md,.docx,.html,.csv"
          />
        </label>
      </div>

      {error && <div className="text-[11px] text-accent-red">{error}</div>}
      {loading && <div className="text-[11px] text-muted">Loading…</div>}

      {!loading && docs.length === 0 && (
        <div className="text-[11px] text-muted border border-dashed border-[var(--border-default)] rounded-sm p-6 text-center bg-[var(--gray-100)]">
          No documents yet. Upload a PDF, markdown file or DOCX to enrich the planner with project context.
        </div>
      )}

      <ul className="space-y-1">
        {docs.map((d) => (
          <li
            key={d.id}
            className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 flex items-center gap-3"
          >
            <FileText className="h-3.5 w-3.5 text-muted" />
            <div className="flex-1 min-w-0">
              <div className="text-[12px] text-primary truncate">{d.filename ?? d.pathRelative}</div>
              <div className="text-[10px] text-muted font-mono mt-0.5">
                {(d.sizeBytes ?? 0) > 0 ? `${Math.ceil((d.sizeBytes ?? 0) / 1024)} kB · ` : ''}
                {new Date(d.createdAt).toLocaleString()}
              </div>
              {d.failureReason && (
                <div className="text-[10px] text-accent-red mt-0.5">{d.failureReason}</div>
              )}
            </div>
            <span className={cn('text-[10px] uppercase tracking-wide font-mono inline-flex items-center gap-1', STATUS_PILL[d.status])}>
              {d.status === 'READY' && <CheckCircle2 className="h-3 w-3" />}
              {d.status === 'FAILED' && <AlertCircle className="h-3 w-3" />}
              {(d.status === 'EXTRACTING' || d.status === 'EMBEDDING' || d.status === 'UPLOADED') && (
                <Loader2 className="h-3 w-3 animate-spin" />
              )}
              {d.status}
            </span>
            <button
              onClick={() => onDelete(d.id)}
              className="text-muted hover:text-accent-red"
              title="Delete"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
