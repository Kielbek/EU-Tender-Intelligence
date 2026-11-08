import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BillingOverview, PlansResponse } from '../../core/api/billing.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { translocoTesting } from '../../testing/transloco-testing';
import { BillingPage } from './billing.page';

const limits = { seats: 1, savedSearchesPerUser: 3, aiAnalysesPerMonth: 2, exportsPerMonth: 3, instantEmails: false,
  marketHistoryMonths: 3, watchedCompetitors: 1 };
const plans = (configured: boolean): PlansResponse => ({
  configured,
  plans: [
    { code: 'FREE', purchasable: false, limits, prices: [] },
    { code: 'STARTER', purchasable: configured, limits: { ...limits, seats: 3, instantEmails: true },
      prices: configured ? [{ interval: 'MONTH', unitAmount: 24900, currency: 'pln', taxBehavior: 'exclusive' }] : [] },
  ],
});
const overview = (o: Partial<BillingOverview>): BillingOverview => ({
  configured: true, plan: 'FREE', planSource: 'DEFAULT', canManage: true, hasCustomer: false, invoices: [],
  usage: { seats: { used: 1, limit: 1 }, savedSearches: { used: 0, limit: 3 }, aiAnalyses: { used: 0, limit: 2 },
    exports: { used: 3, limit: 3 }, instantEmails: false },
  ...o,
});

function setup(p: PlansResponse, o: BillingOverview) {
  const current = signal({ id: 'org-1', name: 'Acme', role: 'COMPANY_ADMIN', planCode: o.plan });
  TestBed.configureTestingModule({
    imports: [BillingPage, translocoTesting()],
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: OrgContextService, useValue: { current, currentId: computed(() => current().id), reload: () => undefined } }],
  });
  const fixture = TestBed.createComponent(BillingPage);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  http.expectOne('/api/v1/billing/plans').flush(p);
  http.expectOne('/api/v1/organizations/org-1/billing').flush(o);
  fixture.detectChanges();
  return { fixture, http, el: fixture.nativeElement as HTMLElement };
}

describe('BillingPage', () => {
  it('shows Stripe prices net of VAT and offers checkout to admins', () => {
    const { el } = setup(plans(true), overview({}));
    expect(el.textContent).toContain('249,00');
    expect(el.textContent).toContain('netto + VAT');
    expect(el.textContent).toContain('Twój plan');
    const buttons = [...el.querySelectorAll('article button')].map((b) => b.textContent!.trim());
    expect(buttons).toEqual(['Wybierz Starter']);
    // A used-up limit is shown as full.
    expect(el.querySelector('[aria-valuenow="3"][aria-valuemax="3"]')).not.toBeNull();
  });

  it('has no purchase buttons when payments are not configured or for members', () => {
    let r = setup(plans(false), overview({ configured: false }));
    expect(r.el.textContent).toContain('Płatności online nie są jeszcze uruchomione');
    expect(r.el.querySelectorAll('article button').length).toBe(0);
    TestBed.resetTestingModule();
    r = setup(plans(true), overview({ canManage: false }));
    expect(r.el.querySelectorAll('article button').length).toBe(0);
    expect(r.el.textContent).toContain('Planem i płatnościami zarządza administrator');
  });

  it('with a subscription, plan changes go through the customer portal', () => {
    const { el } = setup(plans(true), overview({ plan: 'STARTER', hasCustomer: true,
      subscription: { plan: 'STARTER', interval: 'MONTH', status: 'past_due', cancelAtPeriodEnd: false, currentPeriodEnd: '2026-12-08T00:00:00Z' } }));
    expect(el.textContent).toContain('Zaległa płatność');
    expect(el.textContent).toContain('Zarządzaj płatnościami');
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Stripe ponawia');
  });
});
