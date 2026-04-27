import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { submitAnonymousRun, type AnonymousRunResponse } from '../lib/api';
import { trackV3, V3_EVENTS } from '../lib/events';
import Pill from '../components/Pill';

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
 * in sessionStorage for the auth → /v3/p/... handoff.
 */

const SAMPLE_PROMPTS = [
  'Add OAuth login to my Next.js app',
  'Refactor a React component to TypeScript',
  'Build a Stripe checkout endpoint',
];

export default function V3Landing() {
  const [text, setText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<AnonymousRunResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (text.trim().length === 0 || submitting) return;
    setSubmitting(true);
    setError(null);
    trackV3(V3_EVENTS.LANDING_TEXTAREA_SUBMIT, { textLen: text.length });
    try {
      const resp = await submitAnonymousRun(text.trim());
      if (resp.error) {
        setError(resp.message);
        return;
      }
      setResult(resp);
      // Pre-stash the spec so the post-auth handoff can rehydrate it
      // into the project composer.
      try {
        sessionStorage.setItem('v3-anon-spec', text.trim());
        if (resp.sessionToken) sessionStorage.setItem('v3-anon-session', resp.sessionToken);
      } catch {
        /* private mode — best-effort */
      }
    } catch (e) {
      setError((e as Error)?.message ?? 'Something broke. Try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 'calc(100vh - 56px - 48px)' }}>
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
            fontSize: 32,
            fontWeight: 600,
            color: 'var(--v3-text)',
            textAlign: 'center',
            margin: 0,
            lineHeight: 1.2,
          }}
        >
          Ship AI pull requests
          <br />
          you can actually merge.
        </h1>
        <p
          style={{
            fontSize: 14,
            color: 'var(--v3-text-muted)',
            textAlign: 'center',
            maxWidth: 560,
            margin: 0,
            lineHeight: 1.55,
          }}
        >
          Cursor writes the code. Devin claims it works. ExecuteSpec hands you
          a PR your reviewer can approve in five minutes.
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
        aria-label="Anonymous trial composer"
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
            aria-label="What do you want to build"
            disabled={submitting || !!result}
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
            <span style={{ fontSize: 11.5, color: 'var(--v3-text-subtle)' }}>
              Free preview · no card · ~60s · sign up to ship the code
            </span>
            <button
              type="submit"
              disabled={text.trim().length === 0 || submitting || !!result}
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
                    : '#0B0D10',
                border: 'none',
                borderRadius: 6,
                cursor:
                  text.trim().length === 0 || submitting ? 'not-allowed' : 'pointer',
                fontFamily: 'inherit',
              }}
            >
              {submitting ? 'Submitting…' : 'See what we’d build →'}
            </button>
          </div>
        </div>

        {/* Sample chips */}
        {!result && (
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
                type="button"
                onClick={() => {
                  setText(p);
                  trackV3(V3_EVENTS.COMPOSER_SAMPLE_CHIP_CLICKED, { sample: p });
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
        )}

        {/* Result */}
        {result && (
          <div
            style={{
              marginTop: 16,
              background: 'var(--v3-surface)',
              border: '1px solid var(--v3-border-strong)',
              borderRadius: 8,
              padding: 14,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <Pill tone={result.status === 'STUB_COMPLETE' ? 'warn' : 'info'} dot={result.status !== 'STUB_COMPLETE'}>
                {result.status === 'STUB_COMPLETE' ? 'Trial ready' : 'Building'}
              </Pill>
              <span style={{ fontSize: 11, color: 'var(--v3-text-subtle)' }}>
                · session {result.sessionToken?.slice(0, 8)}
              </span>
            </div>
            <p style={{ fontSize: 13, color: 'var(--v3-text)', margin: '0 0 10px' }}>
              {result.message}
            </p>
            <button
              type="button"
              onClick={() => navigate('/v3/auth')}
              style={{
                padding: '6px 14px',
                fontSize: 13,
                fontWeight: 600,
                background: 'var(--v3-brand)',
                color: '#0B0D10',
                border: 'none',
                borderRadius: 6,
                cursor: 'pointer',
                fontFamily: 'inherit',
              }}
            >
              Save this run — sign in
            </button>
          </div>
        )}

        {error && (
          <div
            role="alert"
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
        <Link to="/login" style={{ color: 'var(--v3-brand)', textDecoration: 'none' }}>
          Sign in
        </Link>{' '}
        ·{' '}
        <Link to="/v3/home" style={{ color: 'var(--v3-text-subtle)', textDecoration: 'none' }}>
          v3 home
        </Link>
      </footer>
    </div>
  );
}
