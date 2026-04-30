import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  listGitHubRepos,
  createRepo,
  createProject,
  linkProjectRepo,
  validateGitHub,
  updateGithubSettings,
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
 *   6. Navigate to /projects/{newProjectId}.
 *
 * If the user has no saved GitHub PAT yet, we surface a clear "Connect
 * GitHub" CTA that routes to /integrations (R-144) — the user lands
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
 *   1. Private repo with no GitHub App access — R-321 (2026-04-28)
 *      shipped Q1(b) + Q1(c): when the popover detects no PAT, it
 *      now renders an inline PAT form + an "Ask your admin"
 *      affordance (pre-filled mailto + copy-link button) inside the
 *      popover, so users can paste-and-go without a context switch
 *      to /integrations. Q1(a) — org-wide GitHub App install
 *      flow — remains deferred until the App is registered.
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
  /** R-324 Checkpoint 2 — fired right after a successful
   *  {@code linkProjectRepo}.  When set on the "attach another repo"
   *  flow (i.e. {@code targetProjectId} present), the popover invokes
   *  this instead of navigating to `/projects/{projectId}` so the host
   *  page can refresh its repo list in place rather than reload.
   *  Ignored on the new-project flow (where navigate is the right
   *  default behaviour). */
  onAttached?: (newRepoId: string, projectId: string) => void;
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
  onAttached,
}: RepoAttachPopoverProps) {
  const isAuthed = useAuthStore((s) => s.isAuthenticated);
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);
  const navigate = useNavigate();

  const [phase, setPhase] = useState<Phase>('loading');
  const [repos, setRepos] = useState<GitHubRemoteRepo[]>([]);
  const [filter, setFilter] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [selecting, setSelecting] = useState<string | null>(null);

  // R-321 — inline PAT form state for the no-PAT panel. Mirrors
  // SettingsPage's PAT flow but condensed into the popover so users
  // who land here without a PAT can paste-and-go without a context
  // switch to /integrations.
  const [patInput, setPatInput] = useState('');
  const [patSaving, setPatSaving] = useState(false);
  const [patTesting, setPatTesting] = useState(false);
  const [patMsg, setPatMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [installLinkCopied, setInstallLinkCopied] = useState(false);
  // R-325 — track the repo the user just tried to attach so we can
  // surface a single-click "Attach anyway" retry on size-gate
  // rejection. Cleared on every retry / popover open.
  const [oversizedRepo, setOversizedRepo] = useState<GitHubRemoteRepo | null>(null);

  // Load repos whenever the popover opens.
  useEffect(() => {
    if (!open || !isAuthed) return;
    let cancelled = false;
    setPhase('loading');
    setError(null);
    setFilter('');
    setRepos([]);

    // R-321 — short-circuit to the no-PAT panel rather than fetching
    // and 4xx-ing if we already know the user has no token configured.
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

  // Reset PAT-form state on every open so a stale "saved ✓" message
  // doesn't bleed across sessions.
  useEffect(() => {
    if (!open) return;
    setPatInput('');
    setPatMsg(null);
    setInstallLinkCopied(false);
    setOversizedRepo(null);
  }, [open]);

  // Esc to close
  useEffect(() => {
    if (!open) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  // R-321 — test PAT against GitHub before save. Mirrors SettingsPage.
  async function onTestPat() {
    if (!patInput.trim()) {
      setPatMsg({ ok: false, text: 'Paste a PAT first.' });
      return;
    }
    setPatTesting(true);
    setPatMsg(null);
    try {
      const result = await validateGitHub(patInput.trim());
      setPatMsg({ ok: result.valid, text: result.message });
    } catch (e) {
      setPatMsg({ ok: false, text: (e as Error)?.message ?? 'Validation failed.' });
    } finally {
      setPatTesting(false);
    }
  }

  // R-321 — save the PAT, refresh user, then re-trigger repo load.
  // The load-effect above is keyed on `user`, so updating it via
  // setUser() naturally re-runs the listing.
  async function onSavePat() {
    if (!patInput.trim()) {
      setPatMsg({ ok: false, text: 'Paste a PAT first.' });
      return;
    }
    setPatSaving(true);
    setPatMsg(null);
    try {
      const updated = await updateGithubSettings(patInput.trim(), user?.githubOwner ?? '');
      setUser(updated);
      setPatInput('');
      setPatMsg({ ok: true, text: 'Saved. Listing your repos…' });
    } catch (e) {
      setPatMsg({ ok: false, text: (e as Error)?.message ?? 'Failed to save.' });
    } finally {
      setPatSaving(false);
    }
  }

  // R-321 — copy a sharable install link to the clipboard so the user
  // can paste it into Slack/email for an admin. The (a) GitHub App
  // org-install path is deferred until the App is registered; for now
  // the link points to /integrations where the admin can paste a
  // PAT (and, eventually, click through the App install).
  async function onCopyInstallLink() {
    const url = `${window.location.origin}/integrations`;
    try {
      await navigator.clipboard.writeText(url);
      setInstallLinkCopied(true);
      setTimeout(() => setInstallLinkCopied(false), 2000);
    } catch {
      // Older browsers / non-secure contexts: fall through silently.
      // The mailto link still works.
    }
  }

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return repos;
    return repos.filter((r) => r.fullName.toLowerCase().includes(q));
  }, [repos, filter]);

  async function onPick(repo: GitHubRemoteRepo, attachAnyway = false) {
    setSelecting(repo.fullName);
    setPhase('attaching');
    setError(null);
    if (!attachAnyway) setOversizedRepo(null);
    try {
      // Step 1 — register the repo with v3 (USER scope; ORG path can
      // be added later when org-wide GitHub App is wired). The
      // attachAnyway flag (R-325) bypasses the backend size gate and
      // marks the row so runs touching it count more credits.
      const created = await createRepo({
        scope: 'USER',
        githubRepoFullName: repo.fullName,
        defaultBranch: repo.defaultBranch,
        attachAnyway,
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
      // R-324 Checkpoint 2 — when the popover was opened from an
      // already-loaded project page (targetProjectId set), give the
      // host a chance to refresh its repo list in place instead of
      // bouncing through a navigate to the same URL (which is a no-op
      // for React Router and wouldn't re-fire the project's load
      // effect). The new-project flow keeps the legacy navigate.
      if (targetProjectId && onAttached) {
        onAttached(created.id, projectId!);
      } else {
        navigate(`/projects/${projectId}`);
      }
    } catch (e) {
      const msg = (e as Error)?.message ?? 'Attach failed';
      // R-325 — detect the backend's 413 size-gate copy and stash the
      // repo so we can surface an "Attach anyway" CTA. The message
      // contract is owned by ProjectRepoService.createRepo.
      const looksLikeSizeGate =
        msg.includes('larger than the platform') ||
        msg.includes('clone-time budget');
      if (looksLikeSizeGate && !attachAnyway) {
        setOversizedRepo(repo);
      }
      setError(msg);
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
            <div style={{ padding: '4px 2px', display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <Pill tone="warn">GitHub not connected</Pill>
                <p
                  style={{
                    marginTop: 8,
                    marginBottom: 0,
                    fontSize: 12.5,
                    color: 'var(--v3-text)',
                    lineHeight: 1.55,
                  }}
                >
                  Paste a personal access token (PAT) to list your repos —
                  or send the install link to a teammate who can.
                </p>
              </div>

              {/* R-321 (b) — inline PAT form. Mirrors SettingsPage's flow
                  so users who land here unconfigured can paste-and-go
                  without a context switch. */}
              <div
                style={{
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  padding: 12,
                  background: 'var(--v3-surface-elevated)',
                }}
              >
                <label
                  htmlFor="repo-attach-pat"
                  style={{
                    display: 'block',
                    fontSize: 11,
                    fontWeight: 600,
                    color: 'var(--v3-text-subtle)',
                    textTransform: 'uppercase',
                    letterSpacing: 0.4,
                    marginBottom: 6,
                  }}
                >
                  Paste a PAT
                </label>
                <input
                  id="repo-attach-pat"
                  type="password"
                  autoComplete="off"
                  value={patInput}
                  onChange={(e) => setPatInput(e.target.value)}
                  placeholder="ghp_xxxxxxxxxxxxxxxxxxxx"
                  disabled={patSaving || patTesting}
                  style={{
                    width: '100%',
                    padding: '7px 10px',
                    background: 'var(--v3-bg)',
                    color: 'var(--v3-text)',
                    border: '1px solid var(--v3-border)',
                    borderRadius: 4,
                    fontSize: 12.5,
                    outline: 'none',
                    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  }}
                />
                <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
                  <button
                    type="button"
                    onClick={onSavePat}
                    disabled={patSaving || patTesting || !patInput.trim()}
                    style={{
                      padding: '6px 12px',
                      background: 'var(--v3-brand)',
                      color: 'var(--v3-text-on-brand)',
                      border: 'none',
                      borderRadius: 4,
                      cursor: patSaving || patTesting || !patInput.trim() ? 'not-allowed' : 'pointer',
                      fontSize: 12,
                      fontWeight: 600,
                      fontFamily: 'inherit',
                      opacity: patSaving || patTesting || !patInput.trim() ? 0.6 : 1,
                    }}
                  >
                    {patSaving ? 'Saving…' : 'Save & list repos'}
                  </button>
                  <button
                    type="button"
                    onClick={onTestPat}
                    disabled={patSaving || patTesting || !patInput.trim()}
                    style={{
                      padding: '6px 12px',
                      background: 'transparent',
                      color: 'var(--v3-text)',
                      border: '1px solid var(--v3-border)',
                      borderRadius: 4,
                      cursor: patSaving || patTesting || !patInput.trim() ? 'not-allowed' : 'pointer',
                      fontSize: 12,
                      fontFamily: 'inherit',
                      opacity: patSaving || patTesting || !patInput.trim() ? 0.6 : 1,
                    }}
                  >
                    {patTesting ? 'Testing…' : 'Test'}
                  </button>
                </div>
                {patMsg && (
                  <p
                    style={{
                      marginTop: 8,
                      marginBottom: 0,
                      fontSize: 11.5,
                      color: patMsg.ok ? '#4ADE80' : '#FCA5A5',
                      lineHeight: 1.4,
                    }}
                  >
                    {patMsg.text}
                  </p>
                )}
                <p
                  style={{
                    marginTop: 8,
                    marginBottom: 0,
                    fontSize: 10.5,
                    color: 'var(--v3-text-subtle)',
                    lineHeight: 1.4,
                  }}
                >
                  Needs the <code>repo</code> scope.{' '}
                  <a
                    href="https://github.com/settings/tokens/new?scopes=repo&description=ExecuteSpec"
                    target="_blank"
                    rel="noopener noreferrer"
                    style={{ color: 'var(--v3-brand)' }}
                  >
                    Generate one →
                  </a>
                </p>
              </div>

              {/* R-321 (c) — "ask your admin" affordance: pre-filled
                  mailto + copy-link button. The GitHub App org-install
                  flow (Q1 option a) is deferred until the App is
                  registered; this panel is the bridge until then. */}
              <div
                style={{
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                  padding: 12,
                  background: 'var(--v3-surface-elevated)',
                }}
              >
                <p
                  style={{
                    fontSize: 11,
                    fontWeight: 600,
                    color: 'var(--v3-text-subtle)',
                    textTransform: 'uppercase',
                    letterSpacing: 0.4,
                    margin: '0 0 6px',
                  }}
                >
                  Ask your admin
                </p>
                <p
                  style={{
                    fontSize: 11.5,
                    color: 'var(--v3-text)',
                    lineHeight: 1.5,
                    margin: '0 0 10px',
                  }}
                >
                  No PAT for this org? Send a teammate the setup link.
                </p>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <a
                    href={`mailto:?subject=${encodeURIComponent(
                      'Please set up GitHub access for ExecuteSpec'
                    )}&body=${encodeURIComponent(
                      `Hi — I'm trying to use ExecuteSpec but my account can't list our repos.\n\nCould you set up GitHub access for the org? Setup link: ${window.location.origin}/integrations\n\nThanks.`
                    )}`}
                    style={{
                      padding: '6px 12px',
                      background: 'var(--v3-brand)',
                      color: 'var(--v3-text-on-brand)',
                      border: 'none',
                      borderRadius: 4,
                      cursor: 'pointer',
                      fontSize: 12,
                      fontWeight: 600,
                      textDecoration: 'none',
                      fontFamily: 'inherit',
                      display: 'inline-block',
                    }}
                  >
                    Email admin
                  </a>
                  <button
                    type="button"
                    onClick={onCopyInstallLink}
                    style={{
                      padding: '6px 12px',
                      background: 'transparent',
                      color: 'var(--v3-text)',
                      border: '1px solid var(--v3-border)',
                      borderRadius: 4,
                      cursor: 'pointer',
                      fontSize: 12,
                      fontFamily: 'inherit',
                    }}
                  >
                    {installLinkCopied ? 'Copied ✓' : 'Copy install link'}
                  </button>
                </div>
              </div>

              {/* Tertiary fallback — full Integrations page if neither
                  affordance fits the user's situation. */}
              <div style={{ textAlign: 'center' }}>
                <button
                  type="button"
                  onClick={() => {
                    onClose();
                    navigate('/integrations');
                  }}
                  style={{
                    background: 'transparent',
                    border: 'none',
                    color: 'var(--v3-text-subtle)',
                    fontSize: 11,
                    cursor: 'pointer',
                    textDecoration: 'underline',
                    fontFamily: 'inherit',
                  }}
                >
                  Or open the full Integrations page →
                </button>
              </div>
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
              {/* R-321 (2026-04-28) — the org-wide App install flow
                  (Q1 option a) is still deferred until the App is
                  registered. Until then, users whose PAT can't see
                  all the repos they expect can fall through to the
                  /integrations link below for the full setup
                  surface. */}
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
                  href="/integrations"
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
              <div style={{ marginTop: 8, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {/* R-325 — opt-in CTA shown only when the most recent
                    attach hit the size gate. One click re-submits the
                    same repo with attachAnyway=true. */}
                {oversizedRepo && (
                  <button
                    type="button"
                    onClick={() => onPick(oversizedRepo, true)}
                    title="Attach this repo despite its size. Runs touching it count more credits."
                    style={{
                      padding: '4px 10px',
                      background: 'rgba(245,158,11,0.18)',
                      color: '#FBBF24',
                      border: '1px solid rgba(245,158,11,0.5)',
                      borderRadius: 4,
                      cursor: 'pointer',
                      fontSize: 11,
                      fontWeight: 600,
                      fontFamily: 'inherit',
                    }}
                  >
                    Attach anyway (counts more credits)
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setPhase('loading');
                    setOversizedRepo(null);
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
                  {oversizedRepo ? 'Pick a different repo' : 'Retry'}
                </button>
              </div>
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
