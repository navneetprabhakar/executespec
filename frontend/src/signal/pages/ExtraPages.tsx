/* Additional Signal surfaces — Insights, Library, Migrations, Trust, Empty,
   Public pricing, Forgot/Reset password, Accept invite, Audit, Deployments,
   Spec templates. Bundled here because each is small. */

import {useEffect, useState} from 'react';
import {Link, useNavigate, useSearchParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {
  Feed,
  Row,
  Card,
  Button,
  Chip,
  StatusPill,
  TabsStrip,
  KvList,
} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {ConfirmModal, PromptModal} from '@/signal/components/Modal';
import {LiveHero, LiveHeroAction} from '@/signal/components/Compose';
import AuthLayout from '@/signal/components/AuthLayout';
import {Search, Plus} from '@/signal/icons/icons';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

// ──────────────────── Insights (analytics) ────────────────────
export function InsightsPage() {
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

  const mAny = (metrics || {}) as unknown as {
    totalRuns?: number;
    avgVerifiedScore?: number;
    avgDurationMs?: number;
    totalSpend?: number;
    shipped?: number;
    needs?: number;
    failed?: number;
  };
  const total = mAny.totalRuns ?? 0;
  const shipped = mAny.shipped ?? 0;
  const needs = mAny.needs ?? 0;
  const failed = mAny.failed ?? 0;
  // SDET 2026-05-16 (BUG-011) — distinguish "metrics not loaded yet" (loading skeleton state)
  // from "the answer is genuinely 0/0%/$0". Previously every empty showed "—" which made the
  // page look broken to FREE-tier users with 0 runs in the window. Now: while `metrics` is
  // still null (initial fetch in flight) we keep "—"; once it arrives, fall through to the
  // computed number even if it's zero. Same logic applied to avg-score and spend.
  const metricsLoaded = metrics != null;
  const avgScore =
    mAny.avgVerifiedScore != null
      ? `${mAny.avgVerifiedScore.toFixed(1)}%`
      : metricsLoaded
        ? '0%'
        : '—';
  const avgDuration = (() => {
    if (mAny.avgDurationMs != null) {
      const m = Math.floor(mAny.avgDurationMs / 60000);
      const s = Math.floor((mAny.avgDurationMs % 60000) / 1000);
      return `${m}m ${s}s`;
    }
    return metricsLoaded ? '0m 0s' : '—';
  })();
  const spend =
    mAny.totalSpend != null ? `$${mAny.totalSpend.toFixed(2)}` : metricsLoaded ? '$0.00' : '—';
  const mcAny = (byProvider || {}) as unknown as {
    byProvider?: Array<{provider?: string; spend?: number; cacheHitRate?: number}>;
  };
  const providers = mcAny.byProvider || [];
  const topUser = (summary?.users || [])[0] as unknown as
    | {
        firstName?: string;
        lastName?: string;
        email?: string;
        runCount?: number;
        successRate?: number;
      }
    | undefined;

  return (
    <>
      <TopBar title='Insights' sub={`Last ${days} days · all projects`} back backTo='/home' />
      <Feed>
        <TabsStrip
          items={[
            {label: '30d', active: days === 30, onClick: () => setDays(30)},
            {label: '90d', active: days === 90, onClick: () => setDays(90)},
            {label: 'YTD', active: days === 365, onClick: () => setDays(365)},
          ]}
        />

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        <div className='signal-quick-grid'>
          <div className='signal-quick-card mint'>
            <div className='icon-bg'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 16, height: 16}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
              >
                <path d='M22 11.08V12a10 10 0 1 1-5.93-9.14' />
                <polyline points='22 4 12 14.01 9 11.01' />
              </svg>
            </div>
            <h4>Avg score</h4>
            <div
              className='count'
              style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
            >
              {avgScore}
            </div>
          </div>
          <div className='signal-quick-card signal'>
            <div className='icon-bg'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 16, height: 16}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
              >
                <circle cx='12' cy='12' r='10' />
                <path d='M12 6v6l4 2' />
              </svg>
            </div>
            <h4>Avg run time</h4>
            <div
              className='count'
              style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
            >
              {avgDuration}
            </div>
          </div>
          <div className='signal-quick-card coral'>
            <div className='icon-bg'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 16, height: 16}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
              >
                <line x1='12' y1='1' x2='12' y2='23' />
                <path d='M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6' />
              </svg>
            </div>
            <h4>Total spend</h4>
            <div
              className='count'
              style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
            >
              {spend}
            </div>
          </div>
          <div className='signal-quick-card lavender'>
            <div className='icon-bg'>
              <svg
                viewBox='0 0 24 24'
                style={{width: 16, height: 16}}
                fill='none'
                stroke='currentColor'
                strokeWidth={2}
              >
                <path d='M9 11l3 3L22 4' />
              </svg>
            </div>
            {/*
             * SDET 2026-05-16 (BUG-012) — "Runs shipped: 0 / 5" was easily misread as
             * "5-run quota, 0 used" because the FREE tier's monthly cap is also 5. The metric
             * is actually "shipped (=completed with merged PR) / total runs submitted". Relabel
             * to "Runs completed" (matches the harness's COMPLETE status) and qualify with the
             * window so the user reads it as a metric, not a quota.
             */}
            <h4>Runs completed</h4>
            <div
              className='count'
              style={{color: 'var(--signal-ink)', fontSize: 17, fontWeight: 600}}
            >
              {shipped}{' '}
              <span style={{color: 'var(--signal-text-dim)', fontSize: 13}}>of {total}</span>
            </div>
          </div>
        </div>

        <SectionHeader>Cost by provider</SectionHeader>
        <Card>
          {providers.length === 0 ? (
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No provider data yet for this range.
            </p>
          ) : (
            <KvList
              pairs={providers.map((p) => ({
                k: p.provider ?? '—',
                v: `${p.spend != null ? `$${p.spend.toFixed(2)}` : '—'}${p.cacheHitRate != null ? ` (${Math.round(p.cacheHitRate * 100)}% cache)` : ''}`,
              }))}
            />
          )}
        </Card>

        <SectionHeader>Run outcomes · {days}d</SectionHeader>
        <Card>
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
            <div style={{flex: shipped, background: 'var(--signal-mint)'}} />
            <div style={{flex: needs, background: 'var(--signal-y)'}} />
            <div style={{flex: failed, background: 'var(--signal-coral)'}} />
          </div>
          <KvList
            pairs={[
              {
                k: <span style={{color: 'var(--signal-mint-text-on-light)'}}>● Shipped</span>,
                v: `${shipped} (${total > 0 ? Math.round((shipped / total) * 100) : 0}%)`,
              },
              {
                k: <span style={{color: 'var(--signal-y-text)'}}>● Needs review</span>,
                v: `${needs} (${total > 0 ? Math.round((needs / total) * 100) : 0}%)`,
              },
              {
                k: <span style={{color: 'var(--signal-coral-text)'}}>● Failed / cancelled</span>,
                v: `${failed} (${total > 0 ? Math.round((failed / total) * 100) : 0}%)`,
              },
            ]}
          />
        </Card>

        {topUser && (
          <>
            <SectionHeader>Most active user</SectionHeader>
            <Row
              dot='lavender'
              title={
                topUser.email ||
                `${topUser.firstName ?? ''} ${topUser.lastName ?? ''}`.trim() ||
                '—'
              }
              meta={
                <>
                  <span>{topUser.runCount ?? 0} runs</span>
                  {topUser.successRate != null && (
                    <>
                      <span>·</span>
                      <span>{Math.round(topUser.successRate * 100)}% success</span>
                    </>
                  )}
                </>
              }
              showChev
            />
          </>
        )}
      </Feed>
    </>
  );
}

