import {useEffect, useRef, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, KvList, TabsStrip, Chip, StatusPill} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

type Sub = 'settings' | 'documents' | 'memory';

/**
 * Project settings + documents (RAG) + memory panel — three of the
 * v3 "More drawer" surfaces collapsed into one Signal page. Lives at
 * /projects/:projectId/settings.
 */
export default function ProjectSettingsPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const canEdit = isSuperAdmin || user?.orgRole === 'ORG_ADMIN';

  const [sub, setSub] = useState<Sub>('settings');

  // ── Settings ─────────────────────────
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [branchNaming, setBranchNaming] = useState('');
  const [githubOwner, setGithubOwner] = useState('');
  const [aiProvider, setAiProvider] = useState('ANTHROPIC');
  const [savingSettings, setSavingSettings] = useState(false);

  useEffect(() => {
    if (!project) return;
    setName(project.name || '');
    setDescription(project.description || '');
    setTargetBranch(project.targetBranch || 'main');
    setBranchNaming(project.branchNamingPattern || 'execspec/{slug}-{date}');
    setGithubOwner(project.githubOwner || '');
    setAiProvider(project.aiProvider || 'ANTHROPIC');
  }, [project]);

  async function saveSettings() {
    setSavingSettings(true);
    setErr('');
    setMsg('');
    try {
      // The typed updateProjectSettings only accepts aiProvider + githubOwner
      // today; the backend's PATCH /projects/{id}/settings endpoint accepts
      // more fields, so we hit it directly with the extended payload.
      const r = await fetch(`/api/projects/${id}/settings`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({
          name: name.trim() || undefined,
          description: description.trim() || undefined,
          targetBranch: targetBranch.trim() || undefined,
          branchNamingPattern: branchNaming.trim() || undefined,
          githubOwner: githubOwner.trim() || undefined,
          aiProvider,
        }),
      });
      if (!r.ok) throw new Error(`Save failed (${r.status}).`);
      setMsg('Settings saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setSavingSettings(false);
    }
  }

  async function onArchive() {
    if (!window.confirm('Archive this project? It will be hidden but can be restored.')) return;
    try {
      await api.archiveProject(id);
      setMsg('Project archived. It now lives under "Archived" in /projects.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Archive failed.');
    }
  }

  async function onDelete() {
    if (!window.confirm("Delete this project permanently? This can't be undone via UI.")) return;
    try {
      await api.deleteProject(id);
      window.location.href = '/projects';
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    }
  }

  // ── Documents (RAG) ──────────────────
  const [docs, setDocs] = useState<api.ProjectDocumentRow[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);

  async function refreshDocs() {
    try {
      const list = await api.listProjectDocuments(id);
      setDocs(list);
    } catch {
      setDocs([]);
    }
  }
  useEffect(() => {
    if (id) void refreshDocs();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  async function onPickFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setErr('');
    setMsg('');
    try {
      await api.uploadProjectDocument(id, file);
      setMsg(`Uploaded ${file.name}. Indexing has started.`);
      await refreshDocs();
    } catch (e2: unknown) {
      setErr(e2 instanceof Error ? e2.message : 'Upload failed.');
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  async function deleteDoc(docId: string) {
    if (!window.confirm('Delete this document?')) return;
    try {
      await api.deleteProjectDocument(id, docId);
      await refreshDocs();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    }
  }

  // ── Memory panel ─────────────────────
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);
  const [compacting, setCompacting] = useState(false);
  useEffect(() => {
    if (id) api.getProjectMemory(id).then(setMemory).catch(() => setMemory(null));
  }, [id]);

  async function compactMemory() {
    setCompacting(true);
    setErr('');
    setMsg('');
    try {
      await api.compactProjectMemory(id);
      const next = await api.getProjectMemory(id);
      setMemory(next);
      setMsg('Memory compacted.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compact failed.');
    } finally {
      setCompacting(false);
    }
  }

  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  const projectName = project?.name || 'project';

  return (
    <>
      <TopBar
        title='Settings'
        sub={`${projectName} · org-scoped`}
        back
        backTo={`/projects/${id}`}
      />
      <Feed>
        <ProjectTabs active='settings' />
        <TabsStrip
          items={[
            {label: 'Settings', active: sub === 'settings', onClick: () => setSub('settings')},
            {label: `Documents · ${docs?.length ?? '—'}`, active: sub === 'documents', onClick: () => setSub('documents')},
            {label: 'Memory', active: sub === 'memory', onClick: () => setSub('memory')},
          ]}
        />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{msg}</div>}

        {sub === 'settings' && (
          <>
            <Card>
              <SectionField label='Project name'>
                <input value={name} disabled={!canEdit} onChange={(e) => setName(e.target.value)} style={inputStyle} />
              </SectionField>
              <SectionField label='Description'>
                <textarea value={description} disabled={!canEdit} onChange={(e) => setDescription(e.target.value)} rows={3} style={{...inputStyle, resize: 'vertical', minHeight: 60, fontFamily: 'inherit'}} />
              </SectionField>
              <SectionField label='Default AI provider'>
                <div style={{display: 'flex', gap: 6}}>
                  {(['ANTHROPIC', 'OPENAI', 'GEMINI'] as const).map((p) => (
                    <Chip key={p} kind={aiProvider === p ? 'active' : 'default'} onClick={canEdit ? () => setAiProvider(p) : undefined}>
                      {p[0] + p.slice(1).toLowerCase()}
                    </Chip>
                  ))}
                </div>
              </SectionField>
              <SectionField label='Target branch'>
                <input value={targetBranch} disabled={!canEdit} onChange={(e) => setTargetBranch(e.target.value)} style={inputStyle} />
              </SectionField>
              <SectionField label='Branch naming pattern'>
                <input value={branchNaming} disabled={!canEdit} onChange={(e) => setBranchNaming(e.target.value)} style={inputStyle} />
                <div style={{fontSize: 11, color: 'var(--signal-text-mute)', marginTop: 4}}>
                  Variables: <code>{'{slug}'}</code>, <code>{'{date}'}</code>, <code>{'{runId}'}</code>
                </div>
              </SectionField>
              <SectionField label='Default GitHub owner'>
                <input value={githubOwner} disabled={!canEdit} onChange={(e) => setGithubOwner(e.target.value)} placeholder='e.g. acme-co' style={inputStyle} />
              </SectionField>
              {canEdit && (
                <Button kind='primary' block style={{marginTop: 6}} disabled={savingSettings} onClick={saveSettings}>
                  {savingSettings ? 'Saving…' : 'Save settings'}
                </Button>
              )}
            </Card>

            <SectionHeader>Metadata</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Project ID', v: <span className='mono' style={{fontSize: 11.5}}>{id}</span>},
                  {k: 'Org ID', v: <span className='mono' style={{fontSize: 11.5}}>{project?.orgId || '—'}</span>},
                  {k: 'Status', v: project?.status || '—'},
                  {k: 'Created', v: project?.createdAt ? new Date(project.createdAt).toLocaleString() : '—'},
                  {k: 'Archived', v: project?.archivedAt ? new Date(project.archivedAt).toLocaleString() : '—'},
                ]}
              />
            </Card>

            {canEdit && (
              <>
                <SectionHeader>Danger zone</SectionHeader>
                <Card>
                  <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
                    <Button kind='secondary' sm onClick={onArchive}>Archive project</Button>
                    <Button kind='ghost' sm style={{color: 'var(--signal-coral-text)'}} onClick={onDelete}>Delete project</Button>
                  </div>
                  <p style={{fontSize: 12, color: 'var(--signal-text-mute)', marginTop: 10, lineHeight: 1.5}}>
                    Archived projects can be restored from <a href='/projects' style={{color: 'var(--signal-coral)'}}>/projects → Archived</a>. Deletion is permanent.
                  </p>
                </Card>
              </>
            )}
          </>
        )}

        {sub === 'documents' && (
          <>
            <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent', marginBottom: 12}}>
              <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.55}}>
                Upload markdown, PDF, or plain-text docs to give the planner and chat extra context. Documents are chunked, embedded, and retrieved at run time.
              </div>
            </Card>

            <input
              ref={fileInput}
              type='file'
              accept='.md,.txt,.pdf,.markdown,.rst'
              style={{display: 'none'}}
              onChange={onPickFile}
            />
            <Button kind='primary' block style={{marginBottom: 12}} disabled={uploading} onClick={() => fileInput.current?.click()}>
              {uploading ? 'Uploading…' : 'Upload document'}
            </Button>

            {docs === null ? (
              <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading documents…</p></Card>
            ) : docs.length === 0 ? (
              <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No documents yet. Upload one above to start.</p></Card>
            ) : (
              docs.map((d) => {
                const dAny = d as unknown as {id?: string; filename?: string; name?: string; status?: string; uploadedAt?: string; sizeBytes?: number; mimeType?: string};
                const status = (dAny.status || 'READY').toUpperCase();
                const sizeKb = dAny.sizeBytes ? `${(dAny.sizeBytes / 1024).toFixed(1)} KB` : '—';
                return (
                  <Row
                    key={dAny.id}
                    dot={status === 'READY' ? 'mint' : status === 'FAILED' ? 'coral' : 'signal'}
                    title={dAny.filename || dAny.name || `Document ${dAny.id?.slice(0, 6)}`}
                    meta={
                      <>
                        {dAny.uploadedAt && <span>{new Date(dAny.uploadedAt).toLocaleDateString()}</span>}
                        <span>·</span>
                        <span>{sizeKb}</span>
                        {dAny.mimeType && (<><span>·</span><span className='mono'>{dAny.mimeType}</span></>)}
                      </>
                    }
                    trailing={
                      <div style={{display: 'flex', gap: 4}}>
                        <StatusPill kind={status === 'READY' ? 'shipped' : status === 'FAILED' ? 'failed' : 'needs'}>{status}</StatusPill>
                        <Button kind='ghost' sm style={{color: 'var(--signal-coral-text)'}} onClick={() => dAny.id && deleteDoc(dAny.id)}>×</Button>
                      </div>
                    }
                  />
                );
              })
            )}
          </>
        )}

        {sub === 'memory' && (
          <>
            <Card>
              <SectionHeader>Project memory</SectionHeader>
              <KvList
                pairs={[
                  {k: 'Total runs in ledger', v: String((memory as unknown as {totalRuns?: number})?.totalRuns ?? '—')},
                  {k: 'Last compacted', v: (memory as unknown as {lastCompactedAt?: string})?.lastCompactedAt ? new Date((memory as unknown as {lastCompactedAt: string}).lastCompactedAt).toLocaleString() : '—'},
                  {k: 'Snapshot version', v: String((memory as unknown as {version?: number})?.version ?? '—')},
                  {k: 'Token estimate', v: String((memory as unknown as {tokenEstimate?: number})?.tokenEstimate ?? '—')},
                ]}
              />
              <Button kind='secondary' block style={{marginTop: 12}} disabled={compacting} onClick={compactMemory}>
                {compacting ? 'Compacting…' : 'Compact memory'}
              </Button>
            </Card>

            <SectionHeader>What the planner remembers</SectionHeader>
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', lineHeight: 1.55, margin: 0, whiteSpace: 'pre-wrap'}}>
                {(memory as unknown as {summary?: string; digest?: string})?.summary ||
                  (memory as unknown as {digest?: string})?.digest ||
                  'No memory snapshot yet. The planner builds context after the first ~5 completed runs.'}
              </p>
            </Card>
          </>
        )}
      </Feed>
    </>
  );
}

function SectionField({label, children}: {label: string; children: React.ReactNode}) {
  return (
    <div style={{marginBottom: 14}}>
      <div style={{fontSize: 11, fontWeight: 700, letterSpacing: '0.14em', textTransform: 'uppercase', color: 'var(--signal-text-mute)', marginBottom: 6}}>{label}</div>
      {children}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--signal-card-elev)',
  border: '1px solid var(--signal-hairline)',
  borderRadius: 8,
  padding: '10px 12px',
  fontSize: 13,
  color: 'var(--signal-ink)',
  outline: 'none',
  boxSizing: 'border-box',
};
