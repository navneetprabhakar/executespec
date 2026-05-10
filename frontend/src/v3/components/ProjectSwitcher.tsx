import {errMsg} from '@/lib/utils';
import {useEffect, useMemo, useRef, useState} from 'react';
import {useLocation, useNavigate, useParams} from 'react-router-dom';
import {getProjects, type ProjectDto} from '@/lib/api';
import {useAuthStore} from '@/stores/useAuthStore';
import {PROVIDER_DISPLAY} from '../lib/terminology';
import RepoAttachPopover from './RepoAttachPopover';

/**
 * R-124 — top-bar project switcher.
 *
 * The "where am I?" affordance for v3. Replaces the breadcrumb
 * (deleted) and the sidebar's "Projects" section. Click to open
 * a Linear-style popover with search + project list + "+ New project"
 * inline.
 *
 * "+ New project" lands as a real form in R-120's project home.
 * For now it routes to the v2 OrgProjectsPage which has the New
 * project form already wired — keeps the link live without forcing
 * us to ship the v3 create flow before R-120.
 */
export default function ProjectSwitcher() {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [projects, setProjects] = useState<ProjectDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [repoPopoverOpen, setRepoPopoverOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const navigate = useNavigate();
  const params = useParams<{slug?: string}>();
  const isAuthed = useAuthStore((s) => s.isAuthenticated);

  // Fetch projects on mount when authed + on auth state change. Need
  // them BEFORE the popover opens so the trigger button can show the
  // current project name (not "Pick a project") when there's a slug
  // in the URL.
  useEffect(() => {
    if (!isAuthed) {
      setProjects(null);
      return;
    }
    let cancelled = false;
    getProjects()
      .then((list) => {
        if (!cancelled) {
          setProjects(list ?? []);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setError(errMsg(e) ?? 'Failed to load projects');
          setProjects([]);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthed]);

  useEffect(() => {
    function onDoc(e: MouseEvent) {
      if (open && ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (open && e.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 0);
  }, [open]);

  // 2026-05-10 — derive the current project from the URL pathname directly
  // instead of from `params.slug`. The v3 routes use `:projectId` (and the
  // run-detail route uses `:projectId` AND `:runShort`); a top-level
  // `useParams` call here can't reach those nested params, so `params.slug`
  // was always undefined and `current` always null → the switcher always
  // rendered "Pick a project" even on `/projects/{id}` and
  // `/projects/{id}/runs/{runShort}`. Parsing pathname is robust against
  // route-layout reshuffles.
  const location = useLocation();
  const projectIdFromPath = useMemo<string | null>(() => {
    const m = location.pathname.match(/^\/projects\/([0-9a-fA-F-]{8,})(?:\/|$)/);
    return m ? m[1] : null;
  }, [location.pathname]);

  const current = useMemo(() => {
    if (!projects) return null;
    const slug = projectIdFromPath ?? params.slug ?? null;
    if (!slug) return null;
    return (
      projects.find((p) => p.id === slug) ?? projects.find((p) => p.id.startsWith(slug)) ?? null
    );
  }, [projects, projectIdFromPath, params.slug]);

  const filtered = useMemo(() => {
    if (!projects) return [];
    const q = query.trim().toLowerCase();
    if (!q) return projects;
    return projects.filter(
      (p) => p.name.toLowerCase().includes(q) || (p.description ?? '').toLowerCase().includes(q),
    );
  }, [projects, query]);

  // Logged-out: nothing useful in this slot.
  if (!isAuthed) {
    return (
      <span className='v3-topbar__placeholder' aria-hidden='true'>
        Sign in to pick a project
      </span>
    );
  }

  const triggerLabel = current?.name ?? 'Pick a project';

  return (
    <div ref={ref} style={{position: 'relative'}}>
      <button
        type='button'
        onClick={() => setOpen((o) => !o)}
        aria-haspopup='menu'
        aria-expanded={open}
        aria-label='Switch project'
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 6,
          // 2026-05-10 mobile pass — width caps + flex-shrink so the trigger
          // doesn't crowd out the logo + hamburger on 320px viewports. Old
          // `maxWidth: 280` could push the brand off-screen. Cap at 100% of
          // container so it shrinks first, then ellipsises the label.
          maxWidth: '100%',
          minWidth: 0,
          padding: '5px 10px',
          background: 'var(--v3-surface)',
          color: 'var(--v3-text)',
          border: '1px solid var(--v3-border)',
          borderRadius: 6,
          cursor: 'pointer',
          fontSize: 13,
          fontFamily: 'inherit',
          fontWeight: 500,
        }}
      >
        <span
          style={{
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {triggerLabel}
        </span>
        <span style={{color: 'var(--v3-text-subtle)', fontSize: 10}}>▾</span>
      </button>

      {open && (
        <div
          role='menu'
          aria-label='Projects'
          style={{
            position: 'absolute',
            top: 'calc(100% + 6px)',
            left: '50%',
            transform: 'translateX(-50%)',
            minWidth: 360,
            maxWidth: 420,
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 8,
            boxShadow: '0 16px 32px rgba(0,0,0,0.5)',
            zIndex: 80,
            display: 'flex',
            flexDirection: 'column',
            maxHeight: '70vh',
          }}
        >
          <div style={{padding: 8, borderBottom: '1px solid var(--v3-border)'}}>
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder='Search projects…'
              style={{
                width: '100%',
                padding: '6px 8px',
                background: 'var(--v3-bg)',
                color: 'var(--v3-text)',
                border: '1px solid var(--v3-border)',
                borderRadius: 4,
                fontSize: 12,
                outline: 'none',
              }}
            />
          </div>

          <ul style={{margin: 0, padding: 6, listStyle: 'none', overflowY: 'auto'}}>
            {projects === null && (
              <li style={{padding: '10px 12px', fontSize: 12, color: 'var(--v3-text-subtle)'}}>
                Loading projects…
              </li>
            )}
            {error && (
              <li style={{padding: '10px 12px', fontSize: 12, color: 'var(--v3-error)'}}>
                {error}
              </li>
            )}
            {projects !== null && filtered.length === 0 && !error && (
              <li style={{padding: '10px 12px', fontSize: 12, color: 'var(--v3-text-subtle)'}}>
                No projects match. Press "+ New project" below.
              </li>
            )}
            {filtered.map((p) => (
              <li key={p.id} role='none'>
                <button
                  role='menuitem'
                  type='button'
                  onClick={() => {
                    navigate(`/projects/${p.id}`);
                    setOpen(false);
                  }}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    width: '100%',
                    textAlign: 'left',
                    padding: '8px 10px',
                    background: current?.id === p.id ? 'var(--v3-surface-elevated)' : 'transparent',
                    color: 'var(--v3-text)',
                    border: 'none',
                    borderRadius: 4,
                    cursor: 'pointer',
                    fontSize: 12.5,
                    fontFamily: 'inherit',
                  }}
                  onMouseEnter={(e) => {
                    if (current?.id !== p.id)
                      e.currentTarget.style.background = 'var(--v3-surface-elevated)';
                  }}
                  onMouseLeave={(e) => {
                    if (current?.id !== p.id) e.currentTarget.style.background = 'transparent';
                  }}
                >
                  <span
                    style={{
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                      flex: 1,
                      minWidth: 0,
                    }}
                  >
                    {current?.id === p.id && '✓ '}
                    {p.name}
                  </span>
                  <span
                    style={{
                      color: 'var(--v3-text-subtle)',
                      fontSize: 10,
                      marginLeft: 8,
                      flexShrink: 0,
                    }}
                  >
                    {PROVIDER_DISPLAY[p.aiProvider] ?? p.aiProvider}
                  </span>
                </button>
              </li>
            ))}
          </ul>

          <div
            style={{
              padding: 6,
              borderTop: '1px solid var(--v3-border)',
              display: 'flex',
              flexDirection: 'column',
              gap: 2,
            }}
          >
            <button
              type='button'
              onClick={() => {
                // R-120 will own the inline "+ New project" form. For
                // now route to the v2 OrgProjectsPage so the link is
                // never dead and v3 dogfood doesn't get stuck.
                navigate('/org/projects?new=1');
                setOpen(false);
              }}
              style={{
                padding: '8px 10px',
                background: 'transparent',
                color: 'var(--v3-brand)',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                fontSize: 12.5,
                textAlign: 'left',
                fontFamily: 'inherit',
              }}
            >
              + New project
            </button>
            <button
              type='button'
              onClick={() => {
                setOpen(false);
                setRepoPopoverOpen(true);
              }}
              style={{
                padding: '8px 10px',
                background: 'transparent',
                color: 'var(--v3-brand)',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                fontSize: 12.5,
                textAlign: 'left',
                fontFamily: 'inherit',
              }}
            >
              + Connect a GitHub repo
            </button>
          </div>
        </div>
      )}

      {/* R-125 — repo-attach popover */}
      <RepoAttachPopover open={repoPopoverOpen} onClose={() => setRepoPopoverOpen(false)} />
    </div>
  );
}
