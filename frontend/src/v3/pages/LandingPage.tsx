import {useEffect, useState} from 'react';
import {Link, useNavigate} from 'react-router-dom';
// 2026-04-28 — submitAnonymousRun + AnonymousRunResponse type no longer
// imported here. Submit now redirects straight to /login (with the typed
// spec stashed in sessionStorage for post-signup composer hydration).
// The /api/v3/anonymous-runs endpoint + the AnonymousRunSession backend
// are still live for the post-signup claim flow.
import {
  getV3LandingStats,
  getV3SampleRuns,
  getV3ExecSnapshot,
  type V3LandingStats,
  type V3SampleRun,
  type V3ExecSnapshot,
} from '../lib/api';
import {trackV3, V3_EVENTS} from '../lib/events';
import _Pill from '../components/Pill';

/**
 * R-110 — chat-first marketing landing for anonymous visitors.
 *
 * Greenfield doc §4.1: textarea above the fold, no signup required
 * for first run. Mirrors v0.dev / Bolt / Lovable. Conversion event:
 * landing_textarea_submit (fires on submit, regardless of whether
 * the run dispatches for real or stub).
 *
 * On stub mode (Wave 1 default), submit returns a fake run reference;
 * we surface it as "Sign up to ship a real run" with the spec preserved
 * in sessionStorage for the auth → /projects/... handoff.
 *
 * Selling-point pass: hero copy + CSS-frame demo + verified-score
 * counter + sample-run gallery + per-persona footer routing all
 * land here. Pulls live aggregates from /api/v3/landing/stats +
 * /api/v3/landing/sample-runs (both anonymized, public).
 */

const SAMPLE_PROMPTS = [
  'Add OAuth login to my Next.js app',
  'Refactor a React component to TypeScript',
  'Build a Stripe checkout endpoint',
  'Add unit-test coverage for src/auth/',
  'Migrate from Express to Fastify',
  'Add a /healthz endpoint to my Spring Boot app',
];

