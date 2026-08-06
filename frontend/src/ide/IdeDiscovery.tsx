/* IDE-native analytics surface for /insights. */

import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {insightCards, outcomePercent, providerRows, topUserLabel} from './discoveryUtils';
import './surfaces/systemdiscovery.css';

// Version B: type comes from the `--ide-t-*` ramp via `.sysd-*` classes in
// surfaces/systemdiscovery.css, never a px literal in a style prop — the phone
// step-down at <=600px is attached to the tokens.

// Inline error note. Rule 2 — a failure is a 3px leading edge on a tinted
// note, not a card whose whole border is repainted red.
function ErrBanner({msg}: {msg?: string}) {
  if (!msg) return null;
  return (
    <div className='ide-note bad' style={{marginBottom: 12}}>
      {msg}
    </div>
  );
}

// Uppercase section label between cards (Signal's SectionHeader).
function SectionLabel({children}: {children: React.ReactNode}) {
  return (
    <div className='ide-h2' style={{marginTop: 18}}>
      {children}
    </div>
  );
}

// Key/value rows inside an ide-card (Signal's KvList).
function KvRows({pairs}: {pairs: Array<{k: React.ReactNode; v: React.ReactNode}>}) {
  return (
    <>
      {pairs.map((p, i) => (
        <div
          key={i}
          className='ide-kv'
          style={i === pairs.length - 1 ? {borderBottom: 'none'} : undefined}
        >
          <span>{p.k}</span>
          <span style={{color: 'var(--ide-fg-bright)', textAlign: 'right'}}>{p.v}</span>
        </div>
      ))}
    </>
  );
}

// Insights (analytics)
// Route /insights. api: getMetrics, getModelComparison, getUserRunsSummary.
export function IdeInsights() {
  const [days, setDays] = useState(30);
  const [metrics, setMetrics] = useState<api.MetricsResponse | null>(null);
  const [byProvider, setByProvider] = useState<api.ModelComparisonData | null>(null);
  const [summary, setSummary] = useState<api.UserRunsSummaryData | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    Promise.allSettled([
      api.getMetrics(days),
      api.getModelComparison(days),
      api.getUserRunsSummary(days),
    ]).then(([m, mc, s]) => {
      if (m.status === 'fulfilled') setMetrics(m.value);
      if (mc.status === 'fulfilled') setByProvider(mc.value);
      if (s.status === 'fulfilled') setSummary(s.value);
      if (m.status === 'rejected' && mc.status === 'rejected') setErr('Failed to load metrics.');
    });
  }, [days]);

  const insights = insightCards(metrics);
  const providers = providerRows(byProvider);
  const topUser = topUserLabel(summary);

  const RANGES: Array<{label: string; n: number}> = [
    {label: '30d', n: 30},
    {label: '90d', n: 90},
    {label: 'YTD', n: 365},
  ];
  const cards = [
    {label: 'Avg score', value: insights.avgScore},
    {label: 'Avg run time', value: insights.avgDuration},
    {label: 'Total spend', value: insights.spend},
    {
      label: 'Runs completed',
      value: (
        <>
          {insights.shipped} <small>of {insights.total}</small>
        </>
      ),
    },
  ];

  // A zero-run range used to collapse all three `flex: <count>` children to
  // zero width, painting an unexplained 18px void. Say "no runs" instead.
  const hasOutcomes = insights.shipped + insights.needs + insights.failed > 0;

  return (
    <div className='ide-pane'>
      <h1 className='ide-h1'>Insights</h1>
      <div className='ide-sub'>Last {days} days · all projects</div>

      {/* A three-control range switch must not stretch into a rule across the
          whole pane; `.sysd-range` sizes it to its content. */}
      <div className='ide-rtabs sysd-range'>
        {RANGES.map((r) => (
          <button
            key={r.n}
            type='button'
            className={`ide-rtab${days === r.n ? ' active' : ''}`}
            onClick={() => setDays(r.n)}
          >
            {r.label}
          </button>
        ))}
      </div>

      <ErrBanner msg={err} />

      <div className='sysd-metrics'>
        {cards.map((c) => (
          <div key={c.label} className='ide-card'>
            <div className='sysd-metric-label'>{c.label}</div>
            <div className='sysd-metric-value'>{c.value}</div>
          </div>
        ))}
      </div>

      <SectionLabel>Cost by provider</SectionLabel>
      <div className='ide-card'>
        {providers.length === 0 ? (
          <p className='sysd-note'>No provider data yet for this range.</p>
        ) : (
          <KvRows
            pairs={providers.map((p) => ({
              k: p.provider,
              v: p.value,
            }))}
          />
        )}
      </div>

      <SectionLabel>Run outcomes · {days}d</SectionLabel>
      <div className='ide-card'>
        {hasOutcomes ? (
          <div className='sysd-outcome'>
            <div style={{flex: insights.shipped, background: 'var(--ide-green)'}} />
            <div style={{flex: insights.needs, background: 'var(--ide-yellow)'}} />
            <div style={{flex: insights.failed, background: 'var(--ide-red)'}} />
          </div>
        ) : (
          <>
            <div className='sysd-outcome-empty' />
            <div className='sysd-outcome-none'>No runs in this range.</div>
          </>
        )}
        <KvRows
          pairs={[
            {
              k: (
                <>
                  <span className='sysd-swatch' style={{background: 'var(--ide-green)'}} />
                  Shipped
                </>
              ),
              v: `${insights.shipped} (${outcomePercent(insights.shipped, insights.total)}%)`,
            },
            {
              k: (
                <>
                  <span className='sysd-swatch' style={{background: 'var(--ide-yellow)'}} />
                  Needs review
                </>
              ),
              v: `${insights.needs} (${outcomePercent(insights.needs, insights.total)}%)`,
            },
            {
              k: (
                <>
                  <span className='sysd-swatch' style={{background: 'var(--ide-red)'}} />
                  Failed / cancelled
                </>
              ),
              v: `${insights.failed} (${outcomePercent(insights.failed, insights.total)}%)`,
            },
          ]}
        />
      </div>

      {topUser && (
        <>
          <SectionLabel>Most active user</SectionLabel>
          <div className='ide-card'>
            <div className='sysd-top-user'>{topUser.name}</div>
            <div className='sysd-top-user-meta'>
              {topUser.runCount} runs
              {topUser.successRate != null
                ? ` · ${Math.round(topUser.successRate * 100)}% success`
                : ''}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
