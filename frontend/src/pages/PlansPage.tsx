import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchPlans, subscribe, verifyPayment, type BillingPlan, type SubscriptionInitResult } from '@/lib/billing-api';
import { PlanCard } from '@/components/billing/PlanCard';
import { DisclaimerModal } from '@/components/billing/DisclaimerModal';
import { RazorpayCheckout } from '@/components/billing/RazorpayCheckout';
import { useAuthStore } from '@/stores/useAuthStore';
import PageShell from '@/components/shared/PageShell';

export default function PlansPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();

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

  // Group plans by tier, pick INR or USD variant based on currency toggle
  const displayPlans = (() => {
    const byTier: Record<string, Record<string, BillingPlan>> = {};
    for (const p of plans) {
      if (p.tier === 'FREE' || !p.active) continue;
      if (!byTier[p.tier]) byTier[p.tier] = {};
      const currency = p.baseAmountInr > 0 ? 'INR' : 'USD';
      byTier[p.tier][currency] = p;
    }
    // V141 (2026-04-26) — collapsed ladder. PRO is HIDDEN from new buyers
    // via backend lifecycle filter (still shown to existing subscribers as
    // CURRENT). TEAM is the renamed MAX tier ($29 BYOK unlimited). The old
    // per-seat TEAM was deleted in V141.
    const tierOrder = ['PRO', 'TEAM'];
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
      {/* Currency toggle */}
      <div className="flex justify-center">
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
        <p><strong>Team:</strong> Bring your own Anthropic/OpenAI keys. Unlimited runs, full control.</p>
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
