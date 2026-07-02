import {useEffect, useRef, useState, type ChangeEvent} from 'react';
import {useNavigate, useParams} from 'react-router-dom';

import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {ConfirmModal} from '@/signal/components/Modal';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {ProjectTabs} from '@/ide/IdeProjectTabs';
import {
  ProjectBanner as Banner,
  ProjectField as Field,
  ProjectRouteTabs as RTabs,
} from '@/ide/projectUi';
import {
  canEditProjectSettings,
  isProjectDocumentIndexing,
  projectDocumentDisplay,
  projectSettingsFormFromProject,
} from '@/ide/projectSettingsUtils';

const token = () => localStorage.getItem('access_token') ?? '';

type SettingsSub = 'settings' | 'documents' | 'memory';

export function IdeProjectSettings() {
  const {projectId = ''} = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const canEdit = canEditProjectSettings(user);

  const [sub, setSub] = useState<SettingsSub>('settings');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [deleteDocFor, setDeleteDocFor] = useState<{docId: string; label: string} | null>(null);
  const [projectAction, setProjectAction] = useState<'archive' | 'delete' | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [branchNaming, setBranchNaming] = useState('');
  const [githubOwner, setGithubOwner] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');

  const [docs, setDocs] = useState<api.ProjectDocumentRow[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then((p) => {
        setProject(p);
        const form = projectSettingsFormFromProject(p);
        setName(form.name);
        setDescription(form.description);
        setTargetBranch(form.targetBranch);
        setBranchNaming(form.branchNaming);
        setGithubOwner(form.githubOwner);
        setProvider(form.provider);
      })
      .catch(() => {});
    refreshDocs();
    api
      .getProjectMemory(projectId)
      .then(setMemory)
      .catch(() => setMemory(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  function refreshDocs() {
    api
      .listProjectDocuments(projectId)
      .then(setDocs)
      .catch(() => setDocs([]));
  }

  const indexing = isProjectDocumentIndexing(docs);
  const docPollsRef = useRef(0);
  useEffect(() => {
    if (!indexing) {
      docPollsRef.current = 0;
      return;
    }
    const id = window.setInterval(() => {
      if (document.hidden) return;
      docPollsRef.current += 1;
      if (docPollsRef.current > 60) {
        window.clearInterval(id);
        return;
      }
      refreshDocs();
    }, 3000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [indexing]);

  async function saveSettings() {
    setBusy('save');
    setErr('');
    setMsg('');
    try {
      const r = await fetch(`/api/projects/${projectId}/settings`, {
        method: 'PATCH',
        headers: {'Content-Type': 'application/json', Authorization: `Bearer ${token()}`},
        body: JSON.stringify({
          name: name.trim() || undefined,
          description: description.trim() || undefined,
          targetBranch: targetBranch.trim() || undefined,
          branchNamingPattern: branchNaming.trim() || undefined,
          githubOwner: githubOwner.trim() || undefined,
          aiProvider: provider,
        }),
      });
      if (!r.ok) throw new Error(`Save failed (${r.status}).`);
      setMsg('Settings saved.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy('');
    }
  }

  async function onPickFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy('upload');
    setErr('');
    setMsg('');
    try {
      await api.uploadProjectDocument(projectId, file);
      setMsg(`Uploaded ${file.name}. Indexing has started.`);
      refreshDocs();
    } catch (e2: unknown) {
      setErr(e2 instanceof Error ? e2.message : 'Upload failed.');
    } finally {
      setBusy('');
      if (fileInput.current) fileInput.current.value = '';
    }
  }

  async function deleteDoc(docId: string) {
    try {
      await api.deleteProjectDocument(projectId, docId);
      refreshDocs();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    }
  }

  async function compactMemory() {
    setBusy('compact');
    setErr('');
    setMsg('');
    try {
      await api.compactProjectMemory(projectId);
      setMemory(await api.getProjectMemory(projectId));
      setMsg('Memory compacted.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Compact failed.');
    } finally {
      setBusy('');
    }
  }

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Settings</h1>
      {!project ? (
        <IdeLoader label='Loading settings…' />
      ) : (
        <>
          <RTabs
            active={sub}
            onSelect={(k) => setSub(k as SettingsSub)}
            tabs={[
              {key: 'settings', label: 'Settings'},
              {key: 'documents', label: `Reference docs · ${docs?.length ?? '—'}`},
              {key: 'memory', label: 'Project context'},
            ]}
          />
          <Banner err={err} msg={msg} />

          {sub === 'settings' && (
            <>
              <div className='ide-card'>
                <Field label='Project name'>
                  <input
                    className='ide-input'
                    aria-label='Project name'
                    value={name}
                    disabled={!canEdit}
                    onChange={(e) => setName(e.target.value)}
                  />
                </Field>
                <Field label='Description'>
                  <textarea
                    className='ide-textarea'
                    aria-label='Project description'
                    value={description}
                    disabled={!canEdit}
                    rows={3}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                </Field>
                <Field label='Default AI provider'>
                  <select
                    className='ide-select'
                    aria-label='Default AI provider'
                    value={provider}
                    disabled={!canEdit}
                    onChange={(e) => setProvider(e.target.value)}
                  >
                    <option value='ANTHROPIC'>Anthropic</option>
                    <option value='OPENAI'>OpenAI</option>
                    <option value='DEEPSEEK'>DeepSeek</option>
                  </select>
                </Field>
                <Field label='Target branch'>
                  <input
                    className='ide-input'
                    aria-label='Target branch'
                    value={targetBranch}
                    disabled={!canEdit}
                    onChange={(e) => setTargetBranch(e.target.value)}
                  />
                </Field>
                <Field label='Branch naming pattern'>
                  <input
                    className='ide-input'
                    aria-label='Branch naming pattern'
                    value={branchNaming}
                    disabled={!canEdit}
                    onChange={(e) => setBranchNaming(e.target.value)}
                  />
                  <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                    Variables: {'{slug}'}, {'{date}'}, {'{runId}'}
                    <br />
                    Example: execspec/{'{slug}'}-{'{date}'} →
                    execspec/add-health-endpoint-2026-06-11
                  </div>
                </Field>
                <Field label='Default GitHub owner'>
                  <input
                    className='ide-input'
                    aria-label='Default GitHub owner'
                    value={githubOwner}
                    disabled={!canEdit}
                    placeholder='e.g. acme-co'
                    onChange={(e) => setGithubOwner(e.target.value)}
                  />
                </Field>
                {canEdit && (
                  <button
                    className='ide-btn'
                    style={{marginTop: 6}}
                    disabled={busy === 'save'}
                    onClick={saveSettings}
                  >
                    {busy === 'save' ? 'Saving…' : 'Save settings'}
                  </button>
                )}
              </div>

              <div className='ide-h2'>Metadata</div>
              <div className='ide-card'>
                <div className='ide-kv'>
                  <span>Project ID</span>
                  <span className='ide-mono'>{project.id}</span>
                </div>
                <div className='ide-kv'>
                  <span>Org ID</span>
                  <span className='ide-mono'>{project.orgId || '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Status</span>
                  <span>{project.status || '—'}</span>
                </div>
                <div className='ide-kv' style={{borderBottom: 'none'}}>
                  <span>Created</span>
                  <span>
                    {project.createdAt ? new Date(project.createdAt).toLocaleString() : '—'}
                  </span>
                </div>
              </div>

              <div className='ide-h2'>Danger zone</div>
              <div className='ide-card' style={{display: 'flex', gap: 8}}>
                <button className='ide-btn ghost' onClick={() => setProjectAction('archive')}>
                  Archive
                </button>
                <button
                  className='ide-btn ghost'
                  style={{color: 'var(--ide-red)'}}
                  onClick={() => setProjectAction('delete')}
                >
                  Delete
                </button>
              </div>
            </>
          )}

          {sub === 'documents' && (
            <>
              <div className='ide-card' style={{marginBottom: 12, borderColor: 'var(--ide-blue)'}}>
                <div style={{fontSize: 13, lineHeight: 1.55}}>
                  Upload markdown, PDF, or plain-text docs to give future work and project chat
                  extra context.
                </div>
              </div>
              <input
                ref={fileInput}
                type='file'
                accept='.md,.txt,.pdf,.markdown,.rst'
                style={{display: 'none'}}
                onChange={onPickFile}
              />
              <button
                className='ide-btn'
                style={{marginBottom: 12}}
                disabled={busy === 'upload'}
                onClick={() => fileInput.current?.click()}
              >
                {busy === 'upload' ? 'Uploading…' : 'Upload document'}
              </button>
              {docs === null ? (
                <IdeLoader label='Loading documents…' />
              ) : docs.length === 0 ? (
                <div className='ide-empty'>No documents yet. Upload one above to start.</div>
              ) : (
                <div className='ide-card' style={{padding: 0}}>
                  {docs.map((d, i) => {
                    const display = projectDocumentDisplay(d);
                    return (
                      <div
                        key={d.id}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 10,
                          padding: '11px 16px',
                          borderBottom:
                            i < docs.length - 1 ? '1px solid var(--ide-border)' : 'none',
                        }}
                      >
                        <div style={{flex: 1, minWidth: 0}}>
                          <div
                            style={{
                              fontWeight: 500,
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                              whiteSpace: 'nowrap',
                            }}
                          >
                            {display.label}
                          </div>
                          <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                            {display.sizeLabel}
                            {d.mimeType ? ` · ${d.mimeType}` : ''}
                          </div>
                        </div>
                        <IdeBadge
                          kind={
                            display.status === 'READY'
                              ? 'ok'
                              : display.status === 'FAILED'
                                ? 'bad'
                                : 'warn'
                          }
                        >
                          {display.status}
                        </IdeBadge>
                        <button
                          className='ide-btn ghost'
                          style={{padding: '3px 8px', color: 'var(--ide-red)'}}
                          onClick={() => setDeleteDocFor({docId: d.id, label: display.label})}
                        >
                          ×
                        </button>
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}

          {sub === 'memory' && (
            <>
              <div className='ide-card'>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Project context
                </div>
                <div className='ide-kv'>
                  <span>Completed work in context</span>
                  <span>{memory?.stats?.totalRuns ?? '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Snapshot version</span>
                  <span>{memory?.stats?.snapshotVersion ?? '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Recent entries</span>
                  <span>{memory?.stats?.recentEntryCount ?? '—'}</span>
                </div>
                <div className='ide-kv' style={{borderBottom: 'none'}}>
                  <span>Tokens / budget</span>
                  <span>
                    {memory?.stats
                      ? `${memory.stats.currentTokens} / ${memory.stats.tokenBudget}`
                      : '—'}
                  </span>
                </div>
                <button
                  className='ide-btn ghost'
                  style={{marginTop: 12}}
                  disabled={busy === 'compact'}
                  onClick={compactMemory}
                >
                  {busy === 'compact' ? 'Compacting…' : 'Compact context'}
                </button>
              </div>

              <div className='ide-h2'>What ExecuteSpec remembers</div>
              <div className='ide-card'>
                <div style={{fontSize: 13, lineHeight: 1.55, whiteSpace: 'pre-wrap'}}>
                  {memory?.fullContext?.trim() ||
                    'No context snapshot yet. ExecuteSpec builds this after the first few completed work items.'}
                </div>
              </div>
            </>
          )}
        </>
      )}

      <ConfirmModal
        open={!!deleteDocFor}
        title='Delete document'
        body={deleteDocFor ? `Delete "${deleteDocFor.label}" from project reference docs?` : ''}
        confirmLabel='Delete'
        danger
        onConfirm={async () => {
          const d = deleteDocFor;
          if (d) await deleteDoc(d.docId);
        }}
        onClose={() => setDeleteDocFor(null)}
      />
      <ConfirmModal
        open={projectAction === 'archive'}
        title='Archive project'
        body='Archive this project?'
        confirmLabel='Archive'
        onConfirm={() => {
          setProjectAction(null);
          api
            .archiveProject(projectId)
            .then(() => navigate('/projects'))
            .catch((e) => setErr(e instanceof Error ? e.message : 'Archive failed.'));
        }}
        onClose={() => setProjectAction(null)}
      />
      <ConfirmModal
        open={projectAction === 'delete'}
        title='Delete project'
        body='Delete this project? This cannot be undone.'
        confirmLabel='Delete'
        danger
        onConfirm={() => {
          setProjectAction(null);
          api
            .deleteProject(projectId)
            .then(() => navigate('/projects'))
            .catch((e) => setErr(e instanceof Error ? e.message : 'Delete failed.'));
        }}
        onClose={() => setProjectAction(null)}
      />
    </div>
  );
}
