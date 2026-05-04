import {useEffect, useState} from 'react';
import {useNavigate, Link} from 'react-router-dom';
import {Sparkles, FolderPlus, ArrowRight, MessageSquareText} from 'lucide-react';
import {useAuthStore} from '@/stores/useAuthStore';
import {createProject} from '@/lib/api';

/**
 * R-310 (Wave 7) — explicit empty-state for users with zero projects.
 *
 * **Why this exists.** F1 e2e walk found that HomeRedirectPage was sending
 * 0-projects users to `/projects/empty`, which ProjectHomePage then tried to
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
 * ProjectHomePage fell into.
 *
 * Acceptance per docs/plans/wave-7-issue-list.md R-310:
 *   (a) `/projects/empty` renders this component
 *   (b) Walker finds zero "?"-headed pages on Free + Pro
 *   (c) No `/api/projects/empty` 404 fired on visit
 */
export default function EmptyProjectPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const firstName = user?.firstName?.trim() || '';
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // R-343 (P0 fix from UX persona review) — surface the typed spec the
  // user brought from the marketing site.  AuthPage.claimAndForward
  // already routes the user here when they have no project yet, but
  // it never *displayed* the stashed spec, so users typed "Add OAuth
  // login" on executespec.ai, signed up, and landed on a generic
  // "create your first project" screen — concluding their spec
  // disappeared.  This effect reads the sessionStorage key written
  // by the marketing-site hero composer + the LandingPage fallback,
  // and renders a "Your spec is ready" card above the create-project
  // CTA.  When the user clicks Create, the spec stays in
  // sessionStorage so ProjectHomePage's composer hydrates with it on
  // the project they just created.
  const [stashedSpec, setStashedSpec] = useState<string>('');
  useEffect(() => {
    try {
      const spec = sessionStorage.getItem('v3-anon-spec');
      if (spec && spec.trim().length > 0) setStashedSpec(spec.trim());
    } catch (_) {
      /* private mode — silent fallback */
    }
  }, []);

  async function onCreate() {
    if (creating) return;
    setCreating(true);
    setError(null);
    try {
      const defaultName = firstName ? `${firstName}'s first project` : 'My first project';
      const project = await createProject({name: defaultName});
      navigate(`/projects/${project.id}`, {replace: true});
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
        <Sparkles className='h-7 w-7' style={{color: 'var(--v3-brand)'}} aria-hidden='true' />
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
        {/* D3 (round-3 audit) — outcome-led question instead of "Welcome".
            Matches the login hero ("Ship AI-written PRs you can actually
            merge.") which leads with outcome, and v0/Lovable/Bolt's
            zero-state which open with a question, not a noun. */}
        {firstName ? `What do you want to build, ${firstName}?` : 'What do you want to build?'}
      </h1>
      <p
        style={{
          marginTop: 10,
          maxWidth: 480,
          fontSize: 14,
          lineHeight: 1.55,
          color: 'var(--v3-text-muted)',
        }}
      >
        {stashedSpec
          ? "Your spec is ready — let's create a project to ship it into."
          : tierBlurb(user?.tier)}
      </p>

      {/* R-343 — spec-ready card.  Rendered when AuthPage.claimAndForward
          handed off a spec via sessionStorage.  Tells the user explicitly
          that their typed spec is preserved + will hydrate the composer
          on the project they're about to create.  Truncated to 280 chars
          for the preview; the full spec rides through. */}
      {stashedSpec && (
        <div
          style={{
            marginTop: 16,
            maxWidth: 560,
            width: '100%',
            padding: 14,
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 8,
            textAlign: 'left',
            display: 'flex',
            gap: 12,
            alignItems: 'flex-start',
          }}
        >
          <div
            style={{
              width: 28,
              height: 28,
              borderRadius: 6,
              background: 'rgba(91,141,239,0.12)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
            }}
          >
            <MessageSquareText
              className='h-4 w-4'
              style={{color: 'var(--v3-brand)'}}
              aria-hidden='true'
            />
          </div>
          <div style={{flex: 1, minWidth: 0}}>
            <div
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--v3-text-subtle)',
                letterSpacing: 0.4,
                textTransform: 'uppercase',
                marginBottom: 4,
              }}
            >
              Your spec, ready to ship
            </div>
            <div
              style={{
                fontSize: 13,
                color: 'var(--v3-text)',
                lineHeight: 1.5,
                whiteSpace: 'pre-wrap',
                overflow: 'hidden',
                display: '-webkit-box',
                WebkitLineClamp: 4,
                WebkitBoxOrient: 'vertical',
              }}
            >
              {stashedSpec.length > 280 ? stashedSpec.slice(0, 280) + '…' : stashedSpec}
            </div>
            <div
              style={{
                marginTop: 6,
                fontSize: 11,
                color: 'var(--v3-text-subtle)',
              }}
            >
              We'll pre-fill this in your composer the moment your project's ready.
            </div>
          </div>
        </div>
      )}

      <button
        type='button'
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
        <FolderPlus className='h-4 w-4' aria-hidden='true' />
        {creating ? 'Creating…' : 'Create my first project'}
        <ArrowRight className='h-4 w-4' aria-hidden='true' />
      </button>

      {error && (
        <div
          role='alert'
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
        {/* D3 (round-3 audit) — "long way" framed the primary CTA as a
            shortcut. Renamed to a positive outcome path so the secondary
            choice is "attach a repo first" not "do it the slow way". */}
        Already have a GitHub repo?{' '}
        <Link
          to='/org/projects'
          style={{
            color: 'var(--v3-brand)',
            textDecoration: 'none',
            fontWeight: 500,
          }}
        >
          Attach it first →
        </Link>
      </div>
    </div>
  );
}

/**
 * D3 (round-3 audit) — tier-aware "what does this plan get me" framing.
 * Surfaces the run quota in human terms ("≈ 5 small or 3 large PRs / month")
 * instead of the bare "5 runs / month" the persona-test report flagged
 * as too restrictive-feeling next to consumer competitors. Paid tiers get
 * a neutral framing because the run cap is unlimited.
 *
 * 2026-05-04: FREE tier policy is held at 5 runs / month
 * (post-V145 bump-to-10 was reverted as a positioning decision —
 * "free tier will always remain 5"). Reflect that here.
 */
function tierBlurb(tier?: string): string {
  const normalized = (tier || 'FREE').toUpperCase();
  if (normalized === 'ENTERPRISE' || normalized === 'TEAM' || normalized === 'MAX') {
    return "Describe what you want to build and we'll ship a draft PR you can actually merge — across one repo or twenty.";
  }
  if (normalized === 'PRO') {
    return "Describe what you want to build and we'll ship a draft PR you can actually merge. Your Pro plan covers 20 runs / month (≈ 20 small or 6 large PRs).";
  }
  // FREE (and everything unrecognised) — surface what 5 runs actually buys.
  return "Describe what you want to build and we'll ship a draft PR you can actually merge. Your free plan covers 5 PRs / month — about 5 small features or 3 large refactors.";
}
