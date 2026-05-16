import {useEffect, useMemo, useRef, useState} from 'react';
import {AlertCircle, CheckCircle2, GitBranch, MoreHorizontal} from 'lucide-react';
import {errMsg} from '@/lib/utils';
import type {ProjectRepoLink, StackInfo} from '@/types/project';
import StackBadge from './StackBadge';
import RepoRelationshipEditor from './RepoRelationshipEditor';
import {installRepoWebhook} from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';

interface Props {
  link: ProjectRepoLink;
  onUnlink: (repoId: string) => void;
  onSync: (repoId: string) => void;
  onUpdateRelationships: (repoId: string, relationships: string[]) => void;
  onRefresh?: () => void | Promise<void>;
}

/**
 * 2026-05-14 UX feedback redesign — repo list-row.
 *
 * Pre-fix shape (4 rows tall on phone, all different sizes):
 *   Row 1: repo-name + USER badge
 *   Row 2: stack · branch · webhook chip (wrapped weirdly)
 *   Row 3: Sync (gray) · Install webhook (amber, ~190px) · Unlink (red)
 *   Row 4: no relationships · edit
 *
 * Issues called out: button widths wildly unequal, Unlink red shouts at the
 * user for a low-stakes admin action, Install-webhook eats half the card
 * width, no rhythm.
 *
 * New shape (proper Apple/Linear list-row):
 *   - Title row: icon · repo-name · scope chip ······ Sync (ghost) · ⋯
 *   - Meta row: branch · stack · webhook chip · last-sync (all chips,
 *               same scale, wrap as a single fluid line)
 *   - Relationship row (only if relationships set OR user expands edit)
 *
 * Destructive actions (Install webhook when not installed, Unlink) move
 * into the ⋯ overflow menu — they are admin-only and infrequent, so they
 * don't deserve top-level real-estate next to the always-used Sync.
 */
