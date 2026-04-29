import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Sparkles, FolderPlus, ArrowRight } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import { createProject } from '@/lib/api';

/**
 * R-310 (Wave 7) — explicit empty-state for users with zero projects.
 *
 * **Why this exists.** F1 e2e walk found that V3HomeRedirect was sending
 * 0-projects users to `/projects/empty`, which V3ProjectHome then tried to
 * load via `getProject('empty')` (404) + `getRuns({projectId:'empty'})`.
 * The page rendered with no `<h1>`, the network panel showed `404 on
 * /api/projects/empty`, and the user concluded the product was broken.
 * F1 ranked this as F-001 (MAJOR) and F2 as P1 (single biggest "is this
 * broken?" trigger in the product).
 *
 * **What this fix is.** A real empty-state component routed at the
 * literal slug `/projects/empty` (registered BEFORE the catch-all `p/:slug`
 * in App.tsx so it takes priority). Renders:
 *   - A welcome line that uses the user's first name when available
 *   - A short reframing of the value proposition
 *   - A primary "Create my first project" CTA that POSTs a default-named
 *     project + auto-navigates to `/projects/{newId}` (where Composer kicks in)
 *   - A secondary "Set up a real project (with a GitHub repo) →" link to
 *     /org/projects, for users who already have a repo to wire up
 *
 * **What it deliberately does NOT do.** It does not call
 * `getProject('empty')` — that 404 is the bug. It does not render the
 * Composer pre-emptively — composing requires a real project id, and
 * showing the composer before the user has a project is the same trap
 * V3ProjectHome fell into.
 *
 * Acceptance per docs/plans/wave-7-issue-list.md R-310:
 *   (a) `/projects/empty` renders this component
 *   (b) Walker finds zero "?"-headed pages on Free + Pro
 *   (c) No `/api/projects/empty` 404 fired on visit
 */
export default function V3EmptyProject() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const firstName = user?.firstName?.trim() || '';
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onCreate() {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const defaultName = firstName
        ? `${firstName}'s first project`
        : 'My first project';
      const project = await createProject({ name: defaultName });
      navigate(`/projects/${project.id}`, { replace: true });
    } catch (e) {
      setError((e as Error)?.message ?? 'Failed to create project');
      setCreating(false);
    }
  }

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 'calc(100vh - 56px - 96px)',
        padding: '32px 24px',
        textAlign: 'center',
      }}
    >
      <div
        style={{
          width: 56,
          height: 56,
          borderRadius: '50%',
          background: 'rgba(91,141,239,0.12)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          marginBottom: 18,
        }}
      >
        <Sparkles className="h-7 w-7" style={{ color: 'var(--v3-brand)' }} aria-hidden="true" />
      </div>

      <h1
        style={{
          fontSize: 24,
          fontWeight: 600,
          margin: 0,
          color: 'var(--v3-text)',
          lineHeight: 1.2,
        }}
      >
        {firstName ? `Welcome, ${firstName}.` : 'Welcome to ExecuteSpec.'}
      </h1>
      <p
        style={{
          marginTop: 10,
          maxWidth: 460,
          fontSize: 14,
          lineHeight: 1.55,
          color: 'var(--v3-text-muted)',
        }}
      >
        You don't have any projects yet — let's create your first one.
        Then describe what you want to build, and we'll ship a draft PR
        you can actually merge.
      </p>

      <button
        type="button"
        onClick={onCreate}
        disabled={creating}
        style={{
          marginTop: 22,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 18px',
          background: creating ? 'var(--v3-surface-elevated)' : 'var(--v3-brand)',
          color: creating ? 'var(--v3-text-subtle)' : 'var(--v3-text-on-brand)',
          border: 'none',
          borderRadius: 6,
          fontSize: 13.5,
          fontWeight: 600,
          cursor: creating ? 'not-allowed' : 'pointer',
          fontFamily: 'inherit',
        }}
      >
        <FolderPlus className="h-4 w-4" aria-hidden="true" />
        {creating ? 'Creating…' : 'Create my first project'}
        <ArrowRight className="h-4 w-4" aria-hidden="true" />
      </button>

      {error && (
        <div
          role="alert"
          style={{
            marginTop: 12,
            padding: '6px 10px',
            background: 'rgba(239,68,68,0.12)',
            border: '1px solid rgba(239,68,68,0.36)',
            color: '#FCA5A5',
            borderRadius: 4,
            fontSize: 12,
            maxWidth: 460,
          }}
        >
          {error}
        </div>
      )}

      <div
        style={{
          marginTop: 28,
          fontSize: 12,
          color: 'var(--v3-text-subtle)',
        }}
      >
        Want to attach a GitHub repo first?{' '}
        <Link
          to="/org/projects"
          style={{
            color: 'var(--v3-brand)',
            textDecoration: 'none',
            fontWeight: 500,
          }}
        >
          Set up a project the long way →
        </Link>
      </div>
    </div>
  );
}
