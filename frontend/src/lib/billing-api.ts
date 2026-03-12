// Billing API client — Razorpay subscription endpoints

import { req } from './api';

// ─── Types ───

export interface BillingPlan {
  id: string;
  name: string;
  tier: string;   // FREE, STARTER, PRO, ENTERPRISE
  keyMode: string; // BYOK, PLATFORM, NONE
  period: string;  // MONTHLY, ANNUAL, NONE
  baseAmountInr: number;  // paise
  baseAmountUsd: number;  // cents
  perSeatInr: number;
  perSeatUsd: number;
  includedSeats: number;
  runLimit: number;
  annualDiscountPct: number;
  founderPricing: boolean;
  active: boolean;
  limits: Record<string, number>;  // { RUNS: 50, PROJECTS: 10, STORAGE_MB: 500 }
}

export interface SubscriptionInfo {
  id: string | null;
  status: string;   // FREE, ACTIVE, PAST_DUE, CANCELLED, DOWNGRADED
  paymentGateway: string;
  currentPeriodEnd: string | null;
  totalSeats: number;
  extraSeats: number;
  cancelAtPeriodEnd: boolean;
  founderPricing: boolean;
  termsVersion: string | null;
}

export interface UsageInfo {
  metric: string;   // RUNS, PROJECTS, STORAGE_MB
  current: number;
  max: number;
  pctUsed: number;
}

export interface TopupInfo {
  id: string;
  quantity: number;
  remaining: number;
  source: string;   // PURCHASE | ADMIN_GRANT
  status: string;   // ACTIVE | EXHAUSTED | EXPIRED
  expiresAt: string | null;
  createdAt: string;
}

export interface BonusRunsInfo {
  available: number;
  topups: TopupInfo[];
}

export interface BillingStatus {
  subscription: SubscriptionInfo;
  plan: BillingPlan | null;
  usage: UsageInfo[];
  bonusRuns: BonusRunsInfo | null;
}

export interface TopupPackage {
  id: string;
  name: string;
  runCount: number;
  amountInr: number;   // paise
  amountUsd: number;   // cents
  active: boolean;
  sortOrder: number;
}

export interface TopupOrderResult {
  orderId: string;
  customerId: string | null;
  razorpayKeyId: string;
  amountPaise: number;
}

export interface SubscriptionInitResult {
  gatewaySubscriptionId: string;
  gatewayCustomerId: string;
  shortUrl: string | null;
  razorpayKeyId: string;
}

// ─── API Functions ───

/**
 * Fetch all active plans. Pass keyMode='BYOK' or 'PLATFORM' to filter.
 */
export const fetchPlans = (keyMode?: string) => {
  const params = keyMode ? `?keyMode=${keyMode}` : '';
  return req<BillingPlan[]>(`/billing/plans${params}`);
};

/**
 * Get current billing status for the authenticated user.
 */
export const fetchBillingStatus = () =>
  req<BillingStatus>('/billing/status');

/**
 * Initiate subscription. Returns Razorpay checkout data.
 */
export const subscribe = (planId: string) =>
  req<SubscriptionInitResult>('/billing/subscribe', {
    method: 'POST',
    body: JSON.stringify({ planId }),
  });

/**
 * Verify payment after Razorpay checkout completes.
 */
export const verifyPayment = (
  razorpaySubscriptionId: string,
  razorpayPaymentId: string,
  razorpaySignature: string,
) =>
  req<BillingStatus>('/billing/verify', {
    method: 'POST',
    body: JSON.stringify({ razorpaySubscriptionId, razorpayPaymentId, razorpaySignature }),
  });

/**
 * Cancel subscription at end of billing period.
 */
export const cancelSubscription = () =>
  req<void>('/billing/cancel', { method: 'POST' });

/**
 * Update seat count (Pro tier only).
 */
export const updateSeats = (totalSeats: number) =>
  req<BillingStatus>('/billing/seats', {
    method: 'POST',
    body: JSON.stringify({ totalSeats }),
  });

// ─── Run Top-Up ───

/**
 * List available top-up packages for purchase.
 */
export const fetchTopupPackages = () =>
  req<TopupPackage[]>('/billing/topup/packages');

/**
 * Initiate a top-up purchase. Returns Razorpay order data for checkout.
 */
export const purchaseTopup = (packageId: string) =>
  req<TopupOrderResult>('/billing/topup/purchase', {
    method: 'POST',
    body: JSON.stringify({ packageId }),
  });

/**
 * Verify top-up payment after Razorpay checkout.
 */
export const verifyTopup = (
  razorpayOrderId: string,
  razorpayPaymentId: string,
  razorpaySignature: string,
) =>
  req<BillingStatus>('/billing/topup/verify', {
    method: 'POST',
    body: JSON.stringify({ razorpayOrderId, razorpayPaymentId, razorpaySignature }),
  });
