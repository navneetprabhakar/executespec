import {useEffect, useState} from 'react';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, KvList} from '@/signal/components/Primitives';
import {useAuthStore, selectNormalizedTier} from '@/stores/useAuthStore';
import * as api from '@/lib/api';

interface PaymentRowLite {
  id?: string | number;
  invoiceNumber?: string;
  number?: string;
  amount?: number;
  currency?: string;
  status?: string;
  gateway?: string;
  createdAt?: string;
  paidAt?: string;
}

export default function BillingPage() {
  const user = useAuthStore((s) => s.user);
  const tier = useAuthStore(selectNormalizedTier);
  const permissions = useAuthStore((s) => s.permissions);
  const [billing, setBilling] = useState<api.BillingOverviewData | null>(null);
  const [usage, setUsage] = useState<api.UsageMetrics | null>(null);
  const [payments, setPayments] = useState<PaymentRowLite[]>([]);
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!user?.orgId) return;
    let cancelled = false;
    Promise.allSettled([
      api.getAnalyticsBilling('org', user.orgId).catch(() => null),
      api.getAnalyticsUsage('org', user.orgId, 30).catch(() => null),
      // Best-effort: paymentHistory may not return a typed shape; fail soft.
      (async () => {
        try {
          const raw = await fetch(
            `/api/billing/payment-history?orgId=${encodeURIComponent(user.orgId ?? '')}`,
            {
              headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
            },
          );
          if (!raw.ok) return [];
          const data = (await raw.json()) as unknown;
          if (Array.isArray(data)) return data as PaymentRowLite[];
          if (
            data &&
            typeof data === 'object' &&
            Array.isArray((data as {content?: PaymentRowLite[]}).content)
          ) {
            return (data as {content: PaymentRowLite[]}).content;
          }
          return [];
        } catch {
          return [];
        }
      })(),
    ]).then(([b, u, p]) => {
      if (cancelled) return;
      if (b.status === 'fulfilled' && b.value) setBilling(b.value);
      if (u.status === 'fulfilled' && u.value) setUsage(u.value);
      if (p.status === 'fulfilled' && Array.isArray(p.value)) setPayments(p.value);
    });
    return () => {
      cancelled = true;
    };
  }, [user?.orgId]);

  const billingAny = (billing || {}) as unknown as {
    seats?: number;
    totalSpend?: number;
    amountPaid?: number;
    nextInvoiceDate?: string;
    cycleStart?: string;
    cycleEnd?: string;
    paymentMethod?: {brand?: string; last4?: string; expMonth?: number; expYear?: number};
    monthlyRecurringTotal?: number;
  };
  const seats = billingAny.seats ?? 1;
  const totalSpend = (billingAny.totalSpend ?? billingAny.amountPaid ?? 0) as number;
  const next = billingAny.nextInvoiceDate || billingAny.cycleEnd || '';
  const usageAny = (usage || {}) as unknown as {
    totalRuns?: number;
    spend?: number;
    tokens?: number;
  };
  const totalRuns = usageAny.totalRuns ?? permissions?.currentRunCount ?? 0;
  const runCap =
    permissions?.monthlyRunLimit && permissions.monthlyRunLimit !== -1
      ? permissions.monthlyRunLimit
      : '∞';
  const pm = billingAny.paymentMethod;

  return (
    <>
      <TopBar
        title='Billing'
        sub={`${tier} · ${seats} seat${seats === 1 ? '' : 's'}`}
        back
        backTo='/account'
      />
      <Feed>
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

        <Card style={{padding: 18}}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              flexWrap: 'wrap',
              gap: 12,
              marginBottom: 14,
            }}
          >
            <div>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 700,
                  color: 'var(--signal-text-mute)',
                  letterSpacing: '0.14em',
                  textTransform: 'uppercase',
                  marginBottom: 4,
                }}
              >
                {tier} tier
              </div>
              <div style={{fontSize: 20, fontWeight: 700, letterSpacing: '-0.02em'}}>
                {billingAny.monthlyRecurringTotal != null
                  ? `$${billingAny.monthlyRecurringTotal.toFixed(2)}/mo`
                  : tier === 'FREE'
                    ? 'No subscription'
                    : 'Subscription active'}
              </div>
              <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginTop: 2}}>
                {seats} seat{seats === 1 ? '' : 's'}
                {pm?.brand && ` · billed via ${pm.brand}`}
                {next && ` · renews ${new Date(next).toLocaleDateString()}`}
              </div>
            </div>
            <div style={{display: 'flex', gap: 6}}>
              <Button kind='primary' sm to='/plans'>
                Change plan
              </Button>
            </div>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(3, 1fr)',
              gap: 12,
              paddingTop: 12,
              borderTop: '1px solid var(--signal-hairline)',
              fontSize: 12,
            }}
          >
            <div>
              <div style={{color: 'var(--signal-text-dim)', fontSize: 11, marginBottom: 2}}>
                Runs
              </div>
              <div style={{fontWeight: 700, fontSize: 16}}>
                {totalRuns} / {runCap}
              </div>
            </div>
            <div>
              <div style={{color: 'var(--signal-text-dim)', fontSize: 11, marginBottom: 2}}>
                Spent
              </div>
              <div style={{fontWeight: 700, fontSize: 16}}>${totalSpend.toFixed(2)}</div>
            </div>
            <div>
              <div style={{color: 'var(--signal-text-dim)', fontSize: 11, marginBottom: 2}}>
                Next invoice
              </div>
              <div style={{fontWeight: 700, fontSize: 16}}>
                {next ? new Date(next).toLocaleDateString() : '—'}
              </div>
            </div>
          </div>
        </Card>

        <SectionHeader>This cycle</SectionHeader>
        <Card>
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'baseline',
              marginBottom: 8,
            }}
          >
            <div
              style={{
                fontSize: 11,
                fontWeight: 700,
                color: 'var(--signal-text-mute)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
              }}
            >
              Runs used
            </div>
            <div
              style={{
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: 12,
                color: 'var(--signal-text-dim)',
              }}
            >
              {billingAny.cycleStart ? new Date(billingAny.cycleStart).toLocaleDateString() : '—'} –{' '}
              {billingAny.cycleEnd ? new Date(billingAny.cycleEnd).toLocaleDateString() : '—'}
            </div>
          </div>
          <div
            style={{
              fontSize: 36,
              fontWeight: 600,
              letterSpacing: '-0.02em',
              lineHeight: 1,
              marginBottom: 8,
            }}
          >
            {totalRuns}
            <span style={{fontSize: 18, color: 'var(--signal-text-mute)', fontWeight: 400}}>
              {' '}
              / {runCap}
            </span>
          </div>
          {typeof runCap === 'number' && runCap > 0 && (
            <div
              style={{
                height: 6,
                background: 'var(--signal-card-elev)',
                borderRadius: 3,
                overflow: 'hidden',
                marginBottom: 14,
              }}
            >
              <div
                style={{
                  height: '100%',
                  width: `${Math.min(100, (totalRuns / runCap) * 100)}%`,
                  background: 'linear-gradient(90deg, var(--signal-mint), var(--signal-y))',
                  borderRadius: 3,
                }}
              />
            </div>
          )}
          <KvList
            pairs={[
              {k: 'Tier', v: tier},
              {k: 'Spend (cycle)', v: `$${totalSpend.toFixed(2)}`},
              {
                k: 'Tokens (30d)',
                v: usageAny.tokens != null ? usageAny.tokens.toLocaleString() : '—',
              },
            ]}
          />
        </Card>

        <SectionHeader
          action={payments.length > 6 ? <span>All {payments.length}</span> : undefined}
        >
          Invoices
        </SectionHeader>
        {payments.length === 0 ? (
          <Card>
            <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
              No invoices yet. Your first invoice will appear here after billing closes.
            </p>
          </Card>
        ) : (
          payments.slice(0, 6).map((p) => {
            const num = p.invoiceNumber || p.number || (p.id != null ? `INV-${p.id}` : 'INV-—');
            const amount = p.amount != null ? `${p.currency || 'USD'} ${p.amount.toFixed(2)}` : '—';
            const paid = (p.status || '').toLowerCase() === 'paid' || p.paidAt;
            return (
              <Row
                key={String(p.id || num)}
                dot={paid ? 'mint' : 'signal'}
                title={`${num} — ${amount}`}
                meta={
                  <>
                    <span className='mono'>{p.gateway || 'card'}</span>
                    <span>·</span>
                    <span>{paid ? 'Paid' : p.status || 'pending'}</span>
                    {p.createdAt && (
                      <>
                        <span>·</span>
                        <span>{new Date(p.createdAt).toLocaleDateString()}</span>
                      </>
                    )}
                  </>
                }
                showChev
              />
            );
          })
        )}

        <SectionHeader>Payment method</SectionHeader>
        <Card style={{display: 'flex', gap: 14, alignItems: 'center'}}>
          <div
            style={{
              width: 44,
              height: 30,
              background: 'linear-gradient(135deg, #1A1F71, #4F5BC2)',
              borderRadius: 6,
              display: 'grid',
              placeItems: 'center',
              color: 'white',
              fontWeight: 700,
              fontSize: 12,
              fontStyle: 'italic',
            }}
          >
            {(pm?.brand || 'VISA').toUpperCase().slice(0, 4)}
          </div>
          <div style={{flex: 1}}>
            <div style={{fontSize: 14, fontWeight: 500}}>•••• {pm?.last4 || '—'}</div>
            <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
              {pm?.expMonth && pm?.expYear
                ? `Expires ${pm.expMonth.toString().padStart(2, '0')}/${(pm.expYear % 100).toString().padStart(2, '0')}`
                : 'No card on file'}
            </div>
          </div>
          <Button kind='secondary' sm>
            Update
          </Button>
        </Card>
      </Feed>
    </>
  );
}