export default function LandingPage() {
  // 2026-04-29 — the static marketing site (executespec.ai) now hosts a
  // chat-first composer in its hero.  When the visitor submits there, the
  // marketing site redirects here with the typed spec in a `?prefill=…`
  // query param.  Initial state honors that param so the user lands inside
  // the app with their spec already in the textarea, ready to refine + ship.
  const initialPrefill = (() => {
    if (typeof window === 'undefined') return '';
    try {
      const raw = new URLSearchParams(window.location.search).get('prefill');
      if (!raw) return '';
      // Cap at 4 KB to match the website's submit-handler cap.  Anything
      // longer is almost certainly an exploit attempt or a malformed link.
      return raw.length > 4000 ? raw.slice(0, 4000) : raw;
    } catch {
      return '';
    }
  })();

  const [text, setText] = useState(initialPrefill);
  const [submitting, setSubmitting] = useState(false);
  // 2026-04-28 — `result` state + AnonymousRunResponse type removed. The
  // submit handler now navigates to /login on first keystroke-then-submit
  // so the trial UI never shows.
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  // Selling-point #2 + #7 — fetch live aggregates on mount. Both endpoints
  // are public + cheap, so failure is silent (we just hide the proof block
  // rather than blocking the conversion path).
  const [stats, setStats] = useState<V3LandingStats | null>(null);
  const [samples, setSamples] = useState<V3SampleRun[] | null>(null);
  // R-313 (Wave 7) — exec snapshot for the assertive proof line ("X PRs
  // shipped in the last 30 days") that F2 ranked as P2 (unblocks Dana).
  const [snapshot, setSnapshot] = useState<V3ExecSnapshot | null>(null);
  useEffect(() => {
    let cancelled = false;
    getV3LandingStats()
      .then((s) => {
        if (!cancelled) setStats(s);
      })
      .catch(() => {
        /* hide block on failure */
      });
    getV3SampleRuns()
      .then((rows) => {
        if (!cancelled) setSamples(rows);
      })
      .catch(() => {
        /* hide block on failure */
      });
    getV3ExecSnapshot()
      .then((snap) => {
        if (!cancelled) setSnapshot(snap);
      })
      .catch(() => {
        /* hide block on failure */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (text.trim().length === 0 || submitting) return;
    setSubmitting(true);
    setError(null);
    trackV3(V3_EVENTS.LANDING_TEXTAREA_SUBMIT, {textLen: text.length});
    // 2026-04-28 — direct redirect to /login on anon submit. Previously the
    // textarea triggered an anonymous-trial run + showed a "Save this run —
    // sign in" panel; per founder direction we now skip the trial UI and
    // route the visitor straight into auth, pre-stashing the typed spec so
    // the post-signup handoff still rehydrates the composer.
    try {
      sessionStorage.setItem('v3-anon-spec', text.trim());
    } catch {
      /* private mode — best-effort */
    }
    navigate('/login', {state: {from: '/'}});
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 56px - 48px)'}}>
      {/* Hero */}
      <section
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: 16,
          padding: '48px 0 24px',
        }}
      >
        <h1
          style={{
            fontSize: 36,
            fontWeight: 600,
            color: 'var(--v3-text)',
            textAlign: 'center',
            margin: 0,
            lineHeight: 1.15,
            maxWidth: 820,
          }}
        >
          ExecuteSpec ships AI-written pull requests
          <br />
          you can actually{' '}
          {/* R-305 (Wave 6) — brand-pop gradient on the verb. ONE word per
              page max per the strategy; "merge" is the verb the whole
              pitch hangs on. Mirrors website's text-gradient-orange
              treatment via --v3-pop-grad (orange→amber). */}
          <span
            style={{
              fontStyle: 'italic',
              fontFamily: "'Instrument Serif', Georgia, serif",
              fontWeight: 500,
              backgroundImage: 'var(--v3-pop-grad)',
              backgroundClip: 'text',
              WebkitBackgroundClip: 'text',
              color: 'transparent',
              WebkitTextFillColor: 'transparent',
            }}
          >
            merge
          </span>{' '}
          — for one repo or for twenty.
        </h1>
        <p
          style={{
            fontSize: 15,
            color: 'var(--v3-text-muted)',
            textAlign: 'center',
            maxWidth: 640,
            margin: 0,
            lineHeight: 1.55,
          }}
        >
          Plan, build, test, review, open a PR. ~80% of real runs land Ready — and you see the
          verdict (Ready / Needs review / Failed) on every one of yours.
        </p>
      </section>

      {/* Composer */}
      <form
        onSubmit={onSubmit}
        style={{
          maxWidth: 720,
          margin: '0 auto',
          width: '100%',
          padding: '0 16px',
        }}
        aria-label='Anonymous trial composer'
      >
        <div
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border-strong)',
            borderRadius: 10,
            padding: 12,
          }}
        >
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder='e.g. "Add a forgot-password flow to my Spring Boot app"'
            rows={4}
            aria-label='What do you want to build'
            disabled={submitting}
            style={{
              width: '100%',
              background: 'transparent',
              color: 'var(--v3-text)',
              border: 'none',
              outline: 'none',
              resize: 'vertical',
              fontFamily: 'inherit',
              fontSize: 15,
              lineHeight: 1.55,
              minHeight: 96,
            }}
          />

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginTop: 10,
              paddingTop: 10,
              borderTop: '1px solid var(--v3-border)',
              gap: 8,
              flexWrap: 'wrap',
            }}
          >
            <span style={{fontSize: 11.5, color: 'var(--v3-text-subtle)'}}>
              Free preview · no card · ~60s · sign up to ship the code
            </span>
            <button
              type='submit'
              disabled={text.trim().length === 0 || submitting}
              style={{
                padding: '8px 18px',
                fontSize: 13,
                fontWeight: 600,
                background:
                  text.trim().length === 0 || submitting
                    ? 'var(--v3-surface-elevated)'
                    : 'var(--v3-brand)',
                color:
                  text.trim().length === 0 || submitting
                    ? 'var(--v3-text-subtle)'
                    : 'var(--v3-text-on-brand)',
                border: 'none',
                borderRadius: 6,
                cursor: text.trim().length === 0 || submitting ? 'not-allowed' : 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {submitting ? 'Submitting…' : 'See what we’d build →'}
            </button>
          </div>
        </div>

        {/* Sample chips */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: 8,
            marginTop: 16,
          }}
        >
          {SAMPLE_PROMPTS.map((p) => (
            <button
              key={p}
              type='button'
              onClick={() => {
                setText(p);
                trackV3(V3_EVENTS.COMPOSER_SAMPLE_CHIP_CLICKED, {sample: p});
              }}
              style={{
                padding: '10px 12px',
                background: 'var(--v3-surface)',
                color: 'var(--v3-text-muted)',
                border: '1px solid var(--v3-border)',
                borderRadius: 6,
                fontSize: 12.5,
                cursor: 'pointer',
                textAlign: 'left',
                fontFamily: 'inherit',
              }}
            >
              {p}
            </button>
          ))}
        </div>

        {/* 2026-04-28 — anonymous-trial result UI removed. Submit now redirects
            straight to /login (with the typed spec stashed in sessionStorage
            for post-signup composer hydration). The "Trial ready · session
            xxx · Save this run — sign in" panel that used to render here is
            gone — that flow was the leak between the textarea and the
            conversion path. */}

        {error && (
          <div
            role='alert'
            style={{
              marginTop: 12,
              padding: '8px 12px',
              background: 'rgba(239,68,68,0.12)',
              border: '1px solid rgba(239,68,68,0.36)',
              color: '#FCA5A5',
              borderRadius: 6,
              fontSize: 12,
            }}
          >
            {error}
          </div>
        )}
      </form>

      {/* Selling-point #2 — verified-score live counter (anonymized aggregate) */}
      {stats && (stats.specsThisWeek > 0 || stats.totalRunsAllTime > 0) && (
        <VerifiedCounter stats={stats} />
      )}

      {/* R-202 (Wave 4) + R-313 (Wave 7) — "Trusted by" logo strip with
          the assertive proof line F2 ranked as P2 (unblocks Dana). The
          live ship-count from /v3/public/exec-snapshot rides above the
          placeholder logo grid; if the snapshot fetch fails or returns
          0 we fall back to the original honest placeholder copy. */}
      <TrustedByStrip snapshot={snapshot} />

      {/* R-204 (Wave 4) — Hero-video slot. Real 30s capture (spec → plan →
          build → PR) is a real-asset task that has to be recorded; until
          then HeroVideoSlot wraps the existing CSS-frame DemoAnimation as
          a fallback. When the .mp4 lands, drop it at `frontend/public/hero-demo.mp4`
          + flip the prop — see HeroVideoSlot below. */}
      <HeroVideoSlot />

      {/* Selling-point #7 — sample-run gallery (anonymized recent successful runs) */}
      {samples && samples.length > 0 && <SampleRunGallery rows={samples} />}

      {/* R-203 (Wave 4) — competitor positioning table. The narrow line
          ("ships PRs your team will merge") is more believable when paired
          with what each competitor actually does well, not against a strawman.
          Source: docs/strict-ux-thread-handoff.md + the matrix in
          /tmp/executespec-selling-conversion-points.md. */}
      <CompetitorTable />

      {/* Per-persona conversion CTAs — one card per buyer-type so each can
          self-route to the surface that closes their deal. */}
      <PersonaCtaStrip />

      {/* Footer */}
      <footer
        style={{
          marginTop: 'auto',
          padding: '32px 16px 16px',
          textAlign: 'center',
          fontSize: 11,
          color: 'var(--v3-text-subtle)',
        }}
      >
        Already have an account?{' '}
        <Link to='/login' style={{color: 'var(--v3-brand)', textDecoration: 'none'}}>
          Sign in
        </Link>{' '}
        ·{' '}
        <Link to='/home' style={{color: 'var(--v3-text-subtle)', textDecoration: 'none'}}>
          v3 home
        </Link>{' '}
        ·{' '}
        <Link to='/exec-demo' style={{color: 'var(--v3-text-subtle)', textDecoration: 'none'}}>
          See a team's last 30 days
        </Link>{' '}
        ·{' '}
        <Link to='/trust' style={{color: 'var(--v3-text-subtle)', textDecoration: 'none'}}>
          SOC 2 · DPA · DSAR
        </Link>
      </footer>
    </div>
  );
}

