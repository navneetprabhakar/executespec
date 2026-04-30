import { useEffect, useState } from 'react';
import {
  createProject,
  createRepo,
  getProjects,
  getProjectRepos,
  linkProjectRepo,
  listGitHubRepos,
  type GitHubRemoteRepo,
  type ProjectDto,
} from '@/lib/api';
import type { ProjectRepoLink } from '@/types/project';

/**
 * R-335 — shared project + repo picker for Library + Migrations
 * detail pages.
 *
 * <p>Closes the UX gap from the W4/W5 ship where the user had to
 * paste raw UUIDs into free-form text inputs.  Now:
 * <ul>
 *   <li>Project: dropdown sourced from {@code GET /api/projects}
 *       with a "+ Create new project" inline form at the bottom.</li>
 *   <li>Repos: multi-select checkbox list sourced from
 *       {@code GET /api/projects/{id}/repos}, scoped to the chosen
 *       project, with an "+ Add repo to this project" inline form
 *       (creates the repo via {@code POST /api/repos} then links it
 *       via {@code POST /api/projects/{id}/repos/{repoId}}).</li>
 * </ul>
 *
 * <p>Stays project-scoped on purpose: the catalog instantiate flow
 * always lands as a run inside a project, so repos that aren't
 * linked to that project would be invalid choices.
 */
export interface ProjectRepoPickerProps {
  projectId: string;
  repoIds: string[];
  onProjectChange: (id: string) => void;
  onRepoIdsChange: (ids: string[]) => void;
}

