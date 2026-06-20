/* IDE-native analytics surface for /insights. */

import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {insightCards, outcomePercent, providerRows, topUserLabel} from './discoveryUtils';

// Inline error banner — red-bordered ide-card. Mirrors the Banner in IdeProjectMore.
function ErrBanner({msg}: {msg?: string}) {
  if (!msg) return null;
  return (
    <div
      className='ide-card'
      style={{
        marginBottom: 12,
        fontSize: 13,
        color: 'var(--ide-red)',
        borderColor: 'var(--ide-red)',
      }}
    >
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
          {insights.shipped}{' '}
          <span style={{color: 'var(--ide-fg-muted)', fontSize: 13}}>of {insights.total}</span>
        </>
      ),
    },
  ];

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Insights</div>
      <div className='ide-sub'>Last {days} days · all projects</div>

      <div className='ide-rtabs' style={{marginBottom: 12}}>
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

      <div className='ide-grid' style={{marginBottom: 4}}>
        {cards.map((c) => (
          <div key={c.label} className='ide-card'>
            <div
              style={{
                fontSize: 10,
                fontWeight: 700,
                color: 'var(--ide-fg-muted)',
                letterSpacing: '0.1em',
                textTransform: 'uppercase',
                marginBottom: 6,
              }}
            >
              {c.label}
            </div>
            <div style={{fontSize: 18, fontWeight: 600, color: 'var(--ide-fg-bright)'}}>
              {c.value}
            </div>
          </div>
        ))}
      </div>

      <SectionLabel>Cost by provider</SectionLabel>
      <div className='ide-card'>
        {providers.length === 0 ? (
          <p style={{fontSize: 13, color: 'var(--ide-fg-muted)', margin: 0}}>
            No provider data yet for this range.
          </p>
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
        <div
          style={{
            display: 'flex',
            gap: 4,
            height: 18,
            borderRadius: 999,
            overflow: 'hidden',
            marginBottom: 14,
          }}
        >
          <div style={{flex: insights.shipped, background: 'var(--ide-green)'}} />
          <div style={{flex: insights.needs, background: 'var(--ide-yellow)'}} />
          <div style={{flex: insights.failed, background: 'var(--ide-red)'}} />
        </div>
        <KvRows
          pairs={[
            {
              k: <span style={{color: 'var(--ide-green)'}}>● Shipped</span>,
              v: `${insights.shipped} (${outcomePercent(insights.shipped, insights.total)}%)`,
            },
            {
              k: <span style={{color: 'var(--ide-yellow)'}}>● Needs review</span>,
              v: `${insights.needs} (${outcomePercent(insights.needs, insights.total)}%)`,
            },
            {
              k: <span style={{color: 'var(--ide-red)'}}>● Failed / cancelled</span>,
              v: `${insights.failed} (${outcomePercent(insights.failed, insights.total)}%)`,
            },
          ]}
        />
      </div>

      {topUser && (
        <>
          <SectionLabel>Most active user</SectionLabel>
          <div className='ide-card'>
            <div style={{fontSize: 13.5, fontWeight: 500, color: 'var(--ide-fg-bright)'}}>
              {topUser.name}
            </div>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginTop: 4}}>
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