// ─── Verified-score counter (selling-point #2) ──────────────────────────────

function VerifiedCounter({stats}: {stats: V3LandingStats}) {
  return (
    <section
      aria-label='Platform proof'
      style={{
        margin: '12px auto 0',
        padding: '14px 18px',
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 10,
        display: 'flex',
        alignItems: 'center',
        gap: 18,
        flexWrap: 'wrap',
        justifyContent: 'center',
        maxWidth: 720,
        width: '100%',
      }}
    >
      <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 110}}>
        <span style={{fontSize: 22, fontWeight: 700, color: 'var(--v3-text)'}}>
          {stats.specsThisWeek.toLocaleString()}
        </span>
        <span style={{fontSize: 11, color: 'var(--v3-text-muted)'}}>specs shipped this week</span>
      </div>
      {stats.verifiedScorePercent != null && (
        <div
          style={{display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 110}}
        >
          <span style={{fontSize: 22, fontWeight: 700, color: 'var(--v3-brand)'}}>
            {stats.verifiedScorePercent}%
          </span>
          <span style={{fontSize: 11, color: 'var(--v3-text-muted)'}}>verified score ≥ 80</span>
        </div>
      )}
      <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', minWidth: 110}}>
        <span style={{fontSize: 22, fontWeight: 700, color: 'var(--v3-text)'}}>
          {stats.totalRunsAllTime.toLocaleString()}
        </span>
        <span style={{fontSize: 11, color: 'var(--v3-text-muted)'}}>total runs all-time</span>
      </div>
      <Link
        to='/exec-demo'
        style={{
          fontSize: 12,
          color: 'var(--v3-brand)',
          textDecoration: 'none',
          padding: '6px 12px',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 6,
        }}
      >
        See this week's runs →
      </Link>
    </section>
  );
}