// ──────────────────── Library (readymade solutions) ────────────────────
const LIBRARY_SOLUTIONS = [
  {
    slug: 'stripe-checkout',
    name: 'Stripe checkout',
    desc: 'Subscriptions + one-time + webhook signature verify',
    tags: ['payments', 'webhook'],
    time: '~6m',
    credits: 4,
    color: 'mint',
  },
  {
    slug: 'oauth-login',
    name: 'OAuth login',
    desc: 'Google + GitHub + email/password fallback',
    tags: ['auth', 'security'],
    time: '~4m',
    credits: 2,
    color: 'signal',
  },
  {
    slug: 'rag-pgvector',
    name: 'RAG with pgvector',
    desc: 'Tika extraction → embeddings → retrieval',
    tags: ['llm', 'postgres'],
    time: '~9m',
    credits: 6,
    color: 'coral',
  },
  {
    slug: 'github-actions',
    name: 'GitHub Actions CI',
    desc: 'Build · test · lint · containerize · push',
    tags: ['ci', 'docker'],
    time: '~3m',
    credits: 2,
    color: 'lavender',
  },
  {
    slug: 'docker-compose',
    name: 'Docker compose dev',
    desc: 'Postgres + Redis + app · hot reload',
    tags: ['infra'],
    time: '~2m',
    credits: 1,
    color: 'mint',
  },
  {
    slug: 'razorpay-checkout',
    name: 'Razorpay checkout',
    desc: 'India payments · UPI · cards · auth webhooks',
    tags: ['payments'],
    time: '~5m',
    credits: 3,
    color: 'signal',
  },
] as const;

export function LibraryPage() {
  return (
    <>
      <TopBar
        title='Library'
        sub='28 readymade solutions'
        back
        backTo='/home'
        actions={
          <IconButton to='/search' ariaLabel='Search'>
            <Search size={18} />
          </IconButton>
        }
      />
      <Feed>
        <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-y-text)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            How it works
          </div>
          <div style={{fontSize: 14, color: 'var(--signal-y-text)', lineHeight: 1.5}}>
            Pick a solution, edit the spec to match your stack, approve the plan. Average ~5 minutes
            from click to PR.
          </div>
        </Card>

        <TabsStrip
          items={[
            {label: 'All · 28', active: true},
            {label: 'Auth'},
            {label: 'Payments'},
            {label: 'LLM'},
            {label: 'CI / Infra'},
            {label: 'UI / Frontend'},
          ]}
        />

        <div className='signal-grid auto'>
          {LIBRARY_SOLUTIONS.map((s) => (
            <Card key={s.slug} to={`/library/${s.slug}`}>
              <div style={{display: 'flex', gap: 14, marginBottom: 12}}>
                <div
                  style={{
                    width: 40,
                    height: 40,
                    borderRadius: 10,
                    background:
                      s.color === 'mint'
                        ? 'var(--signal-mint-soft)'
                        : s.color === 'signal'
                          ? 'var(--signal-y-soft)'
                          : s.color === 'coral'
                            ? 'var(--signal-coral-soft)'
                            : 'var(--signal-lavender-soft)',
                    display: 'grid',
                    placeItems: 'center',
                    flexShrink: 0,
                  }}
                >
                  ⚡
                </div>
                <div style={{flex: 1, minWidth: 0}}>
                  <div style={{fontSize: 15, fontWeight: 600, marginBottom: 4}}>{s.name}</div>
                  <div style={{fontSize: 13, color: 'var(--signal-text-dim)', lineHeight: 1.4}}>
                    {s.desc}
                  </div>
                </div>
              </div>
              <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                {s.tags.map((t) => (
                  <Chip key={t}>{t}</Chip>
                ))}
              </div>
              <div
                style={{
                  display: 'flex',
                  gap: 10,
                  alignItems: 'center',
                  fontFamily: "'JetBrains Mono', monospace",
                  fontSize: 11,
                  color: 'var(--signal-text-mute)',
                }}
              >
                <span>{s.time}</span>
                <span>·</span>
                <span>{s.credits} credits</span>
              </div>
            </Card>
          ))}
        </div>
      </Feed>
    </>
  );
}

