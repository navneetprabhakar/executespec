import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { fetchPlans, subscribe, verifyPayment, type BillingPlan, type SubscriptionInitResult } from '@/lib/billing-api';
import { PlanCard } from '@/components/billing/PlanCard';
import { DisclaimerModal } from '@/components/billing/DisclaimerModal';
import { RazorpayCheckout } from '@/components/billing/RazorpayCheckout';
import { useAuthStore } from '@/stores/useAuthStore';

export default function PlansPage() {
  const navigate = useNavigate();
  const { user } = useAuthStore();

  const [plans, setPlans] = useState<BillingPlan[]>([]);
  const [loading, setLoading] = useState(true);
  const [billingCycle, setBillingCycle] = useState<'MONTHLY' | 'ANNUAL'>('MONTHLY');
  const [keyMode, setKeyMode] = useState<'BYOK' | 'PLATFORM'>('BYOK');
  const [showInr, setShowInr] = useState(true); // default to INR

  // Checkout flow state
  const [selectedPlanId, setSelectedPlanId] = useState<string | null>(null);
  const [showDisclaimer, setShowDisclaimer] = useState(false);
  const [checkoutData, setCheckoutData] = useState<SubscriptionInitResult | null>(null);
  const [checkoutError, setCheckoutError] = useState<string | null>(null);

  useEffect(() => {
    fetchPlans()
      .then(setPlans)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  // Filter plans by selected cycle and key mode
  const filteredPlans = plans.filter(p =>
    p.tier !== 'FREE' &&
    p.period === billingCycle &&
    p.keyMode === keyMode
  );

  // ─── Checkout Flow ───

  const handleSelectPlan = (planId: string) => {
    setSelectedPlanId(planId);
    setShowDisclaimer(true);
    setCheckoutError(null);
  };

  const handleDisclaimerAccept = async () => {
    if (!selectedPlanId) return;
    setShowDisclaimer(false);

    try {
      // Determine gateway: use Stripe for non-INR, Razorpay for INR
      const gateway = showInr ? 'RAZORPAY' : 'STRIPE';
      const result = await subscribe(
        selectedPlanId,
        gateway,
        gateway === 'STRIPE' ? `${window.location.origin}/billing?status=success` : undefined,
        gateway === 'STRIPE' ? `${window.location.origin}/billing?status=cancelled` : undefined,
      );

      if (result.gateway === 'STRIPE' && result.checkoutUrl) {
        // Redirect to Stripe Checkout
        window.location.href = result.checkoutUrl;
        return;
      }

      // Razorpay flow — open client-side SDK
      setCheckoutData(result);
    } catch (err: any) {
      setCheckoutError(err?.message || 'Failed to create subscription');
      setSelectedPlanId(null);
    }
  };

  const handleCheckoutSuccess = async (response: any) => {
    try {
      await verifyPayment(
        response.razorpay_subscription_id,
        response.razorpay_payment_id,
        response.razorpay_signature
      );
      // Success! Redirect to dashboard
      navigate('/dashboard?billing=success');
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
    <div className="max-w-5xl mx-auto px-4 py-8 sm:py-12">
      <div className="text-center mb-10">
        <h1 className="text-2xl sm:text-3xl font-bold text-primary">Simple, transparent pricing</h1>
        <p className="mt-3 text-secondary">No credits. No surprises. You know exactly what you're getting.</p>
      </div>

      {/* Toggles */}
      <div className="flex flex-col sm:flex-row justify-center gap-4 sm:gap-6 mb-10 items-center">
        {/* Billing cycle toggle */}
        <div className="flex items-center gap-2 bg-[var(--gray-200)] rounded-sm p-1">
          <button
            onClick={() => setBillingCycle('MONTHLY')}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              billingCycle === 'MONTHLY' ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            Monthly
          </button>
          <button
            onClick={() => setBillingCycle('ANNUAL')}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              billingCycle === 'ANNUAL' ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            Annual <span className="text-accent-blue text-xs ml-1">Save 20%</span>
          </button>
        </div>

        {/* Key mode toggle */}
        <div className="flex items-center gap-2 bg-[var(--gray-200)] rounded-sm p-1">
          <button
            onClick={() => setKeyMode('BYOK')}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              keyMode === 'BYOK' ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            BYOK
          </button>
          <button
            onClick={() => setKeyMode('PLATFORM')}
            className={`px-4 py-1.5 rounded-md text-sm font-medium transition ${
              keyMode === 'PLATFORM' ? 'bg-[var(--gray-300)] text-primary' : 'text-muted'
            }`}
          >
            Platform Keys
          </button>
        </div>
      </div>

      {/* Error message */}
      {checkoutError && (
        <div className="mb-6 p-4 bg-red-50 border border-red-200 text-red-700 rounded-sm text-sm text-center">
          {checkoutError}
        </div>
      )}

      {/* Plan cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 max-w-3xl mx-auto">
        {filteredPlans.map(plan => (
          <PlanCard
            key={plan.id}
            plan={plan}
            isCurrentPlan={false}  // TODO: compare with user's current plan
            showInr={showInr}
            onSelect={handleSelectPlan}
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
        <p><strong>BYOK:</strong> Bring your own Anthropic/OpenAI keys. You pay LLM providers directly. More runs, lower price.</p>
        <p><strong>Platform Keys:</strong> We handle everything. Fewer runs due to LLM costs, but zero setup required.</p>
      </div>

      {/* Disclaimer Modal */}
      <DisclaimerModal
        isOpen={showDisclaimer}
        onAccept={handleDisclaimerAccept}
        onCancel={() => { setShowDisclaimer(false); setSelectedPlanId(null); }}
      />

      {/* Razorpay Checkout (renders nothing — opens modal overlay) */}
      {checkoutData && (
        <RazorpayCheckout
          subscriptionId={checkoutData.gatewaySubscriptionId}
          razorpayKeyId={checkoutData.razorpayKeyId}
          onSuccess={handleCheckoutSuccess}
          onFailure={handleCheckoutFailure}
          userName={user ? `${user.firstName} ${user.lastName}` : undefined}
          userEmail={user?.email}
        />
      )}
    </div>
  );
}