// ─── Demo animation (selling-point #3 — placeholder for real video) ─────────

const DEMO_FRAMES = [
  {
    phase: 'Spec',
    line: 'Add a /healthz endpoint to my Spring Boot app',
    tone: 'var(--v3-text-muted)',
  },
  {
    phase: 'Plan',
    line: 'T1 · Add HealthController · T2 · Wire ManagementMetrics',
    tone: 'var(--v3-brand)',
  },
  {
    phase: 'Build',
    line: 'mvn -q compile · 0 errors · 47 source files · 6.2s',
    tone: 'var(--v3-brand)',
  },
  {phase: 'Test', line: '14/14 tests passed · reviewer verdict: CLEAN', tone: 'var(--v3-brand-2)'},
  {
    phase: 'PR opened',
    line: 'github.com/your-org/svc · #482 · ready for review',
    tone: 'var(--v3-brand-2)',
  },
];

// ─── R-204 (Wave 4) — Hero-video slot ──────────────────────────────────────
// Real 30-second hero video remains a real-asset task: someone has to
// record an actual run (spec → plan → build → tests → PR-opened), edit
// to ~30s, and drop the final .mp4 at `frontend/public/hero-demo.mp4`
// + a poster JPG at `frontend/public/hero-demo-poster.jpg`. Until that
// asset exists, this slot falls back to the CSS-frame DemoAnimation
// shipped in 4fa8ed63 — it's not video, but it gives the page motion
// + a phase-by-phase story for skim-readers in the meantime.
//
// To swap in the real video once the asset lands:
//   1. Place `hero-demo.mp4` (~30s, ≤8 MB, MP4/H.264) in frontend/public/
//   2. Place `hero-demo-poster.jpg` (1280×720, ≤200 KB) in frontend/public/
//   3. Flip USE_REAL_VIDEO below to true (one-line change)
//
// The poster image is what shows before play — even when autoplay is
// blocked (mobile data-saver, low-power mode, ad-blocker). Pick a frame
// that conveys "PR opened" so the static state itself is compelling.