export function LibraryDetailPage() {
  return (
    <>
      <TopBar
        title='Stripe checkout'
        sub='Library solution · ~6m · 4 credits'
        back
        backTo='/library'
      />
      <Feed>
        <Card>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-text-mute)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 8,
            }}
          >
            What you'll get
          </div>
          <ul
            style={{
              listStyle: 'none',
              padding: 0,
              margin: 0,
              display: 'grid',
              gap: 8,
              fontSize: 14,
            }}
          >
            <li>✓ Subscription + one-time checkout endpoints</li>
            <li>✓ Webhook handler with signature verification</li>
            <li>✓ Customer + invoice persistence</li>
            <li>✓ Failed-payment retry + dunning email</li>
            <li>✓ Test mode toggle + sandbox keys</li>
            <li>✓ Smoke test + integration test suite</li>
          </ul>
        </Card>

        <SectionHeader>Tweak the spec before running</SectionHeader>
        <div className='signal-composer-card'>
          <div className='composer-h'>
            <h3>Pre-filled spec</h3>
            <span className='est'>est 4 credits</span>
          </div>
          <textarea
            defaultValue={`Add Stripe checkout to the API. Both subscription and one-time charge endpoints. Webhook handler at /webhooks/stripe with HMAC SHA-256 signature verification. Persist customer + invoice. Failed-payment retry with 3-attempt dunning. Add test-mode env var + sandbox keys. Tests must still pass.`}
          />
          <Link to='/plan' className='composer-submit'>
            Run this solution →
          </Link>
        </div>

        <SectionHeader>Used by · 1,247 orgs</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'Avg run time', v: '6m 12s'},
              {k: 'Avg verified score', v: '93%'},
              {k: 'PRs merged from this', v: '982 (78%)'},
              {k: 'Open source', v: 'github.com/executespec/library'},
            ]}
          />
        </Card>
      </Feed>
    </>
  );
}

// ──────────────────── Migrations ────────────────────
const MIGRATIONS = [
  {
    slug: 'js-to-ts',
    name: 'JavaScript → TypeScript',
    desc: 'Add types incrementally with strict mode pin',
    impact: 'high',
    time: '~15m / file',
  },
  {
    slug: 'express-fastify',
    name: 'Express → Fastify',
    desc: 'Per-route migration · Zod schemas · perf wins',
    impact: 'medium',
    time: '~8m / route',
  },
  {
    slug: 'mongoose-prisma',
    name: 'Mongoose → Prisma',
    desc: 'Schema · queries · migrations · Postgres adapter',
    impact: 'high',
    time: '~12m / model',
  },
  {
    slug: 'react-class-hooks',
    name: 'React classes → hooks',
    desc: 'Components, lifecycle → useEffect, context → providers',
    impact: 'medium',
    time: '~4m / cmp',
  },
  {
    slug: 'webpack-vite',
    name: 'Webpack → Vite',
    desc: 'Config, plugins, alias, env, build · dev-server',
    impact: 'high',
    time: '~30m',
  },
  {
    slug: 'pages-app-router',
    name: 'Pages → App Router',
    desc: 'Next.js routing · server components · loading',
    impact: 'high',
    time: '~10m / route',
  },
];

export function MigrationsPage() {
  return (
    <>
      <TopBar
        title='Migrations'
        sub='11 modernization recipes'
        back
        backTo='/home'
        actions={
          <IconButton to='/search' ariaLabel='Search'>
            <Search size={18} />
          </IconButton>
        }
      />
      <Feed>
        <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#5443a8',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            What's a migration?
          </div>
          <div style={{fontSize: 14, color: '#5443a8', lineHeight: 1.5}}>
            A multi-file refactor with parity tests. We run it incrementally — file by file, route
            by route — so your CI stays green throughout.
          </div>
        </Card>

        <div className='signal-grid cols-2'>
          {MIGRATIONS.map((m) => (
            <Card key={m.slug} to={`/migrations/${m.slug}`}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'baseline',
                  marginBottom: 6,
                }}
              >
                <div style={{fontSize: 15, fontWeight: 600}}>{m.name}</div>
                <StatusPill kind={m.impact === 'high' ? 'shipped' : 'needs'}>{m.impact}</StatusPill>
              </div>
              <div style={{fontSize: 13, color: 'var(--signal-text-dim)', marginBottom: 8}}>
                {m.desc}
              </div>
              <div
                style={{
                  fontFamily: "'JetBrains Mono', monospace",
                  fontSize: 11,
                  color: 'var(--signal-text-mute)',
                }}
              >
                {m.time}
              </div>
            </Card>
          ))}
        </div>
      </Feed>
    </>
  );
}

export function MigrationDetailPage() {
  return (
    <>
      <TopBar
        title='JavaScript → TypeScript'
        sub='Migration · incremental · per-file'
        back
        backTo='/migrations'
      />
      <Feed>
        <Card>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-text-mute)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 8,
            }}
          >
            How it runs
          </div>
          <ol style={{padding: '0 0 0 18px', margin: 0, display: 'grid', gap: 8, fontSize: 14}}>
            <li>
              Scan all{' '}
              <code className='mono' style={{fontSize: 12}}>
                .js
              </code>{' '}
              /{' '}
              <code className='mono' style={{fontSize: 12}}>
                .jsx
              </code>{' '}
              files
            </li>
            <li>Cluster by dependency graph; start at leaves</li>
            <li>For each file: rename, infer types, add explicit annotations on public APIs</li>
            <li>
              Run{' '}
              <code className='mono' style={{fontSize: 12}}>
                tsc --noEmit
              </code>{' '}
              after every cluster
            </li>
            <li>Open one PR per cluster (~30 files), CI-green</li>
          </ol>
        </Card>

        <SectionHeader>Configure</SectionHeader>
        <div className='signal-composer-card'>
          <KvList
            pairs={[
              {k: 'Target repo', v: 'frontend'},
              {k: 'Strictness', v: '--strict + noImplicitAny'},
              {k: 'PR size cap', v: '30 files / PR'},
              {k: 'Test command', v: 'npm test'},
              {k: 'Est total cost', v: '$8.40'},
              {k: 'Est total time', v: '~3 hours'},
            ]}
          />
          <Link to='/plan' className='composer-submit' style={{marginTop: 16}}>
            Kick off migration →
          </Link>
        </div>

        <Card style={{background: 'var(--signal-coral-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-coral-text)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            Heads up
          </div>
          <div style={{fontSize: 13.5, color: 'var(--signal-coral-text)', lineHeight: 1.5}}>
            We pause at every plan gate. You're never further than one approval away from the next
            batch.
          </div>
        </Card>
      </Feed>
    </>
  );
}

