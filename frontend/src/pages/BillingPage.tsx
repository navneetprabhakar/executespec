import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Gift, Package, ShoppingCart, Clock, Zap } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuthStore, selectIsOrgAdmin, selectIsSuperAdmin } from '@/stores/useAuthStore';
import BillingProfileForm from '@/components/billing/BillingProfileForm';
import { useUiStore } from '@/stores/useUiStore';
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
import { RazorpayOrderCheckout } from '@/components/billing/RazorpayOrderCheckout';

export default function BillingPage() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const isOrgAdmin = useAuthStore(selectIsOrgAdmin);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const { addToast } = useUiStore();
  const isAdmin = isOrgAdmin || isSuperAdmin;
  const isOrgUser = !!user?.orgId;

  const [status, setStatus] = useState<BillingStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [cancelling, setCancelling] = useState(false);

  // Top-up state
  const [packages, setPackages] = useState<TopupPackage[]>([]);
  const [purchasing, setPurchasing] = useState<string | null>(null); // packageId being purchased
  const [orderData, setOrderData] = useState<TopupOrderResult | null>(null);

  const loadStatus = useCallback(() => {
    return fetchBillingStatus()
      .then(setStatus)
      .catch(console.error);
  }, []);

  useEffect(() => {
    Promise.all([
      loadStatus(),
      fetchTopupPackages().then(setPackages).catch(() => {}),
    ]).finally(() => setLoading(false));
  }, [loadStatus]);

  const handleCancel = async () => {
    if (!confirm('Are you sure? Your subscription will remain active until the end of the current billing period.')) return;
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
      addToast({ type: 'error', message: err.message || 'Failed to initiate purchase' });
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
      addToast({ type: 'success', message: 'Runs credited successfully!' });
      await loadStatus();
    } catch (err: any) {
      addToast({ type: 'error', message: err.message || 'Payment verification failed' });
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
      <div className="flex items-center justify-center h-full">
        <span className="h-6 w-6 rounded-full border-2 border-[var(--gray-400)] border-t-accent-blue animate-spin" />
      </div>
    );
  }
  if (!status) return <div className="p-8 text-secondary">Unable to load billing information.</div>;

  const { subscription: sub, plan, usage, bonusRuns } = status;
  const isActive = sub.status === 'ACTIVE' || sub.status === 'PAST_DUE';
  const isFree = sub.status === 'FREE' || !sub.id;
  const bonusAvailable = bonusRuns?.available ?? 0;

  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-3xl mx-auto p-6 space-y-6">

        {/* Page header */}
        <h1 className="text-xl font-bold text-primary">Billing</h1>

        {/* Current Plan */}
        <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-6">
          <div className="flex justify-between items-start">
            <div>
              <h2 className="text-lg font-semibold text-primary">
                {plan?.name || 'Free'}{' '}
                {plan?.keyMode === 'BYOK' ? '(BYOK)' : plan?.keyMode === 'PLATFORM' ? '(Platform Keys)' : ''}
              </h2>
              <p className="text-sm text-secondary mt-1">
                Status:{' '}
                <span className={cn(
                  'font-medium',
                  sub.status === 'ACTIVE' && 'text-accent-green',
                  sub.status === 'PAST_DUE' && 'text-accent-amber',
                  sub.status === 'CANCELLED' && 'text-accent-red',
                  sub.status === 'FREE' && 'text-secondary',
                )}>
                  {sub.status}
                </span>
              </p>
              {sub.currentPeriodEnd && (
                <p className="text-sm text-muted mt-1">
                  {sub.cancelAtPeriodEnd ? 'Access until' : 'Next billing'}:{' '}
                  {new Date(sub.currentPeriodEnd).toLocaleDateString()}
                </p>
              )}
              {sub.founderPricing && (
                <span className="inline-block mt-2 px-2 py-0.5 bg-accent-amber/15 text-accent-amber text-xs font-semibold rounded-sm">
                  Founder Pricing — locked in forever
                </span>
              )}
            </div>
            {!isFree && user?.tier !== 'ENTERPRISE' && (
              <button
                onClick={() => navigate('/plans')}
                className="text-sm text-accent-blue hover:text-accent-blue/80 transition-colors"
              >
                Change Plan
              </button>
            )}
          </div>

          {isFree && (
            <button
              onClick={() => navigate('/plans')}
              className="mt-4 w-full py-2.5 bg-accent-blue text-primary rounded-sm text-sm font-semibold hover:bg-accent-blue/80 transition-colors"
            >
              Upgrade Plan
            </button>
          )}
        </div>

        {/* Usage */}
        <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-6">
          <h2 className="text-base font-semibold text-primary mb-4">Usage This Month</h2>
          <div className="space-y-4">
            {usage.map((u) => {
              const isRuns = u.metric === 'RUNS';
              return (
                <div key={u.metric}>
                  <div className="flex justify-between text-sm mb-1">
                    <span className="text-secondary">{formatMetric(u.metric)}</span>
                    <span className="text-muted">
                      {u.current} / {u.max}
                      {isRuns && bonusAvailable > 0 && (
                        <span className="text-accent-green ml-1">(incl. {bonusAvailable} bonus)</span>
                      )}
                    </span>
                  </div>
                  <div className="w-full bg-[var(--gray-200)] rounded-full h-2">
                    <div
                      className={cn(
                        'h-2 rounded-full transition-all',
                        u.pctUsed > 90 ? 'bg-accent-red' : u.pctUsed > 70 ? 'bg-accent-amber' : 'bg-accent-blue',
                      )}
                      style={{ width: `${Math.min(u.pctUsed, 100)}%` }}
                    />
                  </div>
                </div>
              );
            })}
            {usage.length === 0 && (
              <p className="text-sm text-muted">No usage data yet.</p>
            )}
          </div>
        </div>

        {/* Bonus Runs */}
        {bonusRuns && bonusRuns.topups.length > 0 && (
          <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-6">
            <div className="flex items-center gap-2 mb-4">
              <Gift className="h-4 w-4 text-accent-green" />
              <h2 className="text-base font-semibold text-primary">Bonus Runs</h2>
              <span className="ml-auto px-2.5 py-0.5 rounded-sm bg-accent-green/15 text-accent-green text-sm font-semibold">
                {bonusAvailable} available
              </span>
            </div>
            <div className="space-y-2">
              {bonusRuns.topups.map((t) => (
                <div
                  key={t.id}
                  className={cn(
                    'flex items-center justify-between px-4 py-2.5 rounded-sm border',
                    t.status === 'ACTIVE'
                      ? 'bg-accent-green/[0.04] border-accent-green/20'
                      : 'bg-[var(--gray-100)] border-[var(--border-default)] opacity-60',
                  )}
                >
                  <div className="flex items-center gap-3">
                    <span className={cn(
                      'px-2 py-0.5 rounded text-[10px] font-semibold uppercase',
                      t.source === 'PURCHASE'
                        ? 'bg-accent-blue/15 text-accent-blue'
                        : 'bg-accent-amber/15 text-accent-amber',
                    )}>
                      {t.source === 'PURCHASE' ? 'Purchased' : 'Granted'}
                    </span>
                    <span className="text-sm text-primary">
                      {t.remaining}/{t.quantity} remaining
                    </span>
                  </div>
                  <div className="flex items-center gap-3 text-xs text-muted">
                    {t.expiresAt && (
                      <span className="flex items-center gap-1">
                        <Clock className="h-3 w-3" />
                        Expires {new Date(t.expiresAt).toLocaleDateString()}
                      </span>
                    )}
                    <span className={cn(
                      'px-1.5 py-0.5 rounded text-[10px] font-medium',
                      t.status === 'ACTIVE' && 'text-accent-green',
                      t.status === 'EXHAUSTED' && 'text-muted',
                      t.status === 'EXPIRED' && 'text-accent-red',
                    )}>
                      {t.status}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Buy Extra Runs */}
        {packages.length > 0 && (
          <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-6">
            <div className="flex items-center gap-2 mb-1">
              <Zap className="h-4 w-4 text-accent-amber" />
              <h2 className="text-base font-semibold text-primary">Buy Extra Runs</h2>
            </div>
            <p className="text-sm text-muted mb-4">
              Need more runs? Purchase a top-up pack. Purchased runs never expire.
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              {packages.map((pkg) => (
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
                  <Package className="h-6 w-6 text-accent-blue mb-2 group-hover:scale-110 transition-transform" />
                  <span className="text-lg font-bold text-primary">{pkg.runCount} Runs</span>
                  <span className="text-secondary text-sm mt-1">{pkg.name}</span>
                  <div className="mt-3 flex items-baseline gap-1">
                    <span className="text-xl font-bold text-accent-blue">
                      {'\u20B9'}{(pkg.amountInr / 100).toFixed(0)}
                    </span>
                    {pkg.amountUsd > 0 && (
                      <span className="text-xs text-muted ml-1">
                        / ${(pkg.amountUsd / 100).toFixed(2)}
                      </span>
                    )}
                  </div>
                  {purchasing === pkg.id && (
                    <span className="absolute inset-0 flex items-center justify-center rounded-sm bg-[var(--gray-200)]">
                      <span className="h-5 w-5 rounded-full border-2 border-white/30 border-t-white animate-spin" />
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Cancel — admin only for org accounts */}
        {isActive && !sub.cancelAtPeriodEnd && (!isOrgUser || isAdmin) && (
          <div className="rounded-sm bg-[var(--gray-100)] border border-[var(--border-default)] p-6">
            <button
              onClick={handleCancel}
              disabled={cancelling}
              className="text-sm text-accent-red hover:text-accent-red/80 transition-colors disabled:opacity-50"
            >
              {cancelling ? 'Cancelling...' : 'Cancel Subscription'}
            </button>
            <p className="text-xs text-muted mt-1">
              Cancellation takes effect at end of billing period. No refunds.
            </p>
          </div>
        )}
      </div>

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
        />
      )}

      {/* Billing profile (GST details) — admin only for org accounts */}
      {(!isOrgUser || isAdmin) && <BillingProfileForm />}
    </div>
  );
}

function formatMetric(metric: string): string {
  switch (metric) {
    case 'RUNS': return 'Runs';
    case 'PROJECTS': return 'Projects';
    case 'STORAGE_MB': return 'Storage';
    default: return metric;
  }
}
