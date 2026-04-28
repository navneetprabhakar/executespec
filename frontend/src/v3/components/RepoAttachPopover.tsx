import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listGitHubRepos,
  createRepo,
  createProject,
  linkProjectRepo,
  type GitHubRemoteRepo,
} from '@/lib/api';
import { useAuthStore } from '@/stores/useAuthStore';
import Pill from './Pill';

/**
 * R-125 — repo-attach popover.
 *
 * The connective tissue between R-124's project switcher action and
 * the OAuth-scope-grant flow §4.3 of the greenfield doc promised.
 * Today this closes the v2 cohabitation gap: clicking "+ Connect a
 * GitHub repo" from R-124 used to route to v2's OrgProjectsPage; now
 * it opens this popover and stays in v3.
 *
 * Happy path:
 *   1. User opens popover (from project switcher OR composer chip).
 *   2. We call /api/repos/github (existing v2 endpoint) which uses
 *      the user's saved GitHub PAT to list their repos.
 *   3. User picks a repo from the list.
 *   4. We call createRepo({scope:'USER', githubRepoFullName, defaultBranch})
 *      → returns a project_repo row.
 *   5. If the user has no current project context, we createProject()
 *      auto-named after the repo's short name and linkProjectRepo().
 *   6. Navigate to /v3/p/{newProjectId}.
 *
 * If the user has no saved GitHub PAT yet, we surface a clear "Connect
 * GitHub" CTA that routes to /v3/integrations (R-144) — the user lands
 * there, configures, and comes back. The popover doesn't try to do
 * OAuth on its own (that's a Wave-3 follow-up that needs the GitHub
 * App install path designed end-to-end).
 *
 * ──────────────────────────────────────────────────────────────────
 * D-125 — DESIGN DECISIONS PARKED (revisit before production traffic)
 *
 * The greenfield doc was deliberately light on edge cases. These
 * gaps have TODO comments at their natural call-sites so they can't
 * slip silently:
 *
 *   1. Private repo with no GitHub App access — today we list any
 *      repo the user's PAT can see. Org-level App install path is
 *      surfaced as a fallback link to /v3/integrations. TODO:
 *      decide whether the popover should auto-detect "no access"
 *      and switch to App-install copy inline.
 *
 *   2. Mono-repo subdirectory selection — design doesn't ask. Today
 *      we assume the repo root is the project root. TODO: an
 *      Advanced expander on the popover for `subdir` once we have
 *      a real mono-repo in QA.
 *
 *   3. Repo-too-big quota — no client-side gate. Backend's existing
 *      clone-size limits apply silently. TODO: surface a warning in
 *      the popover if the API returns a known-large repo size, so
 *      the user can decide to attach a smaller repo first.
 *
 *   4. Per-run repo override — design has the composer chip
 *      "[Attach repo: ▾]" but no add-from-composer flow. We keep
 *      the chip project-level only; "+ Connect a GitHub repo" is
 *      the only way to grow the project's repo list. TODO: revisit
 *      if multi-repo runs become common.
 * ──────────────────────────────────────────────────────────────────
 */

interface RepoAttachPopoverProps {
  open: boolean;
  onClose: () => void;
  /** When set, the picked repo is linked to this project; otherwise
   *  a new project is created auto-named after the repo. */
  targetProjectId?: string;
  /** Anchor element for the popover; absent → centre on screen. */
  anchorEl?: HTMLElement | null;
}

type Phase =
  | 'loading'
  | 'no-pat'
  | 'list'
  | 'attaching'
  | 'error';