// ──────────────────── Trust (public security page) ────────────────────
export function TrustPage() {
  return (
    <>
      <TopBar title='Trust & security' sub='SOC 2 · DPA · GDPR' back backTo='/home' />
      <Feed>
        <div className='signal-quick-grid'>
          {[
            {label: 'SOC 2 Type II', v: 'Ready', tone: 'mint'},
            {label: 'DPA available', v: 'Yes', tone: 'mint'},
            {label: 'SSO + SCIM', v: 'TEAM+', tone: 'signal'},
            {label: 'GDPR export', v: 'Built-in', tone: 'mint'},
          ].map((b) => (
            <div key={b.label} className={`signal-quick-card ${b.tone}`}>
              <div className='icon-bg'>✓</div>
              <h4>{b.label}</h4>
              <div
                className='count'
                style={{color: 'var(--signal-ink)', fontSize: 15, fontWeight: 600}}
              >
                {b.v}
              </div>
            </div>
          ))}
        </div>

        <SectionHeader>What we encrypt</SectionHeader>
        <Card>
          <KvList
            pairs={[
              {k: 'At rest', v: 'AES-256 (Cloud SQL)'},
              {k: 'In transit', v: 'TLS 1.3'},
              {k: 'API keys (BYOK)', v: 'KMS-wrapped'},
              {k: 'Source code', v: 'Workspace-local · cleared at run end'},
              {k: 'Backups', v: 'Daily · 30d retention · encrypted'},
            ]}
          />
        </Card>

        <SectionHeader>What we never do</SectionHeader>
        <Card>
          <ul style={{padding: '0 0 0 18px', margin: 0, fontSize: 14, lineHeight: 1.7}}>
            <li>Train any model on your source code</li>
            <li>Retain your code outside the run workspace</li>
            <li>Share your data with third parties beyond the LLM provider you picked</li>
            <li>Read your code without the run-scoped JWT proving you authorized it</li>
          </ul>
        </Card>

        <SectionHeader>Documents</SectionHeader>
        <Row title='SOC 2 Type II report' meta={<span>NDA required</span>} showChev />
        <Row title='Data Processing Addendum (DPA)' meta={<span>Standard MSAs</span>} showChev />
        <Row
          title='Sub-processors list'
          meta={<span>GCP · Anthropic · OpenAI · Google AI</span>}
          showChev
        />
        <Row title='Penetration test report · 2026' meta={<span>NDA required</span>} showChev />
        <Row title='Privacy policy' meta={<span>last updated 2026-03-14</span>} showChev />
      </Feed>
    </>
  );
}

// ──────────────────── Empty project (FREE / no repos yet) ────────────────────
export function EmptyProjectPage() {
  return (
    <>
      <TopBar title='Get started' sub="Let's get your first PR shipped" back backTo='/home' />
      <Feed>
        <Card
          style={{
            background: 'var(--signal-mint-soft)',
            borderColor: 'transparent',
            textAlign: 'center',
            padding: 28,
          }}
        >
          <div style={{fontSize: 38, marginBottom: 8}}>🚀</div>
          <div
            style={{
              fontSize: 18,
              fontWeight: 700,
              color: 'var(--signal-mint-text-on-light)',
              marginBottom: 6,
            }}
          >
            You don't have any projects yet
          </div>
          <div
            style={{
              fontSize: 13.5,
              color: 'var(--signal-mint-text-on-light)',
              maxWidth: 280,
              margin: '0 auto',
              lineHeight: 1.5,
            }}
          >
            Link a repo or pick a sandbox to see ExecuteSpec in action. ~3 minutes to your first
            verified PR.
          </div>
        </Card>

        <SectionHeader>Path 1 · Link a GitHub repo</SectionHeader>
        <Card>
          <div
            style={{
              fontSize: 14,
              color: 'var(--signal-text-dim)',
              marginBottom: 12,
              lineHeight: 1.5,
            }}
          >
            Bring an existing codebase. We'll install a webhook, snapshot the architecture, and you
            can run specs against it immediately.
          </div>
          <Button kind='primary' block to='/integrations'>
            <svg viewBox='0 0 24 24' style={{width: 16, height: 16}}>
              <path
                fill='currentColor'
                d='M12 .5C5.65.5.5 5.65.5 12c0 5.1 3.3 9.4 7.88 10.92.58.1.78-.25.78-.55 0-.27-.01-1-.02-1.96-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.68-1.28-1.68-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.55-.29-5.24-1.28-5.24-5.7 0-1.26.45-2.29 1.19-3.09-.12-.3-.52-1.46.1-3.04 0 0 .97-.31 3.18 1.18A11.07 11.07 0 0 1 12 6.8c.98 0 1.98.13 2.91.38 2.2-1.5 3.18-1.18 3.18-1.18.63 1.58.23 2.74.11 3.04.74.8 1.19 1.83 1.19 3.09 0 4.42-2.7 5.4-5.26 5.69.41.36.78 1.06.78 2.13 0 1.54-.01 2.78-.01 3.16 0 .3.2.66.79.55C20.2 21.4 23.5 17.1 23.5 12 23.5 5.65 18.35.5 12 .5z'
              />
            </svg>
            Connect GitHub
          </Button>
        </Card>

        <SectionHeader>Path 2 · Try a sandbox</SectionHeader>
        <Card>
          <div
            style={{
              fontSize: 14,
              color: 'var(--signal-text-dim)',
              marginBottom: 12,
              lineHeight: 1.5,
            }}
          >
            We'll spin up a brand-new repo with one of three preset specs. Free, no commits to your
            repos.
          </div>
          <Button kind='secondary' block to='/welcome'>
            Pick a starter →
          </Button>
        </Card>

        <SectionHeader>Path 3 · Browse the library</SectionHeader>
        <Card>
          <div
            style={{
              fontSize: 14,
              color: 'var(--signal-text-dim)',
              marginBottom: 12,
              lineHeight: 1.5,
            }}
          >
            28 readymade solutions (Stripe, OAuth, RAG, …) and 11 migrations (JS→TS,
            Express→Fastify, …) — each pre-tuned to ship to a real repo.
          </div>
          <Button kind='secondary' block to='/library'>
            Open library →
          </Button>
        </Card>
      </Feed>
    </>
  );
}

