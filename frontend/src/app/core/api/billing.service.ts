import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type PlanCode = 'FREE' | 'STARTER' | 'PRO' | 'BUSINESS' | 'ENTERPRISE';
export type BillingInterval = 'MONTH' | 'YEAR';

/** Limits enforced by the backend; null = unlimited. */
export interface PlanLimits {
  seats: number | null;
  savedSearchesPerUser: number;
  aiAnalysesPerMonth: number;
  exportsPerMonth: number | null;
  instantEmails: boolean;
}

/** As configured in Stripe; amounts in minor units (grosze/cents). taxBehavior "exclusive" = net + VAT. */
export interface PlanPrice {
  interval: BillingInterval;
  unitAmount: number;
  currency: string;
  taxBehavior?: string;
}

export interface PlanInfo {
  code: PlanCode;
  purchasable: boolean;
  limits: PlanLimits;
  prices: PlanPrice[];
}

export interface PlansResponse {
  configured: boolean;
  plans: PlanInfo[];
}

export interface Meter {
  used: number;
  limit: number | null;
}

export interface SubscriptionInfo {
  plan?: PlanCode;
  interval?: BillingInterval;
  status: string;
  currentPeriodEnd?: string;
  cancelAtPeriodEnd: boolean;
  cancelAt?: string;
  trialEnd?: string;
}

export interface Invoice {
  number?: string;
  status: string;
  currency: string;
  amountDue: number;
  amountPaid: number;
  tax?: number;
  periodStart?: string;
  periodEnd?: string;
  paidAt?: string;
  hostedInvoiceUrl?: string;
  pdfUrl?: string;
  createdAt: string;
}

export interface BillingOverview {
  configured: boolean;
  plan: PlanCode;
  planSource: 'DEFAULT' | 'STRIPE' | 'MANUAL';
  canManage: boolean;
  hasCustomer: boolean;
  subscription?: SubscriptionInfo;
  usage: { seats: Meter; savedSearches: Meter; aiAnalyses: Meter; exports: Meter; instantEmails: boolean };
  invoices: Invoice[];
}

@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);

  plans(): Observable<PlansResponse> {
    return this.http.get<PlansResponse>('/api/v1/billing/plans');
  }

  overview(orgId: string): Observable<BillingOverview> {
    return this.http.get<BillingOverview>(`/api/v1/organizations/${orgId}/billing`);
  }

  checkout(orgId: string, plan: PlanCode, interval: BillingInterval, lang: string): Observable<{ url: string }> {
    return this.http.post<{ url: string }>(`/api/v1/organizations/${orgId}/billing/checkout`, { plan, interval }, {
      params: { lang },
    });
  }

  portal(orgId: string, lang: string): Observable<{ url: string }> {
    return this.http.post<{ url: string }>(`/api/v1/organizations/${orgId}/billing/portal`, null, { params: { lang } });
  }
}