export default function RepoAttachPopover({
  open,
  onClose,
  targetProjectId,
}: RepoAttachPopoverProps) {
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();

  const [phase, setPhase] = useState<Phase>('loading');
  const [repos, setRepos] = useState<GitHubRemoteRepo[]>([]);
  const [filter, setFilter] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [selecting, setSelecting] = useState<string | null>(null);

  // Load repos whenever the popover opens.
  useEffect(() => {
    if (!open || !isAuthed) return;
    let cancelled = false;
    setPhase('loading');
    setError(null);
    setFilter('');
    setRepos([]);

    // TODO(D-125): if user.hasGithubPat is false, treat that as the
    // "no PAT" path immediately rather than fetching and 4xx-ing.
    if (user && user.hasGithubPat === false) {
      setPhase('no-pat');
      return;
    }

    listGitHubRepos()
      .then((list) => {
        if (cancelled) return;
        setRepos(list ?? []);
        setPhase('list');
      })
      .catch((e: Error) => {
        if (cancelled) return;
        const msg = e?.message ?? '';
        // The backend returns 400/404 with a "no GitHub PAT configured"
        // message when the user hasn't set up a token. Surface the
        // dedicated no-PAT phase rather than a generic error.
        if (msg.toLowerCase().includes('pat') || msg.includes('GitHub')) {
          setPhase('no-pat');
        } else {
          setError(msg || 'Failed to list repositories');
          setPhase('error');
        }
      });

    return () => {
      cancelled = true;
    };
  }, [open, isAuthed, user]);

  // Esc to close
  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return repos;
    return repos.filter((r) => r.fullName.toLowerCase().includes(q));
  }, [repos, filter]);

  async function onPick(repo: GitHubRemoteRepo) {
    setSelecting(repo.fullName);
    setPhase('attaching');
    setError(null);
    try {
      // Step 1 — register the repo with v3 (USER scope; ORG path can
      // be added later when org-wide GitHub App is wired).
      const created = await createRepo({
        scope: 'USER',
        githubRepoFullName: repo.fullName,
        defaultBranch: repo.defaultBranch,
      });

      let projectId = targetProjectId;
      if (!projectId) {
        // TODO(D-125 #2): we currently use the repo's short name verbatim
        // as the project name. If we ever support mono-repo subdirs,
        // append the subdir here.
        const shortName = repo.fullName.split('/').pop() || repo.fullName;
        const project = await createProject({
          name: shortName,
          description: `Imported from github.com/${repo.fullName}`,
          aiProvider: 'ANTHROPIC',
        });
        projectId = project.id;
      }

      await linkProjectRepo(projectId!, created.id);
      onClose();
      navigate(`/v3/p/${projectId}`);
    } catch (e) {
      setError((e as Error)?.message ?? 'Attach failed');
      setPhase('error');
    } finally {
      setSelecting(null);
    }
  }

  if (!open) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Connect a GitHub repo"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.55)',
        zIndex: 110,
        display: 'flex',
        alignItems: 'flex-start',
        justifyContent: 'center',
        paddingTop: 'min(12vh, 96px)',
        padding: 16,
      }}
    >
      <div
        style={{
          width: 'min(560px, 92vw)',
          maxHeight: '70vh',
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          boxShadow: '0 24px 64px rgba(0,0,0,0.55)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        <header
          style={{
            padding: '12px 14px',
            borderBottom: '1px solid var(--v3-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div>
            <h2
              style={{
                margin: 0,
                fontSize: 14,
                fontWeight: 600,
                color: 'var(--v3-text)',
              }}
            >
              {targetProjectId ? 'Add a repo to this project' : 'Connect a GitHub repo'}
            </h2>
            <p
              style={{
                margin: '2px 0 0',
                fontSize: 11,
                color: 'var(--v3-text-subtle)',
              }}
            >
              Pick a repo. We'll auto-name the project and drop you into the chat.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            style={{
              padding: '4px 8px',
              background: 'transparent',
              color: 'var(--v3-text-subtle)',
              border: 'none',
              cursor: 'pointer',
              fontSize: 16,
            }}
          >
            ×
          </button>
        </header>

        <div style={{ padding: 12, flex: 1, overflowY: 'auto' }}>
          {phase === 'loading' && (
            <div style={{ padding: 16, fontSize: 12, color: 'var(--v3-text-subtle)', textAlign: 'center' }}>
              Loading your GitHub repositories…
            </div>
          )}

          {phase === 'no-pat' && (
            <div style={{ padding: '8px 4px' }}>
              <Pill tone="warn">GitHub not connected</Pill>
              <p
                style={{
                  marginTop: 8,
                  marginBottom: 12,
                  fontSize: 12.5,
                  color: 'var(--v3-text)',
                  lineHeight: 1.55,
                }}
              >
                Connect GitHub to list your repositories. We use a personal
                access token (PAT) for now — the org-wide GitHub App install
                path is on the way.
              </p>
              <button
                type="button"
                onClick={() => {
                  onClose();
                  navigate('/v3/integrations');
                }}
                style={{
                  padding: '8px 14px',
                  background: 'var(--v3-brand)',
                  color: '#0B0D10',
                  border: 'none',
                  borderRadius: 6,
                  cursor: 'pointer',
                  fontSize: 12.5,
                  fontWeight: 600,
                  fontFamily: 'inherit',
                }}
              >
                Open Integrations →
              </button>
            </div>
          )}

          {phase === 'list' && (
            <>
              <input
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                placeholder="Search owner/repo…"
                style={{
                  width: '100%',
                  padding: '7px 10px',
                  background: 'var(--v3-bg)',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  fontSize: 12.5,
                  outline: 'none',
                  marginBottom: 8,
                }}
              />
              {filtered.length === 0 && (
                <div style={{ padding: 12, fontSize: 12, color: 'var(--v3-text-subtle)', textAlign: 'center' }}>
                  No repositories match your filter.
                </div>
              )}
              <ul style={{ margin: 0, padding: 0, listStyle: 'none' }}>
                {filtered.map((r) => (
                  <li key={r.fullName}>
                    <button
                      type="button"
                      onClick={() => onPick(r)}
                      disabled={selecting !== null}
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'center',
                        width: '100%',
                        textAlign: 'left',
                        padding: '8px 10px',
                        background: 'transparent',
                        color: 'var(--v3-text)',
                        border: 'none',
                        borderRadius: 4,
                        cursor: selecting !== null ? 'progress' : 'pointer',
                        fontSize: 12.5,
                        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                      }}
                      onMouseEnter={(e) => {
                        if (selecting === null)
                          (e.currentTarget as HTMLButtonElement).style.background =
                            'var(--v3-surface-elevated)';
                      }}
                      onMouseLeave={(e) => {
                        (e.currentTarget as HTMLButtonElement).style.background = 'transparent';
                      }}
                    >
                      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {r.isPrivate && (
                          <span style={{ marginRight: 6, color: 'var(--v3-text-subtle)' }}>🔒</span>
                        )}
                        {r.fullName}
                      </span>
                      <span style={{ color: 'var(--v3-text-subtle)', fontSize: 10, marginLeft: 8, flexShrink: 0 }}>
                        {r.defaultBranch}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
              {/* TODO(D-125 #1): if the org-wide App install path
                  becomes available, surface it as a footer link here
                  for users whose PAT can't see all the repos they
                  expect. */}
              <p
                style={{
                  marginTop: 10,
                  fontSize: 10.5,
                  color: 'var(--v3-text-subtle)',
                  textAlign: 'center',
                }}
              >
                Don't see your repo?{' '}
                <a
                  href="/v3/integrations"
                  style={{ color: 'var(--v3-brand)' }}
                >
                  Open Integrations →
                </a>
              </p>
            </>
          )}

          {phase === 'attaching' && (
            <div style={{ padding: 16, fontSize: 12, color: 'var(--v3-text)', textAlign: 'center' }}>
              Attaching <code>{selecting}</code>…
            </div>
          )}

          {phase === 'error' && (
            <div
              style={{
                padding: 12,
                background: 'rgba(239,68,68,0.12)',
                border: '1px solid rgba(239,68,68,0.36)',
                color: '#FCA5A5',
                borderRadius: 6,
                fontSize: 12,
              }}
            >
              {error ?? 'Something went wrong. Try again.'}
              <button
                type="button"
                onClick={() => {
                  setPhase('loading');
                  // Re-trigger effect by toggling open prop is owned
                  // by the parent — instead just retry the listing.
                  listGitHubRepos()
                    .then((list) => {
                      setRepos(list ?? []);
                      setPhase('list');
                      setError(null);
                    })
                    .catch((e: Error) => {
                      setError(e?.message ?? 'Failed');
                      setPhase('error');
                    });
                }}
                style={{
                  marginTop: 8,
                  padding: '4px 10px',
                  background: 'transparent',
                  color: 'var(--v3-text)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 4,
                  cursor: 'pointer',
                  fontSize: 11,
                  fontFamily: 'inherit',
                }}
              >
                Retry
              </button>
            </div>
          )}
        </div>

        <footer
          style={{
            padding: '8px 14px',
            borderTop: '1px solid var(--v3-border)',
            color: 'var(--v3-text-subtle)',
            fontSize: 10.5,
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <span>Esc to close</span>
          <span>Connect with GitHub</span>
        </footer>
      </div>
    </div>
  );
}
