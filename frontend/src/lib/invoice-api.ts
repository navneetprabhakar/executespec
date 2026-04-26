import { req } from './api';

// ── Types ────────────────────────────────────────────────────────────────────

export interface BillingProfile {
  id?: string;
  userId?: string;
  orgId?: string;
  legalName?: string;
  gstin?: string;
  pan?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  stateName?: string;
  stateCode?: string;
  postalCode?: string;
  country: string;
  preferredCurrency: string;
}

export interface Invoice {
  id: string;
  invoiceNumber: string;
  financialYear: string;
  userId: string;
  orgId?: string;
  invoiceType: 'SUBSCRIPTION' | 'TOPUP' | 'CREDIT_NOTE';
  status: string;
  currency: string;
  subtotal: number;
  cgstAmount: number;
  sgstAmount: number;
  igstAmount: number;
  totalTax: number;
  totalAmount: number;
  buyerName?: string;
  buyerGstin?: string;
  sellerName?: string;
  gatewayPaymentId?: string;
  invoiceDate: string;
  dueDate?: string;
  description?: string;
  createdAt: string;
}

export interface InvoiceLineItem {
  id: string;
  description: string;
  quantity?: number;
  unitPrice: number;
  amount: number;
}

export interface InvoiceDetail {
  invoice: Invoice;
  lineItems: InvoiceLineItem[];
}

export interface SellerConfig {
  id: string;
  gstin?: string;
  legalName?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  stateName?: string;
  stateCode?: string;
  postalCode?: string;
  pan?: string;
}

export interface RevenueMetrics {
  totalCollections: number;
  totalRevenue: number;
  totalTax: number;
  mrr: number;
  arr: number;
}

export interface GstSummary {
  cgstCollected: number;
  sgstCollected: number;
  igstCollected: number;
  totalGst: number;
  financialYear: string;
  quarter: number;
}

export interface TrialBalanceEntry {
  account: string;
  debitTotal: number;
  creditTotal: number;
}

interface Page<T> {
  content: T[];
  totalPages: number;
  totalElements: number;
}

// ── Billing Profile ──────────────────────────────────────────────────────────

export const getBillingProfile = () =>
  req<BillingProfile>('/billing/profile');

export const updateBillingProfile = (profile: BillingProfile) =>
  req<BillingProfile>('/billing/profile', {
    method: 'PUT',
    body: JSON.stringify(profile),
  });

// ── Invoices (own) ───────────────────────────────────────────────────────────

export const getMyInvoices = (page = 0, size = 20) =>
  req<Page<Invoice>>(`/billing/invoices?page=${page}&size=${size}`);

export const getInvoiceDetail = (id: string) =>
  req<InvoiceDetail>(`/billing/invoices/${id}`);

// ── Admin invoices ───────────────────────────────────────────────────────────

export const getAllInvoices = (page = 0, size = 20) =>
  req<Page<Invoice>>(`/admin/invoices?page=${page}&size=${size}`);

export const resendInvoice = (invoiceId: string) =>
  req<void>(`/admin/invoices/${invoiceId}/send`, { method: 'POST' });

// ── Seller config ────────────────────────────────────────────────────────────

export const getSellerConfig = () =>
  req<SellerConfig>('/admin/seller-config');

export const updateSellerConfig = (config: SellerConfig) =>
  req<SellerConfig>('/admin/seller-config', {
    method: 'PUT',
    body: JSON.stringify(config),
  });

// ── Accounting ───────────────────────────────────────────────────────────────

export const getRevenueMetrics = (from: string, to: string) =>
  req<RevenueMetrics>(`/accounting/revenue?from=${from}&to=${to}`);

export const getGstSummary = (fy: string, quarter: number) =>
  req<GstSummary>(`/accounting/gst-summary?fy=${fy}&quarter=${quarter}`);

export const getTrialBalance = (from: string, to: string) =>
  req<TrialBalanceEntry[]>(`/accounting/trial-balance?from=${from}&to=${to}`);

// ── PDF downloads ────────────────────────────────────────────────────────────

export async function downloadInvoicePdf(id: string, invoiceNumber: string, admin = false) {
  const url = admin ? `/api/admin/invoices/${id}/pdf` : `/api/billing/invoices/${id}/pdf`;
  const token = localStorage.getItem('access_token');
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    credentials: 'include',
  });
  if (!res.ok) throw new Error('PDF download failed');
  const blob = await res.blob();
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `${invoiceNumber}.pdf`;
  a.click();
  URL.revokeObjectURL(a.href);
}

export function getGstr1ExportUrl(fy: string, quarter: number): string {
  return `/api/accounting/gstr1-export?fy=${fy}&quarter=${quarter}`;
}

// ── Helpers ──────────────────────────────────────────────────────────────────

export function formatPaise(paise: number): string {
  const rupees = paise / 100;
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
  }).format(rupees);
}
