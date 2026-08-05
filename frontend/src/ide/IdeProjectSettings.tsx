import {useEffect, useRef, useState, type ChangeEvent} from 'react';
import {useNavigate, useParams} from 'react-router-dom';

import * as api from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {ConfirmModal} from '@/signal/components/Modal';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import {LoadError} from '@/ide/LoadError';
import {toLoadFailure, type LoadFailure} from '@/lib/loadState';
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
import {
  PRODUCT_PROVIDER_OPTIONS,
  providerIsProjectSelectable,
  providerOptionLabel,
  providerReadinessLabel,
} from '@/ide/providerReadiness';
import {useProviderReadiness} from '@/ide/useProviderReadiness';

type SettingsSub = 'settings' | 'documents' | 'memory' | 'exports';

export function IdeProjectSettings() {
  const {projectId = ''} = useParams();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const canEdit = canEditProjectSettings(user);
  const providerReadiness = useProviderReadiness();

  const [sub, setSub] = useState<SettingsSub>('settings');
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [busy, setBusy] = useState('');
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  const [deleteDocFor, setDeleteDocFor] = useState<{docId: string; label: string} | null>(null);
  const [projectAction, setProjectAction] = useState<'archive' | 'unarchive' | 'delete' | null>(
    null,
  );

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetBranch, setTargetBranch] = useState('');
  const [branchNaming, setBranchNaming] = useState('');
  const [githubOwner, setGithubOwner] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');

  const [docs, setDocs] = useState<api.ProjectDocumentRow[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [memory, setMemory] = useState<api.ProjectMemoryResponse | null>(null);
  const [exports, setExports] = useState<api.ProjectExportJob[] | null>(null);
  // D4 — documents / exports / memory each render an affirmative "you have
  // none" state. These loads are re-invoked by mutations via the refresh*
  // helpers below, so they keep manual state and use the shared classifier.
  const [docsFailure, setDocsFailure] = useState<LoadFailure | null>(null);
  const [exportsFailure, setExportsFailure] = useState<LoadFailure | null>(null);
  const [memoryFailure, setMemoryFailure] = useState<LoadFailure | null>(null);
  const [exportAudit, setExportAudit] = useState<{
    jobId: string;
    rows: api.ProjectExportAuditEvent[];
  } | null>(null);
  const exportRequestKey = useRef<string | null>(null);

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
      .then((m) => {
        setMemory(m);
        setMemoryFailure(null);
      })
      .catch((e: unknown) => setMemoryFailure(toLoadFailure(e)));
    if (canEdit) refreshExports();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  function refreshDocs() {
    api
      .listProjectDocuments(projectId)
      .then((d) => {
        setDocs(d);
        setDocsFailure(null);
      })
      .catch((e: unknown) => setDocsFailure(toLoadFailure(e)));
  }

  function refreshExports() {
    api
      .listProjectExports(projectId)
      .then((x) => {
        setExports(x);
        setExportsFailure(null);
      })
      .catch((e: unknown) => setExportsFailure(toLoadFailure(e)));
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

  const exportRunning =
    exports?.some((job) =>
      ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'EXPIRING'].includes(job.status),
    ) ?? false;
  useEffect(() => {
    if (!exportRunning) return;
    const id = window.setInterval(() => {
      if (!document.hidden) refreshExports();
    }, 2500);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [exportRunning, projectId]);

  async function saveSettings() {
    setBusy('save');
    setErr('');
    setMsg('');
    try {
      await api.updateProjectSettings(projectId, {
        name: name.trim() || undefined,
        description: description.trim() || undefined,
        targetBranch: targetBranch.trim() || undefined,
        branchNamingPattern: branchNaming.trim() || undefined,
        githubOwner: githubOwner.trim() || undefined,
        aiProvider: provider,
      });
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

  async function createExport() {
    setBusy('create-export');
    setErr('');
    setMsg('');
    exportRequestKey.current ??= crypto.randomUUID();
    try {
      const job = await api.createProjectExport(projectId, exportRequestKey.current);
      exportRequestKey.current = null;
      await refreshExports();
      setMsg(
        job.status === 'SUCCEEDED'
          ? 'The existing export is ready to download.'
          : 'Export queued. You can leave this page and return later.',
      );
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not queue export.');
    } finally {
      setBusy('');
    }
  }

  async function cancelExport(jobId: string) {
    setBusy(`cancel-export-${jobId}`);
    setErr('');
    try {
      await api.cancelProjectExport(projectId, jobId);
      await refreshExports();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not cancel export.');
    } finally {
      setBusy('');
    }
  }

  async function downloadExport(job: api.ProjectExportJob) {
    setBusy(`download-export-${job.id}`);
    setErr('');
    try {
      await api.downloadProjectExport(projectId, job);
      await refreshExports();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not download export.');
    } finally {
      setBusy('');
    }
  }

  async function toggleExportAudit(jobId: string) {
    if (exportAudit?.jobId === jobId) {
      setExportAudit(null);
      return;
    }
    setBusy(`audit-export-${jobId}`);
    try {
      setExportAudit({jobId, rows: await api.getProjectExportAudit(projectId, jobId)});
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not load export history.');
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
              ...(canEdit ? [{key: 'exports', label: 'Exports'}] : []),
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
                    {PRODUCT_PROVIDER_OPTIONS.map(({id, label}) => (
                      <option
                        key={id}
                        value={id}
                        disabled={
                          providerReadiness.supported &&
                          !providerIsProjectSelectable(providerReadiness.byProvider.get(id))
                        }
                      >
                        {providerReadiness.supported
                          ? providerOptionLabel(label, providerReadiness.byProvider.get(id))
                          : label}
                      </option>
                    ))}
                  </select>
                  <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                    {!providerReadiness.supported
                      ? 'The server will enforce provider policy when work starts.'
                      : providerReadiness.error
                        ? 'Provider readiness is temporarily unavailable.'
                        : providerReadiness.loading
                          ? 'Checking provider readiness…'
                          : providerReadinessLabel(
                              providerReadiness.byProvider.get(provider.toUpperCase()),
                            )}
                  </div>
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
                  <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
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
              {/* RR-1312 — archive and soft-delete are both reversible. Surface the
                  matching recovery action and describe the lifecycle honestly. */}
              <div className='ide-card' style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
                {projectIsArchived(project) ? (
                  <button className='ide-btn ghost' onClick={() => setProjectAction('unarchive')}>
                    Unarchive
                  </button>
                ) : (
                  <button className='ide-btn ghost' onClick={() => setProjectAction('archive')}>
                    Archive
                  </button>
                )}
                <button
                  className='ide-btn ghost'
                  style={{color: 'var(--ide-red)'}}
                  onClick={() => setProjectAction('delete')}
                >
                  Delete
                </button>
              </div>
              {projectIsArchived(project) && (
                <div className='ide-sub' style={{marginTop: 6}}>
                  This project is archived. It is hidden from active listings but retained —
                  unarchive to restore it.
                </div>
              )}
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
              {docsFailure ? (
                <LoadError failure={docsFailure} what='documents' onRetry={refreshDocs} />
              ) : docs === null ? (
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
                          <div style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                            {display.sizeLabel}
                            {d.mimeType ? ` · ${d.mimeType}` : ''}
                          </div>
                          {display.readiness && (
                            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 2}}>
                              {display.readiness.replace(/_/g, ' ').toLowerCase()}
                            </div>
                          )}
                        </div>
                        <IdeBadge
                          kind={
                            display.status === 'SEMANTIC READY'
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
                          aria-label={`Delete ${display.label}`}
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
                {memoryFailure ? (
                  <LoadError failure={memoryFailure} what='project memory' />
                ) : (
                  <div style={{fontSize: 13, lineHeight: 1.55, whiteSpace: 'pre-wrap'}}>
                    {memory?.fullContext?.trim() ||
                      'No context snapshot yet. ExecuteSpec builds this after the first few completed work items.'}
                  </div>
                )}
              </div>
            </>
          )}

          {sub === 'exports' && canEdit && (
            <>
              <div className='ide-card' style={{marginBottom: 12}}>
                <div style={{fontSize: 13, lineHeight: 1.55, marginBottom: 10}}>
                  Create a redacted project metadata and governance archive. Exports run in the
                  background, survive an application restart, and expire after 24 hours. Source code
                  and uploaded document bytes are deliberately excluded.
                </div>
                <button
                  className='ide-btn'
                  disabled={busy === 'create-export' || exportRunning}
                  onClick={createExport}
                >
                  {busy === 'create-export'
                    ? 'Queueing…'
                    : exportRunning
                      ? 'Export in progress'
                      : 'Create export'}
                </button>
              </div>

              {exportsFailure ? (
                <LoadError failure={exportsFailure} what='exports' onRetry={refreshExports} />
              ) : exports === null ? (
                <IdeLoader label='Loading exports…' />
              ) : exports.length === 0 ? (
                <div className='ide-empty'>No project exports yet.</div>
              ) : (
                <div className='ide-card' style={{padding: 0}}>
                  {exports.map((job, index) => (
                    <div
                      key={job.id}
                      style={{
                        padding: '12px 16px',
                        borderBottom:
                          index < exports.length - 1 ? '1px solid var(--ide-border)' : 'none',
                      }}
                    >
                      <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
                        <div style={{flex: 1, minWidth: 0}}>
                          <div className='ide-mono' style={{fontSize: 12}}>
                            {job.id.slice(0, 8)}
                          </div>
                          <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 3}}>
                            {new Date(job.createdAt).toLocaleString()}
                            {job.sizeBytes != null ? ` · ${formatBytes(job.sizeBytes)}` : ''}
                            {job.expiresAt
                              ? ` · expires ${new Date(job.expiresAt).toLocaleString()}`
                              : ''}
                          </div>
                        </div>
                        <IdeBadge
                          kind={
                            job.status === 'SUCCEEDED'
                              ? 'ok'
                              : ['FAILED', 'EXPIRED'].includes(job.status)
                                ? 'bad'
                                : 'warn'
                          }
                        >
                          {job.status.replace(/_/g, ' ')}
                        </IdeBadge>
                      </div>
                      {job.errorCode && (
                        <div style={{fontSize: 12, color: 'var(--ide-red)', marginTop: 6}}>
                          {job.errorCode.replace(/_/g, ' ')}
                        </div>
                      )}
                      {job.sha256 && (
                        <div
                          className='ide-mono'
                          style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 5}}
                        >
                          SHA-256 {job.sha256}
                        </div>
                      )}
                      <div style={{display: 'flex', gap: 6, marginTop: 9}}>
                        {job.downloadable && (
                          <button
                            className='ide-btn'
                            disabled={busy === `download-export-${job.id}`}
                            onClick={() => downloadExport(job)}
                          >
                            {busy === `download-export-${job.id}` ? 'Downloading…' : 'Download'}
                          </button>
                        )}
                        {job.cancellable && job.status !== 'CANCEL_REQUESTED' && (
                          <button
                            className='ide-btn ghost'
                            disabled={busy === `cancel-export-${job.id}`}
                            onClick={() => cancelExport(job.id)}
                          >
                            Cancel
                          </button>
                        )}
                        <button
                          className='ide-btn ghost'
                          disabled={busy === `audit-export-${job.id}`}
                          onClick={() => toggleExportAudit(job.id)}
                        >
                          {exportAudit?.jobId === job.id ? 'Hide history' : 'History'}
                        </button>
                      </div>
                      {exportAudit?.jobId === job.id && (
                        <div style={{marginTop: 10, fontSize: 12}}>
                          {exportAudit.rows.map((event) => (
                            <div key={event.id} className='ide-kv'>
                              <span>{event.eventType.replace(/_/g, ' ')}</span>
                              <span>
                                {event.outcome} · {new Date(event.occurredAt).toLocaleString()}
                              </span>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
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
        open={projectAction === 'unarchive'}
        title='Unarchive project'
        body='Unarchive this project and return it to your active listings?'
        confirmLabel='Unarchive'
        onConfirm={() => {
          setProjectAction(null);
          // RR-1312 — restoration is an implemented, reachable action, not a dead end.
          api
            .unarchiveProject(projectId)
            .then((p) => setProject(p))
            .catch((e) => setErr(e instanceof Error ? e.message : 'Unarchive failed.'));
        }}
        onClose={() => setProjectAction(null)}
      />
      <ConfirmModal
        open={projectAction === 'delete'}
        title='Delete project'
        // RR-1312 — deletion is a soft-delete with a 30-day undo window, not permanent.
        body='Delete this project? It is soft-deleted and can be restored within a 30-day undo window; after that it is permanently removed.'
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

// RR-1312 — a project is archived when it carries an archive timestamp (or the
// backend labels its status ARCHIVED).
function projectIsArchived(project: api.ProjectDto): boolean {
  return !!project.archivedAt || (project.status || '').toUpperCase() === 'ARCHIVED';
}

function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