const USE_REAL_VIDEO = false; // TODO(R-204): flip when hero-demo.mp4 ships

function HeroVideoSlot() {
  if (!USE_REAL_VIDEO) return <DemoAnimation />;
  return (
    <section
      aria-label='What a run looks like (30s demo)'
      style={{
        margin: '20px auto 0',
        maxWidth: 720,
        width: '100%',
      }}
    >
      <video
        src='/hero-demo.mp4'
        poster='/hero-demo-poster.jpg'
        autoPlay
        muted
        loop
        playsInline
        controls={false}
        style={{
          width: '100%',
          aspectRatio: '16 / 9',
          borderRadius: 10,
          border: '1px solid var(--v3-border-strong)',
          display: 'block',
          background: 'var(--v3-bg)',
        }}
      />
    </section>
  );
}

function DemoAnimation() {
  const [frame, setFrame] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setFrame((f) => (f + 1) % DEMO_FRAMES.length), 1800);
    return () => clearInterval(id);
  }, []);
  const f = DEMO_FRAMES[frame];
  return (
    <section
      aria-label='What a run looks like'
      style={{
        margin: '20px auto 0',
        maxWidth: 720,
        width: '100%',
        background: 'var(--v3-bg)',
        border: '1px solid var(--v3-border-strong)',
        borderRadius: 10,
        padding: '14px 16px',
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
        fontSize: 12.5,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 6, marginBottom: 10}}>
        {DEMO_FRAMES.map((d, i) => (
          <span
            key={d.phase}
            style={{
              fontSize: 10,
              padding: '2px 6px',
              borderRadius: 3,
              background: i === frame ? 'var(--v3-brand)' : 'var(--v3-surface)',
              color: i === frame ? 'var(--v3-text-on-brand)' : 'var(--v3-text-muted)',
              fontWeight: i === frame ? 600 : 400,
              transition: 'background 150ms ease',
            }}
          >
            {d.phase}
          </span>
        ))}
        <span style={{marginLeft: 'auto', fontSize: 10, color: 'var(--v3-text-subtle)'}}>
          live demo · loops every 1.8s
        </span>
      </div>
      <pre
        style={{
          margin: 0,
          color: f.tone,
          whiteSpace: 'pre-wrap',
          minHeight: 36,
          lineHeight: 1.55,
        }}
      >
        ▶ {f.line}
      </pre>
    </section>
  );
}

// ─── Sample-run gallery (selling-point #7) ──────────────────────────────────

function SampleRunGallery({rows}: {rows: V3SampleRun[]}) {
  return (
    <section
      aria-label='Sample runs'
      style={{
        margin: '24px auto 0',
        maxWidth: 960,
        width: '100%',
      }}
    >
      <h2
        style={{
          fontSize: 14,
          fontWeight: 600,
          color: 'var(--v3-text)',
          margin: '0 0 10px',
          textAlign: 'center',
        }}
      >
        What we shipped recently
      </h2>
      <p
        style={{
          fontSize: 12,
          color: 'var(--v3-text-muted)',
          margin: '0 0 14px',
          textAlign: 'center',
        }}
      >
        Anonymized — real runs from real users. Verdict + verified score from the same review
        pipeline yours will go through.
      </p>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
          gap: 10,
        }}
      >
        {rows.map((r, i) => (
          <SampleRunCard key={i} row={r} />
        ))}
      </div>
    </section>
  );
}