// ──────────────────── Public pricing ────────────────────
export function PublicPricingPage() {
  return (
    <>
      <TopBar title='Pricing' sub='Start free · upgrade when you need to' back backTo='/login' />
      <Feed>
        <TabsStrip items={[{label: 'Monthly'}, {label: 'Yearly · save 20%', active: true}]} />

        <div className='signal-grid cols-2 cols-4-xl'>
          {[
            {
              tier: 'FREE',
              price: '$0',
              sub: 'No card required',
              features: [
                '5 runs / month',
                '1-credit specs only',
                '$3 hard cost cap',
                'Single repo',
                'Email support',
              ],
            },
            {
              tier: 'SOLO',
              price: '$29',
              unit: '/mo',
              sub: 'Indie devs · single workspace',
              features: [
                '100 runs / month',
                '200 credits / month',
                '$10 hard cap',
                'Multi-repo',
                '1 BYOK provider',
                'Library + migrations',
              ],
            },
            {
              tier: 'TEAM',
              price: '$31',
              unit: '/seat/mo',
              sub: 'Min 2 seats · yearly',
              features: [
                'Unlimited runs',
                '500 credits / seat / month',
                '$25 hard cap',
                'BYOK · 3 providers',
                'Cross-repo + sharing',
                'All autofix surfaces',
              ],
              best: true,
            },
            {
              tier: 'ENTERPRISE',
              price: 'Custom',
              sub: 'SSO · SLA · dedicated CSM',
              features: [
                'Unlimited credits',
                'No cost cap',
                'Standards · Contracts · ADRs',
                'SSO + SCIM',
                '99.9% SLA',
                'Dedicated CSM',
              ],
            },
          ].map((p) => (
            <Card
              key={p.tier}
              style={{position: 'relative', borderColor: p.best ? 'var(--signal-ink)' : undefined}}
            >
              {p.best && (
                <div
                  style={{
                    position: 'absolute',
                    top: -10,
                    right: 16,
                    background: 'var(--signal-y)',
                    color: 'var(--signal-y-text)',
                    fontSize: 10,
                    fontWeight: 700,
                    padding: '3px 9px',
                    borderRadius: 999,
                  }}
                >
                  RECOMMENDED
                </div>
              )}
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'baseline',
                  marginBottom: 6,
                }}
              >
                <div style={{fontSize: 16, fontWeight: 700}}>{p.tier}</div>
                <div>
                  <span style={{fontSize: 22, fontWeight: 700, letterSpacing: '-0.02em'}}>
                    {p.price}
                  </span>
                  {p.unit && (
                    <span style={{fontSize: 13, color: 'var(--signal-text-dim)', fontWeight: 400}}>
                      {p.unit}
                    </span>
                  )}
                </div>
              </div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginBottom: 14}}>
                {p.sub}
              </div>
              <ul
                style={{
                  listStyle: 'none',
                  padding: 0,
                  margin: 0,
                  display: 'grid',
                  gap: 8,
                  fontSize: 13.5,
                }}
              >
                {p.features.map((f) => (
                  <li key={f}>✓ {f}</li>
                ))}
              </ul>
              <Button kind={p.best ? 'primary' : 'secondary'} block style={{marginTop: 14}}>
                {p.tier === 'ENTERPRISE'
                  ? 'Talk to sales'
                  : p.tier === 'FREE'
                    ? 'Start free'
                    : 'Start trial'}
              </Button>
            </Card>
          ))}
        </div>
      </Feed>
    </>
  );
}

function FormError({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-coral-soft)',
        color: 'var(--signal-coral-text)',
        border: '1px solid var(--signal-coral)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}
function FormSuccess({msg}: {msg: string}) {
  if (!msg) return null;
  return (
    <div
      style={{
        background: 'var(--signal-mint-soft)',
        color: 'var(--signal-mint-text-on-light)',
        border: '1px solid var(--signal-mint)',
        borderRadius: 10,
        padding: '10px 14px',
        marginBottom: 12,
        fontSize: 13,
      }}
    >
      {msg}
    </div>
  );
}

// ──────────────────── Forgot password ────────────────────
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [ok, setOk] = useState('');
  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    setOk('');
    setBusy(true);
    try {
      await api.authForgotPassword(email);
      setOk('Reset link sent. Check your inbox (and spam folder).');
      setEmail('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to send the reset link.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <div className='logo'>E</div>
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>
          Forgot your <em>password?</em>
        </h1>
        <p className='lede'>
          Tell us your email and we'll send you a reset link. The link is good for one hour.
        </p>
        <FormError msg={err} />
        <FormSuccess msg={ok} />
        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>Email</label>
            <input
              type='email'
              autoComplete='email'
              placeholder='you@company.com'
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Sending…' : 'Send reset link'}
          </Button>
        </form>
        <div className='footer-link'>
          Remembered it? <Link to='/login'>Sign in</Link>
        </div>
      </div>
    </AuthLayout>
  );
}

// ──────────────────── Reset password ────────────────────
export function ResetPasswordPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [pw, setPw] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErr('');
    if (!token) {
      setErr('No reset token in the URL. Click the link in your email again.');
      return;
    }
    if (pw !== confirm) {
      setErr("Passwords don't match.");
      return;
    }
    if (pw.length < 12) {
      setErr('Password must be at least 12 characters.');
      return;
    }
    setBusy(true);
    try {
      await api.authResetPassword(token, pw);
      navigate('/login?reset=1', {replace: true});
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Reset failed. Request a fresh link.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <div className='logo'>E</div>
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>
          Pick a new <em>password.</em>
        </h1>
        <p className='lede'>12+ characters. One number, one symbol. Anything else goes.</p>
        <FormError msg={err} />
        <form onSubmit={onSubmit}>
          <div className='signal-field'>
            <label>New password</label>
            <input
              type='password'
              autoComplete='new-password'
              placeholder='••••••••'
              value={pw}
              onChange={(e) => setPw(e.target.value)}
              minLength={12}
              required
            />
          </div>
          <div className='signal-field'>
            <label>Confirm</label>
            <input
              type='password'
              autoComplete='new-password'
              placeholder='••••••••'
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              required
            />
          </div>
          <Button kind='primary' block type='submit' disabled={busy}>
            {busy ? 'Resetting…' : 'Reset password'}
          </Button>
        </form>
      </div>
    </AuthLayout>
  );
}

