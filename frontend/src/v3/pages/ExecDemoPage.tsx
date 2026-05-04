import {errMsg} from '@/lib/utils';
import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {getV3ExecSnapshot, type V3ExecSnapshot} from '../lib/api';

/**
 * Selling-point #6 — public anonymized /exec snapshot.
 *
 * Linear's "share-a-roadmap" pattern, applied to ExecuteSpec's strongest
 * in-app surface. Consumes {@code GET /api/v3/public/exec-snapshot} which
 * returns the last 30 days' platform aggregates with all identifiers
 * stripped (project names → "Project A / B / C", spec text truncated).
 *
 * Conversion role: PgM / CFO persona who can't reach /exec without paying.
 * Now they can. They share the link with their CTO, who sees a real
 * verified-score number alongside spend, and the procurement conversation
 * happens with a number on the table instead of a vibe.
 */
export default function ExecDemoPage() {
  const [snap, setSnap] = useState<V3ExecSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getV3ExecSnapshot()
      .then((s) => {
        if (!cancelled) setSnap(s);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(errMsg(e) ?? 'Failed to load snapshot');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return (
      <div style={{padding: 32, textAlign: 'center'}}>
        <h1 style={{fontSize: 22, color: 'var(--v3-text)', margin: '0 0 8px'}}>Demo unavailable</h1>
        <p style={{fontSize: 13, color: 'var(--v3-text-muted)'}}>{error}</p>
        <Link to='/' style={{color: 'var(--v3-brand)', fontSize: 12}}>
          ← Back to landing
        </Link>
      </div>
    );
  }

  if (!snap) {
    return (
      <div style={{padding: 32, textAlign: 'center', color: 'var(--v3-text-muted)'}}>
        Loading the last 30 days…
      </div>
    );
  }

  const maxRuns = Math.max(1, ...snap.spendByProject.map((p) => p.runs));

  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 18, paddingBottom: 48}}>
      <header>
        <p
          style={{
            fontSize: 11,
            color: 'var(--v3-brand)',
            textTransform: 'uppercase',
            letterSpacing: 0.8,
            margin: 0,
            fontWeight: 600,
          }}
        >
          Public demo · anonymized
        </p>
        <h1
          style={{
            fontSize: 26,
            fontWeight: 600,
            color: 'var(--v3-text)',
            margin: '6px 0 0',
            lineHeight: 1.2,
          }}
        >
          What ExecuteSpec shipped in the last {snap.windowDays} days.
        </h1>
        <p
          style={{fontSize: 13, color: 'var(--v3-text-muted)', margin: '6px 0 0', lineHeight: 1.55}}
        >
          Real platform aggregates — project names anonymized, spec text truncated. Same data your
          team would see at{' '}
          <Link to='/exec' style={{color: 'var(--v3-brand)', textDecoration: 'none'}}>
            /exec
          </Link>{' '}
          with their own runs.
        </p>
      </header>

      {/* KPI strip */}
      <section
        aria-label='Platform KPIs'
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
          gap: 10,
        }}
      >
        <Kpi label='Runs' value={snap.totalRuns.toLocaleString()} />
        <Kpi
          label='Verified ≥ 80'
          value={snap.successRatePercent != null ? `${snap.successRatePercent}%` : '—'}
          highlight
        />
        <Kpi
          label='Spend (USD)'
          value={snap.totalSpendUsd != null ? `$${snap.totalSpendUsd.toFixed(2)}` : '—'}
        />
        <Kpi label='Active projects' value={snap.activeProjects.toLocaleString()} />
        <Kpi label='Needs attention' value={snap.needsAttentionCount.toLocaleString()} />
      </section>

      {/* Spend by project */}
      {snap.spendByProject.length > 0 && (
        <section
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 10,
            padding: 16,
          }}
        >
          <h2 style={{fontSize: 14, fontWeight: 600, color: 'var(--v3-text)', margin: '0 0 12px'}}>
            Spend by project
          </h2>
          <ul
            style={{
              listStyle: 'none',
              margin: 0,
              padding: 0,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            {snap.spendByProject.map((p) => (
              <li key={p.anonymousName} style={{display: 'flex', alignItems: 'center', gap: 12}}>
                <span style={{minWidth: 90, fontSize: 12, color: 'var(--v3-text)'}}>
                  {p.anonymousName}
                </span>
                <div
                  style={{
                    flex: 1,
                    height: 6,
                    background: 'var(--v3-bg)',
                    borderRadius: 3,
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      width: `${(p.runs / maxRuns) * 100}%`,
                      height: '100%',
                      background: 'var(--v3-brand)',
                    }}
                  />
                </div>
                <span
                  style={{
                    minWidth: 60,
                    fontSize: 11,
                    color: 'var(--v3-text-muted)',
                    textAlign: 'right',
                  }}
                >
                  {p.runs} runs
                </span>
                <span
                  style={{
                    minWidth: 60,
                    fontSize: 11,
                    color: 'var(--v3-text-muted)',
                    textAlign: 'right',
                  }}
                >
                  {p.spendUsd != null ? `$${p.spendUsd.toFixed(2)}` : '—'}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Top wins */}
      {snap.topWins.length > 0 && (
        <section
          style={{
            background: 'var(--v3-surface)',
            border: '1px solid var(--v3-border)',
            borderRadius: 10,
            padding: 16,
          }}
        >
          <h2 style={{fontSize: 14, fontWeight: 600, color: 'var(--v3-text)', margin: '0 0 12px'}}>
            Top wins
          </h2>
          <ul
            style={{
              listStyle: 'none',
              margin: 0,
              padding: 0,
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            {snap.topWins.map((w, i) => (
              <li
                key={i}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '8px 10px',
                  background: 'var(--v3-bg)',
                  border: '1px solid var(--v3-border)',
                  borderRadius: 6,
                }}
              >
                <span
                  style={{
                    fontSize: 11,
                    fontWeight: 600,
                    padding: '2px 6px',
                    borderRadius: 3,
                    background: 'rgba(122,209,200,0.15)',
                    color: 'var(--v3-brand-2)',
                  }}
                >
                  {w.verifiedScore != null ? `${w.verifiedScore}/100` : '—'}
                </span>
                <span style={{flex: 1, fontSize: 13, color: 'var(--v3-text)'}}>{w.spec}</span>
                <span
                  style={{
                    fontSize: 11,
                    color: 'var(--v3-text-muted)',
                    minWidth: 60,
                    textAlign: 'right',
                  }}
                >
                  {w.costUsd != null ? `$${w.costUsd.toFixed(2)}` : '—'}
                </span>
                {w.when && (
                  <span
                    style={{
                      fontSize: 11,
                      color: 'var(--v3-text-subtle)',
                      minWidth: 80,
                      textAlign: 'right',
                    }}
                  >
                    {w.when}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* CTA back to landing */}
      <section
        style={{
          background: 'var(--v3-surface)',
          border: '1px solid var(--v3-border-strong)',
          borderRadius: 10,
          padding: 18,
          textAlign: 'center',
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
          alignItems: 'center',
        }}
      >
        <h2 style={{margin: 0, fontSize: 17, fontWeight: 600, color: 'var(--v3-text)'}}>
          See your team's numbers here.
        </h2>
        <p
          style={{
            margin: 0,
            fontSize: 13,
            color: 'var(--v3-text-muted)',
            maxWidth: 520,
            lineHeight: 1.55,
          }}
        >
          Sign up free — 10 runs / month, no card. Your verified-score and spend show up in your own
          /exec dashboard within minutes.
        </p>
        <div style={{display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'center'}}>
          <Link
            to='/'
            style={{
              padding: '8px 16px',
              fontSize: 13,
              fontWeight: 600,
              background: 'var(--v3-brand)',
              color: 'var(--v3-text-on-brand)',
              borderRadius: 6,
              textDecoration: 'none',
            }}
          >
            Try free →
          </Link>
          <Link
            to='/pricing'
            style={{
              padding: '8px 16px',
              fontSize: 13,
              background: 'transparent',
              color: 'var(--v3-text)',
              border: '1px solid var(--v3-border-strong)',
              borderRadius: 6,
              textDecoration: 'none',
            }}
          >
            See pricing
          </Link>
        </div>
      </section>
    </div>
  );
}

function Kpi({label, value, highlight}: {label: string; value: string; highlight?: boolean}) {
  return (
    <div
      style={{
        background: 'var(--v3-surface)',
        border: '1px solid ' + (highlight ? 'var(--v3-brand)' : 'var(--v3-border)'),
        borderRadius: 8,
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 4,
      }}
    >
      <span
        style={{
          fontSize: 10.5,
          color: 'var(--v3-text-muted)',
          textTransform: 'uppercase',
          letterSpacing: 0.6,
        }}
      >
        {label}
      </span>
      <span
        style={{
          fontSize: 22,
          fontWeight: 700,
          color: highlight ? 'var(--v3-brand)' : 'var(--v3-text)',
        }}
      >
        {value}
      </span>
    </div>
  );
}