function SampleRunCard({row}: {row: V3SampleRun}) {
  const verdictTone =
    row.verdict === 'READY'
      ? 'var(--v3-brand-2)'
      : row.verdict === 'NEEDS_REVIEW'
        ? 'var(--v3-warning)'
        : '#FCA5A5';
  const verdictLabel =
    row.verdict === 'READY' ? 'Ready' : row.verdict === 'NEEDS_REVIEW' ? 'Needs review' : 'Failed';
  return (
    <div
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid var(--v3-border)',
        borderRadius: 8,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <div style={{display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap'}}>
        <span
          style={{
            fontSize: 10,
            padding: '2px 6px',
            borderRadius: 3,
            background: verdictTone + '20',
            color: verdictTone,
            fontWeight: 600,
            textTransform: 'uppercase',
            letterSpacing: 0.5,
          }}
        >
          {verdictLabel}
        </span>
        {row.verifiedScore != null && (
          <span style={{fontSize: 11, color: 'var(--v3-text-muted)'}}>
            Verified {row.verifiedScore}/100
          </span>
        )}
        {row.prOpened && (
          <span style={{fontSize: 10, color: 'var(--v3-text-subtle)'}}>· PR opened</span>
        )}
      </div>
      <p style={{margin: 0, fontSize: 13, color: 'var(--v3-text)', lineHeight: 1.45}}>{row.spec}</p>
      <div style={{marginTop: 'auto', fontSize: 10.5, color: 'var(--v3-text-subtle)'}}>
        {row.filesGenerated != null ? `${row.filesGenerated} files generated` : '—'}
        {row.durationMs != null && ` · ${Math.round(row.durationMs / 1000)}s`}
      </div>
    </div>
  );
}

// ─── R-202 (Wave 4) + R-313 (Wave 7) — "Trusted by" logo strip ─────────────
// Placeholder until real customer logos can be sourced. Honest framing: the
// strip explains what it'll become rather than fabricating logos. The
// "Add your logo here →" tail link routes to a brief 3-question form via
// mailto: so the marketing team can get a real social-proof acquisition
// pipeline going without waiting on a CRM build.
//
// R-313 (Wave 7) extension: when an exec snapshot is provided AND it
// shows non-zero ship volume, render an assertive headline above the
// logo grid ("X PRs shipped in the last N days · across Y projects").
// F2 P2 — Dana-blocking proof gap. The number is real + live; the logos
// remain placeholders until the founder swaps in real ones via the
// existing "Add your logo here" mailto path.

function TrustedByStrip({snapshot}: {snapshot?: V3ExecSnapshot | null}) {
  // Slot count — sized for desktop 5-up, wraps to 3 then 2 on smaller widths.
  const slots = ['Your logo', 'Your logo', 'Your logo', 'Your logo', 'Your logo'];
  // R-313 — show the assertive headline only when we actually have shipping
  // volume to claim. Zero-volume pre-launch state still gets the honest
  // placeholder strip; we don't fabricate "0 PRs shipped" as proof.
  const hasShipVolume = !!snapshot && snapshot.totalRuns > 0;
  return (
    <section
      aria-label='Trusted by — placeholder'
      style={{
        margin: '24px auto 0',
        maxWidth: 960,
        width: '100%',
        padding: '14px 16px',
        background: 'var(--v3-surface)',
        border: '1px dashed var(--v3-border)',
        borderRadius: 10,
      }}
    >
      {hasShipVolume && (
        <div
          style={{
            textAlign: 'center',
            marginBottom: 12,
            paddingBottom: 12,
            borderBottom: '1px dashed var(--v3-border)',
          }}
        >
          <div
            style={{
              fontSize: 18,
              fontWeight: 600,
              color: 'var(--v3-text)',
              lineHeight: 1.3,
            }}
          >
            <span
              style={{
                backgroundImage: 'var(--v3-pop-grad)',
                backgroundClip: 'text',
                WebkitBackgroundClip: 'text',
                color: 'transparent',
                WebkitTextFillColor: 'transparent',
                fontFamily: "'Instrument Serif', Georgia, serif",
                fontStyle: 'italic',
                fontWeight: 500,
                fontSize: 22,
              }}
            >
              {snapshot?.totalRuns.toLocaleString()}
            </span>{' '}
            PRs shipped in the last {snapshot?.windowDays} days
          </div>
          {snapshot?.activeProjects > 0 && (
            <div
              style={{
                fontSize: 11.5,
                color: 'var(--v3-text-subtle)',
                marginTop: 4,
                letterSpacing: 0.2,
              }}
            >
              across {snapshot?.activeProjects.toLocaleString()} active project
              {snapshot?.activeProjects === 1 ? '' : 's'}
              {snapshot?.successRatePercent !== null
                ? ` · ${Math.round(snapshot?.successRatePercent)}% reviewer-CLEAN rate`
                : ''}
            </div>
          )}
        </div>
      )}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          flexWrap: 'wrap',
        }}
      >
        <span
          style={{
            fontSize: 11,
            color: 'var(--v3-text-subtle)',
            textTransform: 'uppercase',
            letterSpacing: 0.8,
            fontWeight: 600,
            whiteSpace: 'nowrap',
          }}
        >
          Trusted by
        </span>
        <div
          style={{
            display: 'flex',
            gap: 14,
            alignItems: 'center',
            flexWrap: 'wrap',
            flex: 1,
            justifyContent: 'center',
          }}
        >
          {slots.map((label, i) => (
            <div
              key={i}
              aria-hidden='true'
              style={{
                width: 110,
                height: 26,
                borderRadius: 4,
                background: 'var(--v3-bg)',
                border: '1px solid var(--v3-border)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 10,
                color: 'var(--v3-text-subtle)',
                letterSpacing: 0.4,
              }}
            >
              {label}
            </div>
          ))}
        </div>
        <a
          href='mailto:admin@executespec.ai?subject=Trusted-by%20logo%20—%20%5Borg%20name%5D&body=We%27d%20like%20our%20logo%20on%20your%20landing.%20Org%3A%20%5Bname%5D.%20Logo%20file%3A%20%5Bsvg%2Fpng%20attached%5D.%20Use%20approval%3A%20%5Bcontact%20%2B%20OK%5D.'
          style={{
            fontSize: 11,
            color: 'var(--v3-brand)',
            textDecoration: 'none',
            whiteSpace: 'nowrap',
            padding: '4px 10px',
            border: '1px solid var(--v3-border)',
            borderRadius: 6,
          }}
        >
          Add your logo →
        </a>
      </div>
    </section>
  );
}

