import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { fetchPlans, subscribe, verifyPayment, type BillingPlan, type SubscriptionInitResult } from '@/lib/billing-api';
import { PlanCard } from '@/components/billing/PlanCard';
import { DisclaimerModal } from '@/components/billing/DisclaimerModal';
import { RazorpayCheckout } from '@/components/billing/RazorpayCheckout';
import { useAuthStore } from '@/stores/useAuthStore';
import PageShell from '@/components/shared/PageShell';

export default function PlansPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();
  const [searchParams] = useSearchParams();
  // M-009 (Wave 1) — when arriving from /legacy/billing the redirect carries
  // ?legacy=1. Show a soft banner explaining grandfathered Pro continues +
  // surface the Pro plan inline so the buyer can manage their existing sub.
  const legacyView = searchParams.get('legacy') === '1';

  const [plans, setPlans] = useState<BillingPlan[]>([]);
  const [loading, setLoading] = useState(true);
  // Default: USD for everyone except detected-IN locales (language or timezone).
  // Users can still flip the toggle manually.
  const [showInr, setShowInr] = useState(() => {
    try {
      const lang = (navigator.language || '').toLowerCase();
      const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
      return lang.startsWith('en-in') || lang.startsWith('hi') || tz === 'Asia/Kolkata';
    } catch {
      return false; // default USD
    }
  });
  // M-007 (Wave 1) — Annual/Monthly toggle. V146 added YEARLY billing_plan
  // rows for TEAM + MAX with 17% discount. Default Monthly so existing buyer
  // mental model is preserved; Annual is the upsell.
  const [showAnnual, setShowAnnual] = useState(false);

  // Checkout flow state
  const [selectedPlanId, setSelectedPlanId] = useState<string | null>(null);
  const [selectedSeats, setSelectedSeats] = useState<number>(1);
  const [showDisclaimer, setShowDisclaimer] = useState(false);
  const [subscribing, setSubscribing] = useState(false);
  const [checkoutData, setCheckoutData] = useState<SubscriptionInitResult | null>(null);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);

  useEffect(() => {
    fetchPlans()
      .then(setPlans)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  // Group plans by tier, pick INR or USD + period variant based on toggles.
  // M-007 (Wave 1) — period axis added: MONTHLY (default) or YEARLY (V146).
  const displayPlans = (() => {
    const wantPeriod = showAnnual ? 'YEARLY' : 'MONTHLY';
    const byTier: Record<string, Record<string, BillingPlan>> = {};
    for (const p of plans) {
      if (p.tier === 'FREE' || !p.active) continue;
      if (p.period && p.period !== wantPeriod && p.period !== 'NONE') continue;
      if (!byTier[p.tier]) byTier[p.tier] = {};
      const currency = p.baseAmountInr > 0 || p.perSeatInr > 0 ? 'INR' : 'USD';
      byTier[p.tier][currency] = p;
    }
    // Phase 1.1 #9: PRO is hidden from new buyers (lifecycle filter at backend).
    // M-009 (Wave 1) — when ?legacy=1 arrives from /legacy/billing, show PRO
    // inline so grandfathered subscribers can manage their existing plan.
    const tierOrder = legacyView ? ['PRO', 'TEAM', 'MAX'] : ['TEAM', 'MAX'];
    return tierOrder
      .filter(t => byTier[t])
      .map(t => byTier[t][showInr ? 'INR' : 'USD'])
      .filter(Boolean);
  })();

  // ─── Checkout Flow ───

  const handleSelectPlan = (planId: string, seats?: number) => {
    setSelectedPlanId(planId);
    setSelectedSeats(seats ?? 1);
    setShowDisclaimer(true);
    setCheckoutError(null);
  };

  const handleDisclaimerAccept = async () => {
    if (!selectedPlanId) return;
    setShowDisclaimer(false);
    setSubscribing(true);

    try {
      const gateway = showInr ? 'RAZORPAY' : 'STRIPE';
      const result = await subscribe(
        selectedPlanId,
        gateway,
        gateway === 'STRIPE' ? `${window.location.origin}/billing?status=success` : undefined,
        gateway === 'STRIPE' ? `${window.location.origin}/billing?status=cancelled` : undefined,
        selectedSeats,
      );

      if (result.gateway === 'STRIPE' && result.checkoutUrl) {
        window.location.href = result.checkoutUrl;
        return;
      }

      // Razorpay flow
      setCheckoutData(result);
    } catch (err: any) {
      setCheckoutError(err?.message || 'Failed to create subscription');
      setSelectedPlanId(null);
    } finally {
      setSubscribing(false);
    }
  };

  const handleCheckoutSuccess = async (response: any) => {
    try {
      await verifyPayment(
        response.razorpay_subscription_id,
        response.razorpay_payment_id,
        response.razorpay_signature
      );
      // Force full page reload so auth state (tier, permissions) refreshes everywhere
      window.location.href = '/billing?status=success';
    } catch {
      setCheckoutError('Payment verification failed. Please contact support.');
    } finally {
      setCheckoutData(null);
      setSelectedPlanId(null);
    }
  };

  const handleCheckoutFailure = () => {
    setCheckoutData(null);
    setSelectedPlanId(null);
    setCheckoutError('Payment was cancelled or failed. Please try again.');
  };

  if (loading) {
    return <div className="flex justify-center items-center h-64">Loading plans...</div>;
  }

  return (
    <PageShell
      maxWidth="5xl"
      hero
      eyebrow="Plans"
      title="Simple, transparent pricing."
      accent="transparent"
      description="No credits to translate. No token markups. Every tier shows you what ExecuteSpec really is."
    >
      {/* M-009 (Wave 1) — soft landing banner for grandfathered PRO subscribers
          arriving via /legacy/billing → /plans?legacy=1 redirect. */}
      {legacyView && (
        <div className="mb-6 p-4 bg-accent-blue/10 border border-accent-blue/30 rounded-md text-sm">
          <p className="font-medium text-primary mb-1">Your Pro plan continues — same price, same features.</p>
          <p className="text-muted text-xs leading-relaxed">
            Pro is no longer offered to new buyers, but existing subscriptions renew untouched. Your current plan is shown below alongside our newer Team and Max tiers.
            Need to manage billing details? Visit <a href="/billing" className="text-accent-blue underline">Billing</a>.
          </p>
        </div>
      )}

      {/* Toggle row — currency + period (M-007). */}
      <div className="flex justify-center gap-3 flex-wrap">
        <div className="flex items-center gap-2 bg-[var(--gray-200)] rounded-md p-1 border border-[var(--border-default)]">
          <button
            onClick={() => setShowInr(true)}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              showInr ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            INR
          </button>
          <button
            onClick={() => setShowInr(false)}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              !showInr ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            USD
          </button>
        </div>
        <div className="flex items-center gap-2 bg-[var(--gray-200)] rounded-md p-1 border border-[var(--border-default)]">
          <button
            onClick={() => setShowAnnual(false)}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              !showAnnual ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            Monthly
          </button>
          <button
            onClick={() => setShowAnnual(true)}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition inline-flex items-center gap-1.5 ${
              showAnnual ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            Annual
            <span className="text-[10px] font-semibold uppercase tracking-wide px-1.5 py-0.5 rounded bg-status-success/15 text-status-success border border-status-success/30">Save 17%</span>
          </button>
        </div>
      </div>

      {/* Error message */}
      {checkoutError && (
        <div className="mb-6 p-4 bg-status-error/10 border border-status-error/30 text-status-error rounded-sm text-sm text-center">
          {checkoutError}
        </div>
      )}

      {/* Plan cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 md:gap-6 max-w-3xl mx-auto">
        {displayPlans.map(plan => (
          <PlanCard
            key={plan.id}
            plan={plan}
            isCurrentPlan={plan.tier === user?.tier}
            showInr={showInr}
            onSelect={handleSelectPlan}
            userTier={user?.tier}
          />
        ))}
      </div>

      {/* Enterprise CTA */}
      <div className="mt-10 text-center">
        <p className="text-secondary">
          Need more?{' '}
          <a href="mailto:admin@executespec.ai" className="text-accent-blue font-medium hover:underline">
            Contact us for Enterprise pricing
          </a>
        </p>
      </div>

      {/* Key mode explainer */}
      <div className="mt-8 max-w-2xl mx-auto text-center text-sm text-muted space-y-2">
        <p><strong>Pro:</strong> Managed keys — we handle LLM infrastructure. Zero setup required.</p>
        <p><strong>Max:</strong> Bring your own Anthropic/OpenAI keys. Unlimited runs, full control.</p>
      </div>

      {/* Subscribing overlay */}
      {subscribing && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-3">
            <span className="h-8 w-8 rounded-full border-3 border-white/20 border-t-accent-blue animate-spin" />
            <span className="text-sm text-secondary font-medium">Preparing checkout…</span>
          </div>
        </div>
      )}

      {/* Disclaimer Modal */}
      <DisclaimerModal
        isOpen={showDisclaimer}
        onAccept={handleDisclaimerAccept}
        onCancel={() => { setShowDisclaimer(false); setSelectedPlanId(null); }}
      />

      {/* Razorpay Checkout */}
      {checkoutData && (() => {
        const selectedPlan = plans.find(p => p.id === selectedPlanId);
        return (
          <RazorpayCheckout
            subscriptionId={checkoutData.gatewaySubscriptionId}
            razorpayKeyId={checkoutData.razorpayKeyId}
            onSuccess={handleCheckoutSuccess}
            onFailure={handleCheckoutFailure}
            userName={user ? `${user.firstName} ${user.lastName}` : undefined}
            userEmail={user?.email}
            planName={selectedPlan?.name}
            amountPaise={selectedPlan?.baseAmountInr}
          />
        );
      })()}
    </PageShell>
  );
}