export default function ProjectRepoPicker({
  projectId,
  repoIds,
  onProjectChange,
  onRepoIdsChange,
}: ProjectRepoPickerProps) {
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [repos, setRepos] = useState<ProjectRepoLink[] | null>(null);
  const [projectsErr, setProjectsErr] = useState<string | null>(null);
  const [reposErr, setReposErr] = useState<string | null>(null);
  const [showNewProject, setShowNewProject] = useState(false);
  const [showNewRepo, setShowNewRepo] = useState(false);

  // R-343 (P1 from UX persona review) — stale-while-revalidate cache
  // for the project list + per-project repo lists.  Marcus ran into
  // a ~300ms refetch on every navigation to a library/migrations
  // detail page.  Most projects + repos don't change between
  // sessions, so we hydrate from localStorage immediately (zero
  // perceived latency) and refetch in the background to refresh the
  // cache.  24h TTL on cached entries; cache key versioned so we
  // can invalidate atomically by bumping it.
  const CACHE_TTL_MS = 24 * 60 * 60 * 1000;
  const CACHE_VERSION = 1;
  const projectsCacheKey = `v3-pic-projects-v${CACHE_VERSION}`;
  const reposCacheKey = (pid: string) => `v3-pic-repos-${pid}-v${CACHE_VERSION}`;
  function readCache<T>(key: string): T | null {
    try {
      const raw = localStorage.getItem(key);
      if (!raw) return null;
      const parsed = JSON.parse(raw) as { ts: number; data: T };
      if (Date.now() - parsed.ts > CACHE_TTL_MS) return null;
      return parsed.data;
    } catch { return null; }
  }
  function writeCache<T>(key: string, data: T) {
    try {
      localStorage.setItem(key, JSON.stringify({ ts: Date.now(), data }));
    } catch { /* quota exceeded — silent */ }
  }

  // Load projects on mount — cache-first, then revalidate.
  useEffect(() => {
    // 1) Hydrate from cache for instant render.
    const cached = readCache<ProjectDto[]>(projectsCacheKey);
    if (cached && cached.length > 0) {
      const active = cached.filter((row) => row.status === 'ACTIVE');
      setProjects(active);
      if (!projectId && active.length > 0) onProjectChange(active[0].id);
    }
    // 2) Revalidate in the background — replaces the cached list when
    //    the network response lands.  Failure leaves the cached set
    //    intact (graceful degradation when offline).
    let cancelled = false;
    getProjects()
      .then((p) => {
        if (cancelled) return;
        const active = p.filter((row) => row.status === 'ACTIVE');
        setProjects(active);
        writeCache(projectsCacheKey, p);
        if (!projectId && active.length > 0 && !cached) {
          onProjectChange(active[0].id);
        }
      })
      .catch((e: Error) => {
        if (cancelled) return;
        if (!cached) setProjectsErr(e?.message ?? 'Failed to load projects');
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Reload repos when the chosen project changes — same SWR pattern.
  useEffect(() => {
    if (!projectId) {
      setRepos(null);
      return;
    }
    const cacheKey = reposCacheKey(projectId);
    const cached = readCache<ProjectRepoLink[]>(cacheKey);
    if (cached) setRepos(cached);
    let cancelled = false;
    setReposErr(null);
    getProjectRepos(projectId)
      .then((r) => {
        if (cancelled) return;
        setRepos(r);
        writeCache(cacheKey, r);
      })
      .catch((e: Error) => {
        if (cancelled) return;
        if (!cached) {
          setReposErr(e?.message ?? 'Failed to load repos');
          setRepos([]);
        }
      });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  function toggleRepo(repoId: string) {
    if (repoIds.includes(repoId)) {
      onRepoIdsChange(repoIds.filter((id) => id !== repoId));
    } else {
      onRepoIdsChange([...repoIds, repoId]);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {/* Project picker */}
      <FormField label="Project">
        {projects === null && !projectsErr ? (
          <span style={hintStyle}>Loading…</span>
        ) : projectsErr ? (
          <span style={errorTextStyle}>{projectsErr}</span>
        ) : projects && projects.length === 0 ? (
          <NewProjectInline
            onCreated={(p) => {
              setProjects([p]);
              onProjectChange(p.id);
            }}
            label="Create your first project"
          />
        ) : (
          <>
            <select
              value={projectId}
              onChange={(e) => {
                const v = e.target.value;
                if (v === '__new__') {
                  setShowNewProject(true);
                  return;
                }
                onProjectChange(v);
                onRepoIdsChange([]); // reset repo selection on project change
              }}
              style={inputStyle}
            >
              <option value="">— Pick a project —</option>
              {projects?.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
              <option value="__new__">+ Create new project…</option>
            </select>
            {showNewProject && (
              <NewProjectInline
                onCreated={(p) => {
                  setProjects((prev) => (prev ? [...prev, p] : [p]));
                  onProjectChange(p.id);
                  onRepoIdsChange([]);
                  setShowNewProject(false);
                }}
                onCancel={() => setShowNewProject(false)}
              />
            )}
          </>
        )}
      </FormField>

      {/* Repo multi-picker — only when a project is chosen */}
      {projectId && (
        <FormField
          label="Repositories"
          hint={
            repos && repos.length === 0
              ? 'No repos linked to this project yet — add one below.'
              : 'Pick one or more.  Most readymades target a single repo.'
          }
        >
          {repos === null && !reposErr ? (
            <span style={hintStyle}>Loading…</span>
          ) : reposErr ? (
            <span style={errorTextStyle}>{reposErr}</span>
          ) : (
            <>
              {repos && repos.length > 0 && (
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 4,
                    padding: 6,
                    border: '1px solid var(--v3-border)',
                    borderRadius: 4,
                    background: 'var(--v3-bg)',
                    maxHeight: 220,
                    overflowY: 'auto',
                  }}
                >
                  {repos.map((link) => (
                    <label
                      key={link.repo.id}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                        padding: '4px 6px',
                        borderRadius: 3,
                        cursor: 'pointer',
                        fontSize: 12.5,
                        color: 'var(--v3-text)',
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={repoIds.includes(link.repo.id)}
                        onChange={() => toggleRepo(link.repo.id)}
                      />
                      <span style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                        {link.repo.githubRepoFullName}
                      </span>
                      <span style={{ color: 'var(--v3-text-subtle)', fontSize: 11 }}>
                        @ {link.repo.defaultBranch}
                      </span>
                    </label>
                  ))}
                </div>
              )}
              {!showNewRepo && (
                <button
                  type="button"
                  onClick={() => setShowNewRepo(true)}
                  style={linkButtonStyle}
                >
                  + Add repo to this project
                </button>
              )}
              {showNewRepo && (
                <NewRepoInline
                  projectId={projectId}
                  onCreated={(link) => {
                    setRepos((prev) => (prev ? [...prev, link] : [link]));
                    onRepoIdsChange([...repoIds, link.repo.id]);
                    setShowNewRepo(false);
                  }}
                  onCancel={() => setShowNewRepo(false)}
                />
              )}
            </>
          )}
        </FormField>
      )}
    </div>
  );
}

function NewProjectInline({
  onCreated,
  onCancel,
  label,
}: {
  onCreated: (p: ProjectDto) => void;
  onCancel?: () => void;
  label?: string;
}) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!name.trim()) {
      setErr('Project name is required.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const p = await createProject({ name: name.trim() });
      onCreated(p);
    } catch (e) {
      setErr((e as Error)?.message ?? 'Failed to create project');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      style={{
        marginTop: 6,
        padding: 10,
        border: '1px dashed var(--v3-border)',
        borderRadius: 4,
        background: 'var(--v3-surface-elevated)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--v3-text-subtle)' }}>
        {label ?? 'New project'}
      </div>
      <input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        placeholder="Project name (e.g. Acme web app)"
        style={inputStyle}
      />
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="button" onClick={submit} disabled={busy} style={primaryButtonSm(busy)}>
          {busy ? 'Creating…' : 'Create'}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} style={ghostButtonSm}>
            Cancel
          </button>
        )}
      </div>
      {err && <div style={errorTextStyle}>{err}</div>}
    </div>
  );
}

function NewRepoInline({
  projectId,
  onCreated,
  onCancel,
}: {
  projectId: string;
  onCreated: (link: ProjectRepoLink) => void;
  onCancel: () => void;
}) {
  const [scope, setScope] = useState<'ORG' | 'USER'>('USER');
  const [fullName, setFullName] = useState('');
  const [defaultBranch, setDefaultBranch] = useState('main');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // R-336 — fetch the caller's GitHub repo catalogue on mount so the
  // picker can offer a real dropdown instead of a paste-the-string
  // text input.  Endpoint reads the user's stored PAT and returns
  // every repo they can see (owned + collaborator + org-member).
  // Falls back to the free-form input when:
  //   - the call fails (no PAT on the user, GitHub down, 401)
  //   - the response is empty (rare — usually means PAT scope too narrow)
  //   - the user explicitly toggles "type manually" (forks not in the
  //     default owner's list, etc.)
  const [githubRepos, setGithubRepos] = useState<GitHubRemoteRepo[] | null>(null);
  const [ghLoadErr, setGhLoadErr] = useState<string | null>(null);
  const [ghLoading, setGhLoading] = useState(true);
  const [manualMode, setManualMode] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setGhLoading(true);
    listGitHubRepos()
      .then((repos) => {
        if (cancelled) return;
        setGithubRepos(repos);
        if (repos.length === 0) {
          // Not a hard error — could be a scope-restricted PAT.
          // We render the manual-fallback hint when this lands.
          setGhLoadErr(null);
        }
      })
      .catch((e: Error) => {
        if (cancelled) return;
        setGhLoadErr(e?.message ?? 'Could not list GitHub repos');
        setGithubRepos(null);
      })
      .finally(() => {
        if (!cancelled) setGhLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /** When user picks a row from the dropdown, mirror its default
   *  branch into the branch input so they don't have to retype it. */
  function pickGithubRepo(value: string) {
    setFullName(value);
    if (value === '') return;
    const found = githubRepos?.find((r) => r.fullName === value);
    if (found?.defaultBranch) {
      setDefaultBranch(found.defaultBranch);
    }
  }

  async function submit() {
    if (!fullName.trim() || !fullName.includes('/')) {
      setErr('Repo full name must look like "owner/repo".');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const repo = await createRepo({
        scope,
        githubRepoFullName: fullName.trim(),
        defaultBranch: defaultBranch.trim() || undefined,
      });
      const link = await linkProjectRepo(projectId, repo.id);
      onCreated(link);
    } catch (e) {
      setErr((e as Error)?.message ?? 'Failed to add repo');
    } finally {
      setBusy(false);
    }
  }

  // R-336 — manual mode is the fallback when the GitHub list call
  // fails OR the user toggles it (e.g. for a forked repo outside
  // their default owner's catalogue).
  const useManual = manualMode || ghLoadErr !== null || (githubRepos !== null && githubRepos.length === 0);

  return (
    <div
      style={{
        marginTop: 6,
        padding: 10,
        border: '1px dashed var(--v3-border)',
        borderRadius: 4,
        background: 'var(--v3-surface-elevated)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--v3-text-subtle)' }}>
        Add repo
      </div>

      {ghLoading ? (
        <span style={hintStyle}>Loading your GitHub repos…</span>
      ) : useManual ? (
        <>
          {ghLoadErr && (
            <span style={{ ...hintStyle, color: '#FBBF24' }}>
              Couldn't fetch from GitHub ({ghLoadErr}) — type the repo name below.
            </span>
          )}
          {!ghLoadErr && githubRepos && githubRepos.length === 0 && (
            <span style={{ ...hintStyle, color: '#FBBF24' }}>
              No repos returned from GitHub — your PAT may be missing the
              <code style={{ margin: '0 4px' }}>repo</code>scope. Type the
              repo name manually below.
            </span>
          )}
          <input
            autoFocus
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            placeholder="owner/repo (e.g. navneetprabhakar/executespec)"
            style={inputStyle}
          />
        </>
      ) : (
        <>
          <select
            autoFocus
            value={fullName}
            onChange={(e) => pickGithubRepo(e.target.value)}
            style={inputStyle}
          >
            <option value="">— Pick a repo from GitHub —</option>
            {githubRepos?.map((r) => (
              <option key={r.fullName} value={r.fullName}>
                {r.fullName}
                {r.isPrivate ? ' (private)' : ''} · {r.defaultBranch}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => setManualMode(true)}
            style={{
              alignSelf: 'flex-start',
              padding: 0,
              background: 'transparent',
              border: 'none',
              fontSize: 11,
              color: 'var(--v3-text-subtle)',
              cursor: 'pointer',
              textDecoration: 'underline',
              fontFamily: 'inherit',
            }}
          >
            Don't see it? Type the repo name manually.
          </button>
        </>
      )}

      <div style={{ display: 'flex', gap: 6 }}>
        <select
          value={scope}
          onChange={(e) => setScope(e.target.value as 'ORG' | 'USER')}
          style={{ ...inputStyle, flex: 1 }}
        >
          <option value="USER">Personal repo</option>
          <option value="ORG">Org-scoped repo</option>
        </select>
        <input
          value={defaultBranch}
          onChange={(e) => setDefaultBranch(e.target.value)}
          placeholder="default branch"
          style={{ ...inputStyle, flex: 1 }}
        />
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="button" onClick={submit} disabled={busy || !fullName} style={primaryButtonSm(busy || !fullName)}>
          {busy ? 'Adding…' : 'Add + link'}
        </button>
        <button type="button" onClick={onCancel} style={ghostButtonSm}>
          Cancel
        </button>
      </div>
      {err && <div style={errorTextStyle}>{err}</div>}
    </div>
  );
}

function FormField({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label
        style={{
          fontSize: 11,
          fontWeight: 600,
          color: 'var(--v3-text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.4,
        }}
      >
        {label}
      </label>
      {children}
      {hint && (
        <span style={{ fontSize: 10.5, color: 'var(--v3-text-subtle)', lineHeight: 1.4 }}>
          {hint}
        </span>
      )}
    </div>
  );
}

const inputStyle: React.CSSProperties = {
  padding: '7px 10px',
  background: 'var(--v3-bg)',
  color: 'var(--v3-text)',
  border: '1px solid var(--v3-border)',
  borderRadius: 4,
  fontSize: 12.5,
  outline: 'none',
  fontFamily: 'inherit',
  width: '100%',
};

const hintStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--v3-text-subtle)',
  fontStyle: 'italic',
};

const errorTextStyle: React.CSSProperties = {
  fontSize: 11.5,
  color: '#FCA5A5',
  padding: '4px 8px',
  background: 'rgba(239,68,68,0.1)',
  border: '1px solid rgba(239,68,68,0.3)',
  borderRadius: 4,
};

const linkButtonStyle: React.CSSProperties = {
  alignSelf: 'flex-start',
  padding: '4px 8px',
  background: 'transparent',
  border: '1px dashed var(--v3-border)',
  borderRadius: 4,
  fontSize: 11.5,
  color: 'var(--v3-brand)',
  cursor: 'pointer',
  fontFamily: 'inherit',
};

function primaryButtonSm(disabled: boolean): React.CSSProperties {
  return {
    padding: '5px 10px',
    background: disabled ? 'transparent' : 'var(--v3-brand)',
    color: disabled ? 'var(--v3-text-subtle)' : 'var(--v3-text-on-brand)',
    border: disabled ? '1px solid var(--v3-border)' : 'none',
    borderRadius: 3,
    fontSize: 11.5,
    fontWeight: 600,
    cursor: disabled ? 'not-allowed' : 'pointer',
    fontFamily: 'inherit',
  };
}

const ghostButtonSm: React.CSSProperties = {
  padding: '5px 10px',
  background: 'transparent',
  color: 'var(--v3-text-subtle)',
  border: '1px solid var(--v3-border)',
  borderRadius: 3,
  fontSize: 11.5,
  cursor: 'pointer',
  fontFamily: 'inherit',
};
