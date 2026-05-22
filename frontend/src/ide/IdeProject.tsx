import {useEffect, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, statusKind} from '@/ide/IdeKit';
import {SpecChat} from '@/signal/components/SpecChat';

type Repo = Record<string, unknown>;
function rid(r: Repo): string {
  return String(r.id ?? '');
}
function rname(r: Repo): string {
  return String(r.githubRepoFullName ?? r.name ?? r.id ?? 'repo');
}
function fmtWhen(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleDateString();
}

// Real IDE project workspace — replaces the Signal ProjectComposePage. Project
// header + spec composer (repo scoping) + recent project runs, all IDE-native.
export default function IdeProject() {
  const {projectId} = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState<api.ProjectDto | null>(null);
  const [repos, setRepos] = useState<Repo[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [runs, setRuns] = useState<api.RunSummary[]>([]);
  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!projectId) return;
    api
      .getProject(projectId)
      .then(setProject)
      .catch(() => {});
    api
      .getProjectRepos(projectId)
      .then((list) => setRepos((list as unknown as Repo[]) ?? []))
      .catch(() => setRepos([]));
    api
      .getRuns({projectId, size: 6})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
    if (project?.aiProvider) setProvider(project.aiProvider.toUpperCase());
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function submit() {
    if (!spec.trim()) {
      setErr('Describe what to build first.');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const body: Parameters<typeof api.createRun>[0] = {
        userRequest: spec.trim(),
        aiProvider: provider,
        executionMode: mode,
        projectId,
      };
      if (selected.length) body.selectedRepoIds = selected;
      const res = await api.createRun(body);
      navigate(`/live/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Submit failed.');
      setBusy(false);
    }
  }

  return (
    <div className='ide-pane'>
      <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4}}>
        <button className='ide-btn ghost' onClick={() => navigate('/projects')} title='Back'>
          ←
        </button>
        <div className='ide-h1' style={{margin: 0}}>
          {project?.name || 'Project'}
        </div>
      </div>
      <div className='ide-sub'>
        {repos.length} repo{repos.length === 1 ? '' : 's'} · {project?.status || 'ACTIVE'}
      </div>

      <div className='ide-h2'>New spec in this project</div>
      <textarea
        className='ide-textarea'
        rows={8}
        placeholder='Describe what you want, in plain English. Reference repos or files to keep the agent inside specific boundaries.'
        value={spec}
        onChange={(e) => setSpec(e.target.value)}
      />
      <div style={{marginTop: 10}}>
        <SpecChat projectId={projectId} draftSpec={spec} onApply={setSpec} />
      </div>

      {repos.length > 0 && (
        <>
          <div className='ide-h2'>Repos the agent may touch</div>
          <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
            {repos.map((r) => {
              const id = rid(r);
              const on = selected.includes(id);
              return (
                <button
                  key={id}
                  className='ide-badge'
                  style={{
                    cursor: 'pointer',
                    borderColor: on ? 'var(--ide-accent)' : undefined,
                    color: on ? 'var(--ide-accent)' : undefined,
                  }}
                  onClick={() => setSelected((s) => (on ? s.filter((x) => x !== id) : [...s, id]))}
                >
                  {on ? '✓ ' : ''}
                  {rname(r)}
                </button>
              );
            })}
          </div>
        </>
      )}

      <div style={{display: 'flex', gap: 12, margin: '14px 0', flexWrap: 'wrap'}}>
        <div style={{flex: 1, minWidth: 160}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Provider
          </div>
          <select
            className='ide-select'
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value='ANTHROPIC'>Anthropic</option>
            <option value='OPENAI'>OpenAI</option>
            <option value='GEMINI'>Gemini</option>
          </select>
        </div>
        <div style={{flex: 1, minWidth: 160}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Mode
          </div>
          <select
            className='ide-select'
            value={mode}
            onChange={(e) => setMode(e.target.value as 'PROTOTYPE' | 'PRODUCTION')}
          >
            <option value='PRODUCTION'>Production (plan gate)</option>
            <option value='PROTOTYPE'>Prototype (auto)</option>
          </select>
        </div>
      </div>
      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>}
      <button className='ide-btn' onClick={submit} disabled={busy}>
        {busy ? 'Submitting…' : '▷ Submit run'}
      </button>

      <div className='ide-h2'>Recent runs in this project</div>
      {runs.length === 0 ? (
        <div className='ide-empty'>No runs yet.</div>
      ) : (
        <div className='ide-grid'>
          {runs.map((r) => (
            <Link key={r.runId} className='ide-run-card' to={`/result/${r.runId}`}>
              <div className='ide-run-title'>
                {(r.runTitle || r.userRequest || r.runId).slice(0, 70)}
              </div>
              <div className='ide-run-meta'>
                <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                <span>{fmtWhen(r.startedAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
