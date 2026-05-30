import {
  ApiError,
  BASE,
  BillingOverviewData,
  PagedResponse,
  PaymentAuditEntry,
  PricingRow,
  TopupPackageAdmin,
  authHeader,
  parseErrorMessage,
  req,
} from './core';

export const getPricing = () => req<PricingRow[]>('/pricing');

export const updatePricing = (provider: string, model: string, body: Partial<PricingRow>) =>
  req<PricingRow>(`/pricing/${provider}/${model}`, {method: 'PUT', body: JSON.stringify(body)});

export const adminListTopupPackages = () => req<TopupPackageAdmin[]>('/admin/topup-packages');
export const adminCreateTopupPackage = (body: {
  name: string;
  runCount: number;
  amountInr: number;
  amountUsd?: number;
  sortOrder?: number;
}) => req<TopupPackageAdmin>('/admin/topup-packages', {method: 'POST', body: JSON.stringify(body)});
export const adminUpdateTopupPackage = (id: string, body: Partial<TopupPackageAdmin>) =>
  req<TopupPackageAdmin>(`/admin/topup-packages/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  });
export const adminDeleteTopupPackage = (id: string) =>
  req<{message: string}>(`/admin/topup-packages/${id}`, {method: 'DELETE'});

export const getAnalyticsBilling = (entityType: string, entityId: string) =>
  req<BillingOverviewData>(
    `/admin/analytics/billing?entityType=${entityType}&entityId=${entityId}`,
  );

export const updateModelPricing = (
  provider: string,
  model: string,
  data: {inputCostPer1m: number; outputCostPer1m: number; notes?: string},
) => req<PricingRow>(`/pricing/${provider}/${model}`, {method: 'PUT', body: JSON.stringify(data)});

export const getAllPricing = () => req<PricingRow[]>('/pricing');

export const adminListPaymentAudit = (page = 0, size = 50, orgId?: string, userId?: string) => {
  const params = new URLSearchParams({page: String(page), size: String(size)});
  if (orgId) params.set('orgId', orgId);
  if (userId) params.set('userId', userId);
  return req<PagedResponse<PaymentAuditEntry>>(`/admin/payment-audit?${params}`);
};

export const adminExportPaymentAuditCsv = async () => {
  const res = await fetch(`${BASE}/admin/payment-audit/export`, {
    headers: {...authHeader()},
  });
  if (!res.ok) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorMessage(res.status, raw));
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'payment-audit.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
};