// ──────────────────── Accept invite ────────────────────
export function AcceptInvitePage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const token = params.get('token') || '';
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  async function accept() {
    if (!token) {
      setErr('No invite token in the URL. Use the link from your invite email.');
      return;
    }
    setErr('');
    setBusy(true);
    try {
      const r = await api.acceptProjectInvite(token);
      navigate(r?.projectId ? `/projects/${r.projectId}` : '/home', {replace: true});
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Could not accept this invite.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <AuthLayout>
      <div className='signal-splash'>
        <div className='brand'>
          <div className='logo'>E</div>
          <div style={{fontSize: 16, fontWeight: 600}}>ExecuteSpec</div>
        </div>
        <h1>
          You've been <em>invited</em>.
        </h1>
        <p className='lede'>
          Open this page from your email invite link. We'll add you to the project and take you
          straight there.
        </p>
        <FormError msg={err} />
        <Card style={{marginBottom: 18}}>
          <KvList
            pairs={[
              {
                k: 'Invite token',
                v: token ? (
                  token.slice(0, 12) + '…'
                ) : (
                  <span style={{color: 'var(--signal-coral-text)'}}>(missing)</span>
                ),
              },
              {
                k: 'Status',
                v: token ? (
                  'Ready to accept'
                ) : (
                  <span style={{color: 'var(--signal-coral-text)'}}>
                    Open the link in your email
                  </span>
                ),
              },
            ]}
          />
        </Card>
        <Button kind='mint' block onClick={accept} disabled={busy || !token}>
          {busy ? 'Accepting…' : 'Accept & open project'}
        </Button>
        <Button kind='ghost' block to='/home' style={{color: 'var(--signal-coral-text)'}}>
          Decline invite
        </Button>
      </div>
    </AuthLayout>
  );
}