// ─── R-203 (Wave 4) — Competitor positioning table ─────────────────────────
// Four rows. Each row: competitor + what they win on + what ExecuteSpec
// adds. Honest framing — we don't beat Cursor on completion latency or
// Devin on autonomy mystique. We win on the merge step. The table makes
// that explicit so visitors can self-select.

const COMPETITOR_ROWS: {name: string; theyWin: string; weAdd: string}[] = [
  {
    name: 'Cursor',
    theyWin: 'Completion latency · IDE-native edits · individual flow',
    weAdd: 'Multi-file dispatch · multi-repo · validated PR with verified score',
  },
  {
    name: 'Devin',
    theyWin: 'Agent-autonomy story · enterprise mystique',
    weAdd: 'You see + approve the plan before code is written · pricing transparency',
  },
  {
    name: 'Bolt / v0 / Lovable',
    theyWin: 'Instant prototype in their sandbox · live preview',
    weAdd: 'Real PR to your existing repo · backend + multi-file · audit log',
  },
  {
    name: 'GitHub Copilot Workspaces',
    theyWin: 'Lives in GitHub UI · repo-graph access · Copilot bundle',
    weAdd: 'Multi-repo · multi-provider · explicit plan-approval gate',
  },
];

function CompetitorTable() {
  return (
    <section
      aria-label='Competitor positioning'
      style={{
        margin: '24px auto 0',
        maxWidth: 960,
        width: '100%',
      }}
    >
      <h2
        style={{
          fontSize: 14,
          fontWeight: 600,
          color: 'var(--v3-text)',
          margin: '0 0 4px',
          textAlign: 'center',
        }}
      >
        Where we fit
      </h2>
      <p
        style={{
          fontSize: 12,
          color: 'var(--v3-text-muted)',
          margin: '0 0 14px',
          textAlign: 'center',
        }}
      >
        Each competitor optimises for an earlier phase of the SDLC. We optimise for the merge.
      </p>
      <div
        role='table'
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border)',
          borderRadius: 10,
          overflow: 'hidden',
          fontSize: 12.5,
        }}
      >
        {/* Header row */}
        <div
          role='row'
          style={{
            display: 'grid',
            gridTemplateColumns: '160px 1fr 1fr',
            gap: 12,
            padding: '10px 14px',
            background: 'var(--v3-bg)',
            borderBottom: '1px solid var(--v3-border)',
            fontSize: 11,
            color: 'var(--v3-text-subtle)',
            textTransform: 'uppercase',
            letterSpacing: 0.6,
            fontWeight: 600,
          }}
        >
          <span>vs</span>
          <span>They win on</span>
          <span>What ExecuteSpec adds</span>
        </div>
        {COMPETITOR_ROWS.map((row, i) => (
          <div
            role='row'
            key={row.name}
            style={{
              display: 'grid',
              gridTemplateColumns: '160px 1fr 1fr',
              gap: 12,
              padding: '12px 14px',
              borderBottom: i < COMPETITOR_ROWS.length - 1 ? '1px solid var(--v3-border)' : 'none',
              alignItems: 'start',
            }}
          >
            <span style={{color: 'var(--v3-text)', fontWeight: 600}}>{row.name}</span>
            <span style={{color: 'var(--v3-text-muted)', lineHeight: 1.5}}>{row.theyWin}</span>
            <span style={{color: 'var(--v3-text)', lineHeight: 1.5}}>{row.weAdd}</span>
          </div>
        ))}
      </div>
      <p
        style={{
          fontSize: 11,
          color: 'var(--v3-text-subtle)',
          margin: '8px 0 0',
          textAlign: 'center',
        }}
      >
        Want the long version? Check{' '}
        <Link to='/exec-demo' style={{color: 'var(--v3-brand)', textDecoration: 'none'}}>
          live numbers
        </Link>
        .
      </p>
    </section>
  );
}

