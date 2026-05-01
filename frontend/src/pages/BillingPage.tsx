import {useState, useEffect, useCallback} from 'react';
import {useNavigate, useLocation} from 'react-router-dom';
import {Gift, Package, ShoppingCart, Clock, Zap} from 'lucide-react';
import {cn} from '@/lib/utils';
import {useAuthStore, selectIsOrgAdmin, selectIsSuperAdmin} from '@/stores/useAuthStore';
import BillingProfileForm from '@/components/billing/BillingProfileForm';
import PageShell from '@/components/shared/PageShell';
import {useUiStore} from '@/stores/useUiStore';
import {
  fetchBillingStatus,
  cancelSubscription,
  fetchTopupPackages,
  purchaseTopup,
  verifyTopup,
  type BillingStatus,
  type TopupPackage,
  type TopupOrderResult,
} from '@/lib/billing-api';
import {RazorpayOrderCheckout} from '@/components/billing/RazorpayOrderCheckout';

export default function BillingPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const {addToast} = useUiStore();
  const isAdmin = isOrgAdmin || isSuperAdmin;
  const isOrgUser = !!user?.orgId;

  const [status, setStatus] = useState<BillingStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [cancelling, setCancelling] = useState(false);

  // Top-up state
  const [packages, setPackages] = useState<TopupPackage[]>([]);
  const [showInr, setShowInr] = useState(true);
  const [purchasing, setPurchasing] = useState<string | null>(null); // packageId being purchased
  const [orderData, setOrderData] = useState<TopupOrderResult | null>(null);

  const loadStatus = useCallback(() => {
    return fetchBillingStatus().then(setStatus).catch(console.error);
  }, []);

  useEffect(() => {
    Promise.all([
      loadStatus(),
      fetchTopupPackages()
        .then(setPackages)
        .catch(() => {}),
    ]).finally(() => setLoading(false));
  }, [loadStatus]);

  // Scroll to #topup anchor after content loads
  useEffect(() => {
    if (!loading && location.hash === '#topup') {
      setTimeout(() => {
        document.getElementById('topup')?.scrollIntoView({behavior: 'smooth', block: 'start'});
      }, 100);
    }
  }, [loading, location.hash]);

  const handleCancel = async () => {
    if (
      !confirm(
        'Are you sure? Your subscription will remain active until the end of the current billing period.',
      )
    )
      return;
    setCancelling(true);
    try {
      await cancelSubscription();
      await loadStatus();
    } catch (err) {
      console.error('Cancel failed:', err);
    } finally {
      setCancelling(false);
    }
  };

  const handleBuyTopup = async (pkg: TopupPackage) => {
    setPurchasing(pkg.id);
    try {
      const result = await purchaseTopup(pkg.id);
      setOrderData(result);
    } catch (err: any) {
      addToast({type: 'error', message: err.message || 'Failed to initiate purchase'});
      setPurchasing(null);
    }
  };

  const handleTopupSuccess = async (response: {
    razorpay_order_id: string;
    razorpay_payment_id: string;
    razorpay_signature: string;
  }) => {
    try {
      await verifyTopup(
        response.razorpay_order_id,
        response.razorpay_payment_id,
        response.razorpay_signature,
      );
      addToast({type: 'success', message: 'Runs credited successfully!'});
      await loadStatus();
    } catch (err: any) {
      addToast({type: 'error', message: err.message || 'Payment verification failed'});
    } finally {
      setOrderData(null);
      setPurchasing(null);
    }
  };

  const handleTopupFailure = () => {
    setOrderData(null);
    setPurchasing(null);
  };

  if (loading) {
    return (
      <div className='flex items-center justify-center h-full'>
        <span className='h-6 w-6 rounded-full border-2 border-[var(--gray-400)] border-t-accent-blue animate-spin' />
      </div>
    );
  }
  if (!status) return <div className='p-8 text-secondary'>Unable to load billing information.</div>;

  const {subscription: sub, plan, usage, bonusRuns} = status;
  const isActive = sub.status === 'ACTIVE' || sub.status === 'PAST_DUE';
  const isFree = sub.status === 'FREE' || !sub.id;
  const bonusAvailable = bonusRuns?.available ?? 0;

  return (
    <PageShell
      eyebrow='Billing'
      title='Your plan, usage, and invoices.'
      accent='usage'
      description="Track what you've shipped this cycle and manage subscriptions, add-ons, and invoices."
      maxWidth='3xl'
      hero
    >
      {/* Current Plan */}
      <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 sm:p-6'>
        <div className='flex flex-col sm:flex-row justify-between items-start gap-3'>
          <div>
            <h2 className='text-lg font-semibold text-primary'>
              {plan?.name || 'Free'}{' '}
              {plan?.keyMode === 'BYOK'
                ? '(BYOK)'
                : plan?.keyMode === 'MANAGED' || plan?.keyMode === 'PLATFORM'
                  ? '(Managed Keys)'
                  : ''}
            </h2>
            <p className='text-sm text-secondary mt-1'>
              Status:{' '}
              <span
                className={cn(
                  'font-medium',
                  sub.status === 'ACTIVE' && 'text-accent-green',
                  sub.status === 'PAST_DUE' && 'text-accent-amber',
                  sub.status === 'CANCELLED' && 'text-accent-red',
                  sub.status === 'FREE' && 'text-secondary',
                )}
              >
                {sub.status}
              </span>
            </p>
            {sub.currentPeriodEnd && (
              <p className='text-sm text-muted mt-1'>
                {sub.cancelAtPeriodEnd ? 'Access until' : 'Next billing'}:{' '}
                {new Date(sub.currentPeriodEnd).toLocaleDateString()}
              </p>
            )}
            {sub.founderPricing && (
              <span className='inline-block mt-2 px-2 py-0.5 bg-accent-amber/15 text-accent-amber text-xs font-semibold rounded-sm'>
                Founder Pricing — locked in forever
              </span>
            )}
          </div>
          {!isFree && user?.tier !== 'ENTERPRISE' && (
            <button
              onClick={() => navigate('/plans')}
              className='text-sm text-accent-blue hover:text-accent-blue/80 transition-colors'
            >
              Change Plan
            </button>
          )}
        </div>

        {isFree && (
          <button
            onClick={() => navigate('/plans')}
            className='mt-4 w-full py-2.5 bg-accent-blue text-primary rounded-sm text-sm font-semibold hover:bg-accent-blue/80 transition-colors'
          >
            Upgrade Plan
          </button>
        )}
      </div>

      {/* v2.5.8 — LLM spend budget indicator. Soft ceilings derived from plan tier.
            Shows amber at 80%, red at 100% to nudge users before surprise invoice. */}
      {(() => {
        const spent = status?.spentUsd ?? 0;
        const tier = status?.plan?.tier ?? 'FREE';
        // Tier-coherence: MAX renamed to TEAM in V141.
        const softCap =
          tier === 'ENTERPRISE' ? 1000 : tier === 'TEAM' ? 500 : tier === 'PRO' ? 100 : 10;
        const pct = (spent / softCap) * 100;
        if (spent < softCap * 0.5) return null; // only show when meaningful
        const color = pct >= 100 ? 'accent-red' : pct >= 80 ? 'accent-amber' : 'accent-blue';
        return (
          <div
            className={`rounded-sm border p-4 mb-4 border-${color}/30 bg-${color}/10 flex items-start gap-3`}
          >
            <span className={`text-${color} text-lg mt-0.5`}>
              {pct >= 100 ? '⚠' : pct >= 80 ? '!' : 'ℹ'}
            </span>
            <div className='flex-1'>
              <p className={`text-sm font-medium text-${color}`}>
                LLM spend this period: ${spent.toFixed(2)} of ~${softCap} soft budget (
                {Math.round(pct)}%)
              </p>
              <p className='text-xs text-muted mt-1'>
                {pct >= 100
                  ? 'You are over the suggested monthly budget for this tier. Consider upgrading or reducing run frequency.'
                  : 'You are approaching the suggested monthly budget for this tier.'}
              </p>
            </div>
          </div>
        );
      })()}

      {/* Usage */}
      <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 sm:p-6'>
        <div className='flex items-baseline justify-between mb-4'>
          <h2 className='text-base font-semibold text-primary'>Usage This Month</h2>
          {/* v2.5.8 — LLM $ spent this period. Sums pipeline_run + execution_run cost. */}
          {typeof status?.spentUsd === 'number' && (
            <span className='text-sm text-secondary'>
              <span className='text-muted'>LLM spend: </span>
              <span className='font-mono font-semibold text-accent-amber'>
                ${status.spentUsd.toFixed(2)}
              </span>
            </span>
          )}
        </div>
        <div className='space-y-4'>
          {usage.map((u) => {
            const isRuns = u.metric === 'RUNS';
            const isUnlimited = u.max === -1;
            return (
              <div key={u.metric}>
                <div className='flex justify-between text-sm mb-1'>
                  <span className='text-secondary'>{formatMetric(u.metric)}</span>
                  <span className='text-muted'>
                    {isUnlimited ? (
                      <>
                        {u.current} runs used
                        <span className='ml-1 text-accent-green'>(Unlimited)</span>
                      </>
                    ) : (
                      <>
                        {u.current} / {u.max}
                        {isRuns && bonusAvailable > 0 && (
                          <span className='text-accent-green ml-1'>
                            (incl. {bonusAvailable} bonus)
                          </span>
                        )}
                      </>
                    )}
                  </span>
                </div>
                {!isUnlimited && (
                  <div className='w-full bg-[var(--gray-200)] rounded-full h-2'>
                    <div
                      className={cn(
                        'h-2 rounded-full transition-all',
                        u.pctUsed > 90
                          ? 'bg-accent-red'
                          : u.pctUsed > 70
                            ? 'bg-accent-amber'
                            : 'bg-accent-blue',
                      )}
                      style={{width: `${Math.min(u.pctUsed, 100)}%`}}
                    />
                  </div>
                )}
              </div>
            );
          })}
          {usage.length === 0 && <p className='text-sm text-muted'>No usage data yet.</p>}
        </div>
      </div>

      {/* Bonus Runs */}
      {bonusRuns && bonusRuns.topups.length > 0 && (
        <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 sm:p-6'>
          <div className='flex items-center gap-2 mb-4'>
            <Gift className='h-4 w-4 text-accent-green' />
            <h2 className='text-base font-semibold text-primary'>Bonus Runs</h2>
            <span className='ml-auto px-2.5 py-0.5 rounded-sm bg-accent-green/15 text-accent-green text-sm font-semibold'>
              {bonusAvailable} available
            </span>
          </div>
          <div className='space-y-2'>
            {bonusRuns.topups.map((t) => (
              <div
                key={t.id}
                className={cn(
                  'flex flex-col sm:flex-row sm:items-center justify-between gap-2 px-4 py-2.5 rounded-sm border',
                  t.status === 'ACTIVE'
                    ? 'bg-accent-green/[0.04] border-accent-green/20'
                    : 'bg-[var(--gray-100)] border-[var(--border-default)] opacity-60',
                )}
              >
                <div className='flex items-center gap-3'>
                  <span
                    className={cn(
                      'px-2 py-0.5 rounded text-[10px] font-semibold uppercase',
                      t.source === 'PURCHASE'
                        ? 'bg-accent-blue/15 text-accent-blue'
                        : 'bg-accent-amber/15 text-accent-amber',
                    )}
                  >
                    {t.source === 'PURCHASE' ? 'Purchased' : 'Granted'}
                  </span>
                  <span className='text-sm text-primary'>
                    {t.remaining}/{t.quantity} remaining
                  </span>
                </div>
                <div className='flex items-center gap-3 text-xs text-muted'>
                  {t.expiresAt && (
                    <span className='flex items-center gap-1'>
                      <Clock className='h-3 w-3' />
                      Expires {new Date(t.expiresAt).toLocaleDateString()}
                    </span>
                  )}
                  <span
                    className={cn(
                      'px-1.5 py-0.5 rounded text-[10px] font-medium',
                      t.status === 'ACTIVE' && 'text-accent-green',
                      t.status === 'EXHAUSTED' && 'text-muted',
                      t.status === 'EXPIRED' && 'text-accent-red',
                    )}
                  >
                    {t.status}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Buy Extra Runs (hidden for TEAM/ENTERPRISE — BYOK + unlimited) */}
      <div id='topup' />
      {packages.length > 0 &&
        user?.tier !== 'TEAM' &&
        user?.tier !== 'ENTERPRISE' &&
        (() => {
          const filtered = packages.filter((p) =>
            showInr ? p.amountInr > 0 && p.amountUsd === 0 : p.amountUsd > 0 && p.amountInr === 0,
          );
          if (filtered.length === 0) return null;
          return (
            <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 sm:p-6'>
              <div className='flex items-center justify-between mb-1'>
                <div className='flex items-center gap-2'>
                  <Zap className='h-4 w-4 text-accent-amber' />
                  <h2 className='text-base font-semibold text-primary'>Buy Extra Runs</h2>
                </div>
                <div className='flex rounded-md bg-[var(--gray-200)] p-0.5'>
                  <button
                    onClick={() => setShowInr(true)}
                    className={`px-3 py-1 rounded-md text-xs font-medium transition ${
                      showInr ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
                    }`}
                  >
                    INR
                  </button>
                  <button
                    onClick={() => setShowInr(false)}
                    className={`px-3 py-1 rounded-md text-xs font-medium transition ${
                      !showInr ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
                    }`}
                  >
                    USD
                  </button>
                </div>
              </div>
              <p className='text-sm text-muted mb-4'>
                Need more runs? Purchase a top-up pack. Purchased runs never expire.
              </p>
              <div className='grid grid-cols-1 sm:grid-cols-3 gap-2 sm:gap-3'>
                {filtered.map((pkg) => (
                  <button
                    key={pkg.id}
                    onClick={() => handleBuyTopup(pkg)}
                    disabled={purchasing !== null}
                    className={cn(
                      'relative group flex flex-col items-center p-5 rounded-sm border transition-all',
                      'bg-[var(--gray-100)] border-[var(--border-default)] hover:border-accent-blue/40 hover:bg-accent-blue/[0.04]',
                      'disabled:opacity-40 disabled:cursor-not-allowed',
                      purchasing === pkg.id && 'border-accent-blue/60 bg-accent-blue/[0.06]',
                    )}
                  >
                    <Package className='h-6 w-6 text-accent-blue mb-2 group-hover:scale-110 transition-transform' />
                    <span className='text-lg font-bold text-primary'>{pkg.runCount} Runs</span>
                    <div className='mt-3'>
                      <span className='text-xl font-bold text-accent-blue'>
                        {showInr
                          ? `₹${(pkg.amountInr / 100).toLocaleString('en-IN')}`
                          : `$${(pkg.amountUsd / 100).toFixed(2)}`}
                      </span>
                    </div>
                    {showInr && <p className='text-xs text-muted mt-1'>+ 18% GST</p>}
                    {purchasing === pkg.id && (
                      <span className='absolute inset-0 flex items-center justify-center rounded-sm bg-[var(--gray-200)]'>
                        <span className='h-5 w-5 rounded-full border-2 border-white/30 border-t-white animate-spin' />
                      </span>
                    )}
                  </button>
                ))}
              </div>
            </div>
          );
        })()}

      {/* Cancel — admin only for org accounts */}
      {isActive && !sub.cancelAtPeriodEnd && (!isOrgUser || isAdmin) && (
        <div className='rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-4 sm:p-6'>
          <button
            onClick={handleCancel}
            disabled={cancelling}
            className='text-sm text-accent-red hover:text-accent-red/80 transition-colors disabled:opacity-50'
          >
            {cancelling ? 'Cancelling...' : 'Cancel Subscription'}
          </button>
          <p className='text-xs text-muted mt-1'>
            Cancellation takes effect at end of billing period. No refunds.
          </p>
        </div>
      )}

      {/* Razorpay Order Checkout overlay */}
      {orderData && (
        <RazorpayOrderCheckout
          orderId={orderData.orderId}
          razorpayKeyId={orderData.razorpayKeyId}
          amountPaise={orderData.amountPaise}
          onSuccess={handleTopupSuccess}
          onFailure={handleTopupFailure}
          userName={user ? `${user.firstName} ${user.lastName}` : undefined}
          userEmail={user?.email}
          description={`Run Top-Up — ₹${(orderData.amountPaise / 100).toLocaleString('en-IN')} + 18% GST`}
        />
      )}

      {/* Billing profile (GST details) — admin only for org accounts */}
      {(!isOrgUser || isAdmin) && <BillingProfileForm />}
    </PageShell>
  );
}

function formatMetric(metric: string): string {
  switch (metric) {
    case 'RUNS':
      return 'Runs';
    case 'PROJECTS':
      return 'Projects';
    case 'STORAGE_MB':
      return 'Storage';
    default:
      return metric;
  }
}