// ──────────────────── Audit log (org-scoped) ────────────────────
export function AuditLogPage() {
  const tier = useAuthStore(selectNormalizedTier);
  const user = useAuthStore((s) => s.user);
  const isOrgAdmin = user?.orgRole === 'ORG_ADMIN' || user?.globalRole === 'SUPER_ADMIN';
  const [logs, setLogs] = useState<api.AuditLogEntry[] | null>(null);
  const [filter, setFilter] = useState<'all' | 'auth' | 'billing' | 'members' | 'admin'>('all');
  const [err, setErr] = useState('');

  useEffect(() => {
    let cancelled = false;
    api
      .getAuditLogs({page: 0, size: 100})
      .then((p) => {
        if (cancelled) return;
        setLogs((p as unknown as {content?: api.AuditLogEntry[]}).content ?? []);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        // Tailor the 403 message to what the caller can actually do about it:
        //   - FREE/PRO tier  → upsell, audit is a TEAM+ feature
        //   - TEAM+ but not ORG_ADMIN → "ask your org admin"
        //   - TEAM+ ORG_ADMIN → org-level flag isn't on yet; link them to
        //     Org Settings where they can enable it (or contact support if
        //     that page doesn't expose the toggle on their tier).
        const msg = e instanceof Error ? e.message : 'Failed to load audit log.';
        if (/403|permission|forbidden/i.test(msg)) {
          if (tier === 'FREE' || tier === 'PRO') {
            setErr('Audit log is a TEAM+ feature. Upgrade your plan to enable it.');
          } else if (!isOrgAdmin) {
            setErr(
              'Audit log is restricted to org admins. Ask your org admin to grant you access.',
            );
          } else {
            setErr(
              "Audit log isn't enabled for your org yet. Open Org Settings → Compliance to turn it on, or contact support if the toggle isn't visible.",
            );
          }
        } else {
          setErr(msg);
        }
        setLogs([]); // exit the null/loading state so the empty UI renders
      });
    return () => {
      cancelled = true;
    };
  }, [tier, isOrgAdmin]);

  const filtered = (logs || []).filter((l) => {
    if (filter === 'all') return true;
    const t = (
      (l as unknown as {action?: string; eventType?: string}).action ||
      (l as unknown as {eventType?: string}).eventType ||
      ''
    ).toLowerCase();
    if (filter === 'auth')
      return (
        t.includes('login') || t.includes('oauth') || t.includes('logout') || t.includes('password')
      );
    if (filter === 'billing')
      return (
        t.includes('billing') ||
        t.includes('subscription') ||
        t.includes('payment') ||
        t.includes('invoice')
      );
    if (filter === 'members')
      return t.includes('member') || t.includes('invite') || t.includes('seat');
    if (filter === 'admin') return t.includes('admin') || t.includes('tier') || t.includes('flag');
    return true;
  });

  function exportCsv() {
    const rows = ['action,actor,target,createdAt'];
    for (const l of filtered) {
      const lAny = l as unknown as {
        action?: string;
        eventType?: string;
        actorEmail?: string;
        targetId?: string;
        createdAt?: string;
        occurredAt?: string;
      };
      rows.push(
        `${lAny.action || lAny.eventType || ''},${lAny.actorEmail || ''},${lAny.targetId || ''},${lAny.createdAt || lAny.occurredAt || ''}`,
      );
    }
    const blob = new Blob([rows.join('\n')], {type: 'text/csv'});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `audit-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }

  return (
    <>
      <TopBar
        title='Audit log'
        sub={`${logs?.length ?? 0} events`}
        back
        backTo='/account'
        actions={
          <IconButton ariaLabel='Export' onClick={exportCsv}>
            <svg
              viewBox='0 0 24 24'
              style={{width: 18, height: 18}}
              fill='none'
              stroke='currentColor'
              strokeWidth={2}
            >
              <path d='M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3' />
            </svg>
          </IconButton>
        }
      />
      <Feed>
        <TabsStrip
          items={[
            {
              label: `All · ${logs?.length ?? 0}`,
              active: filter === 'all',
              onClick: () => setFilter('all'),
            },
            {label: 'Auth', active: filter === 'auth', onClick: () => setFilter('auth')},
            {label: 'Billing', active: filter === 'billing', onClick: () => setFilter('billing')},
            {label: 'Members', active: filter === 'members', onClick: () => setFilter('members')},
            {label: 'Admin', active: filter === 'admin', onClick: () => setFilter('admin')},
          ]}
        />

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        {logs === null ? (
          <LoadingState label='Loading audit log…' />
        ) : filtered.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No audit events for this filter.
            </p>
          </Card>
        ) : (
          filtered.map((l, i) => {
            const lAny = l as unknown as {
              id?: string;
              action?: string;
              eventType?: string;
              actorEmail?: string;
              actorUserId?: string;
              targetId?: string;
              createdAt?: string;
              occurredAt?: string;
              description?: string;
            };
            const action = lAny.action || lAny.eventType || 'event';
            const ts = lAny.createdAt || lAny.occurredAt;
            return (
              <Row
                key={lAny.id || `${i}-${ts}`}
                dot='lavender'
                title={action}
                meta={
                  <>
                    {(lAny.actorEmail || lAny.actorUserId) && (
                      <span className='mono'>{lAny.actorEmail || lAny.actorUserId}</span>
                    )}
                    {lAny.targetId && (
                      <>
                        <span>·</span>
                        <span>{lAny.targetId}</span>
                      </>
                    )}
                  </>
                }
                trailing=''
                trailingLabel={ts ? new Date(ts).toLocaleString() : '—'}
              />
            );
          })
        )}

        <Button kind='secondary' block style={{marginTop: 14}} onClick={exportCsv}>
          Export filtered as CSV
        </Button>
      </Feed>
    </>
  );
}

// ──────────────────── Deployments ────────────────────
export function DeploymentsPage() {
  const [items, setItems] = useState<api.DeploymentSummary[] | null>(null);
  const [err, setErr] = useState('');
  const [confirmRollback, setConfirmRollback] = useState<{id: string; name: string} | null>(null);
  const [confirmDestroy, setConfirmDestroy] = useState<{id: string; name: string} | null>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);

  useEffect(() => {
    api
      .getDeployments()
      .then(setItems)
      .catch((e: unknown) => {
        setErr(e instanceof Error ? e.message : 'Failed to load deployments.');
      });
  }, []);

  async function performRollback(id: string) {
    setConfirmBusy(true);
    try {
      await api.rollbackDeployment(id);
      setItems(await api.getDeployments());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Rollback failed.');
    } finally {
      setConfirmBusy(false);
      setConfirmRollback(null);
    }
  }

  async function performRemove(id: string) {
    setConfirmBusy(true);
    try {
      await api.deleteDeployment(id);
      setItems(await api.getDeployments());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    } finally {
      setConfirmBusy(false);
      setConfirmDestroy(null);
    }
  }

  return (
    <>
      <TopBar title='Deployments' sub={`${items?.length ?? 0} total`} back backTo='/home' />
      <Feed>
        <Card style={{background: 'var(--signal-y-soft)', borderColor: 'transparent'}}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: 'var(--signal-y-text)',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              marginBottom: 6,
            }}
          >
            How previews work
          </div>
          <div style={{fontSize: 14, color: 'var(--signal-y-text)', lineHeight: 1.5}}>
            Every shipped run gets a free 5-minute preview on Cloud Run. Promote to permanent from a
            completed run.
          </div>
        </Card>

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginTop: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        <SectionHeader>Deployments</SectionHeader>
        {items === null ? (
          <LoadingState label='Loading deployments…' />
        ) : items.length === 0 ? (
          <Card>
            <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
              No deployments yet. Run a spec to ship one.
            </p>
          </Card>
        ) : (
          items.map((d) => {
            const dAny = d as unknown as {
              id?: string;
              status?: string;
              url?: string;
              createdAt?: string;
              runId?: string;
              name?: string;
              environment?: string;
            };
            const status = (dAny.status || 'UNKNOWN').toUpperCase();
            const dot: 'mint' | 'live' | 'coral' | 'mute' =
              status === 'LIVE' || status === 'RUNNING'
                ? 'live'
                : status === 'HEALTHY'
                  ? 'mint'
                  : status === 'FAILED'
                    ? 'coral'
                    : 'mute';
            return (
              <Row
                key={dAny.id}
                dot={dot}
                title={dAny.name || `Deployment ${dAny.id?.slice(0, 8)}`}
                meta={
                  <>
                    {dAny.url && (
                      <span className='mono'>{dAny.url.replace(/^https?:\/\//, '')}</span>
                    )}
                    {dAny.environment && (
                      <>
                        <span>·</span>
                        <span>{dAny.environment}</span>
                      </>
                    )}
                  </>
                }
                trailing={
                  <div style={{display: 'flex', gap: 4}}>
                    {dAny.url && (
                      <Button
                        kind='secondary'
                        sm
                        onClick={() => window.open(dAny.url!, '_blank', 'noopener')}
                      >
                        Open
                      </Button>
                    )}
                    <Button
                      kind='ghost'
                      sm
                      onClick={() =>
                        dAny.id &&
                        setConfirmRollback({
                          id: dAny.id,
                          name: dAny.name || `deployment ${dAny.id.slice(0, 8)}`,
                        })
                      }
                    >
                      Rollback
                    </Button>
                    <Button
                      kind='ghost'
                      sm
                      style={{color: 'var(--signal-coral-text)'}}
                      onClick={() =>
                        dAny.id &&
                        setConfirmDestroy({
                          id: dAny.id,
                          name: dAny.name || `deployment ${dAny.id.slice(0, 8)}`,
                        })
                      }
                    >
                      ×
                    </Button>
                  </div>
                }
              />
            );
          })
        )}
      </Feed>

      <ConfirmModal
        open={!!confirmRollback}
        title='Roll back this deployment?'
        body={`"${confirmRollback?.name || 'this deployment'}" will revert to the previous revision. Live traffic stays online — no downtime.`}
        confirmLabel='Roll back'
        busy={confirmBusy}
        onClose={() => setConfirmRollback(null)}
        onConfirm={() => {
          if (confirmRollback) void performRollback(confirmRollback.id);
        }}
      />
      <ConfirmModal
        open={!!confirmDestroy}
        title='Destroy this deployment?'
        body={`"${confirmDestroy?.name || 'this deployment'}" will be torn down. The URL stops serving immediately and cannot be restored from this UI.`}
        confirmLabel='Destroy'
        danger
        busy={confirmBusy}
        onClose={() => setConfirmDestroy(null)}
        onConfirm={() => {
          if (confirmDestroy) void performRemove(confirmDestroy.id);
        }}
      />
    </>
  );
}

// ──────────────────── Spec templates ────────────────────
export function SpecTemplatesPage() {
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [templates, setTemplates] = useState<api.SpecTemplate[] | null>(null);
  const [err, setErr] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<{id: string; name: string} | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  useEffect(() => {
    api
      .getProjects()
      .then((p) => {
        const active = p.filter((x) => !x.archivedAt);
        setProjects(active);
        if (active.length > 0 && !projectId) setProjectId(active[0].id);
      })
      .catch(() => {
        /* swallow */
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!projectId) return;
    setTemplates(null);
    api
      .listSpecTemplates(projectId)
      .then(setTemplates)
      .catch((e: unknown) => {
        setErr(e instanceof Error ? e.message : 'Failed to load templates.');
      });
  }, [projectId]);

  async function performCreate(values: Record<string, string>) {
    if (!projectId) return;
    const name = (values.name || '').trim();
    const body = (values.body || '').trim();
    if (!name || !body) return;
    setCreateBusy(true);
    setErr('');
    try {
      await api.createProjectSpecTemplate(projectId, {name, body} as unknown as Parameters<
        typeof api.createProjectSpecTemplate
      >[1]);
      setTemplates(await api.listSpecTemplates(projectId));
      setCreateOpen(false);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to create template.');
    } finally {
      setCreateBusy(false);
    }
  }

  async function performDelete(id: string) {
    if (!projectId) return;
    setDeleteBusy(true);
    try {
      await api.deleteSpecTemplate(projectId, id);
      setTemplates(await api.listSpecTemplates(projectId));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed.');
    } finally {
      setDeleteBusy(false);
      setConfirmDelete(null);
    }
  }

  return (
    <>
      <TopBar
        title='Spec templates'
        sub={projects.find((p) => p.id === projectId)?.name || 'Saved templates'}
        back
        backTo='/compose'
      />
      <Feed>
        <Card style={{background: 'var(--signal-lavender-soft)', borderColor: 'transparent'}}>
          <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.5}}>
            Save specs you reuse. Templates pre-fill the composer.
          </div>
        </Card>

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginTop: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}

        {projects.length > 0 && (
          <>
            <SectionHeader>Project</SectionHeader>
            <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
              {projects.map((p) => (
                <Chip
                  key={p.id}
                  kind={p.id === projectId ? 'active' : 'default'}
                  onClick={() => setProjectId(p.id)}
                >
                  {p.name}
                </Chip>
              ))}
            </div>
          </>
        )}

        {projectId && <SectionHeader>Templates · {templates?.length ?? 0}</SectionHeader>}
        {!projectId ? (
          <Card>
            <p
              style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}
            >
              Templates are project-scoped.{' '}
              <a href='/projects' style={{color: 'var(--signal-coral)'}}>
                Create a project →
              </a>{' '}
              to start saving specs.
            </p>
          </Card>
        ) : templates === null ? (
          <LoadingState label='Loading templates…' />
        ) : templates.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No templates yet. Save one to reuse common specs.
            </p>
          </Card>
        ) : (
          templates.map((t) => {
            const tAny = t as unknown as {
              id?: string;
              name?: string;
              body?: string;
              createdAt?: string;
            };
            return (
              <Row
                key={tAny.id}
                dot='lavender'
                title={tAny.name || 'untitled'}
                meta={<span>{(tAny.body || '').slice(0, 60)}…</span>}
                trailing={
                  <div style={{display: 'flex', gap: 4}}>
                    <Button
                      kind='secondary'
                      sm
                      onClick={() => {
                        sessionStorage.setItem('signal:pending-spec', tAny.body || '');
                        window.location.href = '/compose';
                      }}
                    >
                      Use
                    </Button>
                    <Button
                      kind='ghost'
                      sm
                      style={{color: 'var(--signal-coral-text)'}}
                      onClick={() =>
                        tAny.id &&
                        setConfirmDelete({id: tAny.id, name: tAny.name || 'this template'})
                      }
                    >
                      ×
                    </Button>
                  </div>
                }
              />
            );
          })
        )}

        {projectId && (
          <Button
            kind='secondary'
            block
            style={{marginTop: 14}}
            onClick={() => setCreateOpen(true)}
          >
            <Plus size={16} /> New template
          </Button>
        )}
      </Feed>

      <PromptModal
        open={createOpen}
        title='New spec template'
        body='Save reusable spec scaffolding. The body pre-fills the composer when you pick this template.'
        fields={[
          {
            name: 'name',
            label: 'Template name',
            placeholder: 'e.g. CRUD endpoint scaffold',
            required: true,
          },
          {
            name: 'body',
            label: 'Spec body',
            placeholder: 'Describe the spec…',
            multiline: true,
            required: true,
          },
        ]}
        submitLabel='Save template'
        busy={createBusy}
        onClose={() => setCreateOpen(false)}
        onSubmit={performCreate}
      />
      <ConfirmModal
        open={!!confirmDelete}
        title='Delete this template?'
        body={`"${confirmDelete?.name || 'this template'}" will be removed. Existing runs that used it are unaffected.`}
        confirmLabel='Delete'
        danger
        busy={deleteBusy}
        onClose={() => setConfirmDelete(null)}
        onConfirm={() => {
          if (confirmDelete) void performDelete(confirmDelete.id);
        }}
      />
    </>
  );
}