// ─── Per-persona CTA strip (selling-point #4 follow-up) ────────────────────

function PersonaCtaStrip() {
  const ctas: {title: string; body: string; href: string; cta: string}[] = [
    {
      title: 'For teams',
      body: 'Per-seat collab, BYOK, audit log, SSO. Bring your engineers into one project.',
      href: '/team',
      cta: 'See team features →',
    },
    {
      title: 'For enterprise',
      body: 'SOC 2 audit-in-progress, signed DPA, dedicated infra, custom MSA, SAML/OIDC.',
      href: '/trust',
      cta: 'Trust posture →',
    },
    {
      title: 'For PgM / CFO',
      body: "Watch a real team's last 30 days — spend by project, top wins, success rate.",
      href: '/exec-demo',
      cta: 'Public demo →',
    },
  ];
  return (
    <section
      aria-label='Conversion paths by persona'
      style={{
        margin: '24px auto 0',
        maxWidth: 960,
        width: '100%',
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))',
        gap: 10,
      }}
    >
      {ctas.map((c) => (
        <Link
          key={c.title}
          to={c.href}
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 8,
            padding: 14,
            textDecoration: 'none',
            color: 'var(--v3-text)',
            display: 'flex',
            flexDirection: 'column',
            gap: 6,
          }}
        >
          <span
            style={{
              fontSize: 11,
              color: 'var(--v3-text-subtle)',
              textTransform: 'uppercase',
              letterSpacing: 0.6,
            }}
          >
            {c.title}
          </span>
          <span style={{fontSize: 13.5, color: 'var(--v3-text)', lineHeight: 1.45}}>{c.body}</span>
          <span style={{marginTop: 'auto', fontSize: 12, color: 'var(--v3-brand)'}}>{c.cta}</span>
        </Link>
      ))}
    </section>
  );
}