export default function RepoCard({
  link,
  onUnlink,
  onSync,
  onUpdateRelationships,
  onRefresh,
}: Props) {
  const {repo} = link;
  const [editing, setEditing] = useState(false);
  const [installingHook, setInstallingHook] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  // Click-outside / Escape closes the menu.
  useEffect(() => {
    if (!menuOpen) return;
    const onDown = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen]);

  const addToast = useUiStore((s) => s.addToast);
  const doInstallHook = async () => {
    if (installingHook) return;
    setInstallingHook(true);
    setMenuOpen(false);
    try {
      const r = await installRepoWebhook(repo.id);
      if (!r.installed && r.reason) {
        addToast({type: 'error', message: r.reason});
      } else if (r.installed) {
        addToast({type: 'success', message: 'Webhook installed'});
      }
      if (onRefresh) await onRefresh();
    } catch (e) {
      addToast({
        type: 'error',
        message: `Webhook install failed: ${e instanceof Error ? errMsg(e) : String(e)}`,
      });
    } finally {
      setInstallingHook(false);
    }
  };

  const stack = useMemo<StackInfo | null>(() => {
    if (!repo.detectedStack) return null;
    try {
      return JSON.parse(repo.detectedStack) as StackInfo;
    } catch {
      return null;
    }
  }, [repo.detectedStack]);

  const relationships = useMemo<string[]>(() => {
    if (!link.relationships) return [];
    if (Array.isArray(link.relationships)) return link.relationships;
    try {
      const parsed = JSON.parse(link.relationships as unknown as string);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }, [link.relationships]);

  return (
    <div className='rounded-md border border-[var(--border-default)] bg-[var(--surface-card)] surface-card p-3 sm:p-4'>
      {/* Title row — name + scope inline, actions right.
          Hierarchy: name (primary 13px) > scope chip (10px micro) > Sync ghost
          button > overflow ⋯. The Sync button + ⋯ are the only top-level
          actions; everything else lives in the menu. Same row at every
          breakpoint — no stacking — because the title is single-line truncate
          and the action cluster is fixed-width (74px Sync + 32px ⋯). */}
      <div className='flex items-center gap-3'>
        <div className='flex items-center gap-2 min-w-0 flex-1'>
          {/* Inline whiteSpace:nowrap + overflow:hidden + text-overflow:ellipsis
              because `truncate` was being overridden by an ancestor white-space
              rule (computed white-space:normal was leaking through). Belt and
              braces — the class stays for clarity, the inline styles win. */}
          <span
            className='font-mono text-[13px] font-medium truncate text-primary'
            style={{
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              minWidth: 0,
              flex: '1 1 auto',
            }}
            title={repo.githubRepoFullName}
          >
            {repo.githubRepoFullName}
          </span>
          <span
            className={`shrink-0 inline-flex items-center rounded-sm px-1.5 py-0.5 text-[10px] font-medium tracking-wide uppercase ${
              repo.scope === 'ORG'
                ? 'bg-accent-blue/10 text-accent-blue'
                : 'bg-[var(--surface-sunken)] text-muted'
            }`}
          >
            {repo.scope}
          </span>
        </div>
        <div className='flex items-center gap-1 shrink-0'>
          <button
            onClick={() => onSync(repo.id)}
            className='press-effect inline-flex items-center gap-1.5 text-[12px] font-medium px-2.5 h-8 rounded-md border border-[var(--border-default)] text-secondary hover:text-primary hover:border-[var(--border-hover)] hover:bg-[var(--surface-raised)] transition-colors'
            title='Pull latest from origin and regenerate codemap/architecture'
          >
            Sync
          </button>
          <div className='relative' ref={menuRef}>
            <button
              onClick={() => setMenuOpen((v) => !v)}
              aria-haspopup='menu'
              aria-expanded={menuOpen}
              aria-label='More repository actions'
              className='press-effect inline-flex items-center justify-center w-8 h-8 rounded-md border border-[var(--border-default)] text-muted hover:text-primary hover:border-[var(--border-hover)] hover:bg-[var(--surface-raised)] transition-colors'
            >
              <MoreHorizontal className='h-4 w-4' />
            </button>
            {menuOpen && (
              <div
                role='menu'
                className='absolute right-0 top-9 z-20 min-w-[180px] rounded-md border border-[var(--border-default)] bg-[var(--surface-raised)] py-1 shadow-lg'
                style={{boxShadow: 'var(--shadow-overlay)'}}
              >
                {!repo.webhookInstalled && (
                  <button
                    onClick={doInstallHook}
                    disabled={installingHook}
                    role='menuitem'
                    className='w-full text-left px-3 py-2 text-[12.5px] text-primary hover:bg-[var(--surface-sunken)] disabled:opacity-50 flex items-center justify-between gap-3'
                    title='Install a pull_request webhook using your GitHub PAT (requires admin:repo_hook)'
                  >
                    <span>{installingHook ? 'Installing webhook…' : 'Install webhook'}</span>
                    <AlertCircle className='h-3.5 w-3.5 text-status-waiting shrink-0' />
                  </button>
                )}
                <button
                  onClick={() => {
                    setMenuOpen(false);
                    setEditing(true);
                  }}
                  role='menuitem'
                  className='w-full text-left px-3 py-2 text-[12.5px] text-primary hover:bg-[var(--surface-sunken)]'
                >
                  Edit relationships
                </button>
                <div className='my-1 h-px bg-[var(--hairline)]' aria-hidden='true' />
                <button
                  onClick={() => {
                    setMenuOpen(false);
                    onUnlink(repo.id);
                  }}
                  role='menuitem'
                  className='w-full text-left px-3 py-2 text-[12.5px] text-status-error hover:bg-status-error/10'
                >
                  Unlink repository
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Meta row — every chip is the same scale (11px / px-2 py-0.5) so the
          row reads as a single fluid string. Wraps gracefully. Order picks
          the most-informative first: branch → stack → optional flags →
          webhook status → last sync. */}
      <div className='mt-2 flex flex-wrap items-center gap-x-2 gap-y-1.5 text-[11px] text-muted'>
        <span className='inline-flex items-center gap-1'>
          <GitBranch className='h-3 w-3' />
          {repo.defaultBranch}
        </span>
        <span aria-hidden='true' className='text-subtle'>
          ·
        </span>
        <StackBadge stack={stack} />
        {repo.subdir && (
          <>
            <span aria-hidden='true' className='text-subtle'>
              ·
            </span>
            <span
              className='inline-flex items-center rounded-sm bg-accent-blue/10 px-1.5 py-0.5 text-[10px] font-mono text-accent-blue'
              title='.executespec/root.yml declares this subdirectory as the project root'
            >
              root: {repo.subdir}
            </span>
          </>
        )}
        {repo.attachedAnyway && (
          <>
            <span aria-hidden='true' className='text-subtle'>
              ·
            </span>
            <span
              className='inline-flex items-center rounded-sm bg-accent-amber/10 px-1.5 py-0.5 text-[10px] text-accent-amber'
              title={`Attached despite the platform size budget${
                repo.sizeKb ? ` (≈${Math.round(repo.sizeKb / 1024)} MB)` : ''
              }. Runs touching this repo count more credits.`}
            >
              big repo
            </span>
          </>
        )}
        <span aria-hidden='true' className='text-subtle'>
          ·
        </span>
        <span
          className={`inline-flex items-center gap-1 ${
            repo.webhookInstalled ? 'text-status-success' : 'text-status-waiting'
          }`}
          title={
            repo.webhookInstalled
              ? 'Webhook installed — PR merges trigger post-merge cascade'
              : 'Optional — install via the ⋯ menu to auto-refresh architecture/memory on PR merge'
          }
        >
          {repo.webhookInstalled ? (
            <>
              <CheckCircle2 className='h-3 w-3' />
              webhook
            </>
          ) : (
            <>
              <AlertCircle className='h-3 w-3' />
              webhook off
            </>
          )}
        </span>
        {repo.lastSyncedSha && (
          <>
            <span aria-hidden='true' className='text-subtle'>
              ·
            </span>
            <span title='Last synced commit SHA'>sync {repo.lastSyncedSha.slice(0, 7)}</span>
          </>
        )}
      </div>

      {/* Relationships — surfaced as a thin row separated by a hairline so it
          reads as secondary metadata, not co-equal with the actions. Hidden
          entirely when relationships are empty AND the user isn't editing
          (the ⋯ → "Edit relationships" menu item is the entry-point). */}
      {(relationships.length > 0 || editing) && (
        <div className='mt-3 pt-3 border-t border-[var(--hairline)]'>
          {editing ? (
            <RepoRelationshipEditor
              initial={relationships}
              onSave={(next) => {
                onUpdateRelationships(repo.id, next);
                setEditing(false);
              }}
              onCancel={() => setEditing(false)}
            />
          ) : (
            <div className='flex flex-wrap items-center gap-x-2 gap-y-1'>
              <span className='text-[10.5px] font-medium uppercase tracking-wider text-subtle'>
                Linked to
              </span>
              {relationships.map((r) => (
                <span
                  key={r}
                  className='inline-flex items-center rounded-sm bg-[var(--surface-sunken)] px-1.5 py-0.5 text-[11px] text-secondary'
                >
                  {r}
                </span>
              ))}
              <button
                onClick={() => setEditing(true)}
                className='text-[11px] text-accent-blue hover:underline ml-1'
              >
                Edit
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
