import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CompetitionStats, TenderMarket } from '../../core/api/market.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { translocoTesting } from '../../testing/transloco-testing';
import { CompetitionStatsComponent, pct } from './market-ui';
import { TenderMarketComponent } from './tender-market.component';

const stats = (s: Partial<CompetitionStats>): CompetitionStats => ({
  awardedTenders: 12, medianOffers: 3, singleBidShare: 0.167, offersSample: 12, priceRatioP25: 0.85,
  priceRatioMedian: 0.9, priceRatioP75: 1.1, priceRatioSample: 10, medianAwardEur: 170049, ...s,
});

describe('Market UI', () => {
  beforeEach(() => {
    const current = signal({ id: 'org-1', name: 'Acme', role: 'COMPANY_ADMIN', planCode: 'FREE' });
    TestBed.configureTestingModule({
      imports: [translocoTesting()],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: OrgContextService, useValue: { current, currentId: computed(() => current().id) } }],
    });
  });

  it('formats ratios as percent', () => {
    expect(pct(0.9, 'pl')).toBe('90%');
    expect(pct(null, 'pl')).toBe('');
  });

  it('says "too little data" instead of showing statistics from a handful of awards', () => {
    const f = TestBed.createComponent(CompetitionStatsComponent);
    f.componentRef.setInput('stats', stats({ awardedTenders: 3, medianOffers: null, priceRatioMedian: null, medianAwardEur: null, priceRatioSample: 2 }));
    f.detectChanges();
    const text = (f.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('3');
    expect(text.match(/Za mało danych/g)?.length).toBe(3);
  });

  it('shows the tender market panel with winners linking to company profiles', () => {
    const f = TestBed.createComponent(TenderMarketComponent);
    f.componentRef.setInput('tenderId', 't-1');
    f.detectChanges();
    const m: TenderMarket = {
      scope: { countryCode: 'PL', cpvPrefix: '45233', cpvLabel: 'Roboty drogowe', months: 3, since: '2026-08-08', dataSince: '2026-08-01' },
      stats: stats({}),
      topWinners: [{ id: 'c-1', name: 'Alfa Drogi', countryCode: 'PL', wins: 7, totalEur: 705000, lastWin: '2026-11-01' }],
      recentAwards: [{ tenderId: 't-2', title: 'Remont drogi', countryCode: 'PL', winnerId: 'c-1', winnerName: 'Alfa Drogi', valueEur: 80000, offers: 3, awardDate: '2026-11-01' }],
      authority: { id: 'a-1', name: 'Gmina Testowo', stats: stats({ awardedTenders: 4 }), topWinners: [] },
    };
    TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/v1/organizations/org-1/market/tenders/t-1').flush(m);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('90%');
    expect(el.textContent).toContain('ok. 90% wartości szacunkowej');
    expect(el.querySelector('a[href="/companies/c-1"]')?.textContent).toContain('Alfa Drogi');
    expect(el.querySelector('a[href="/authorities/a-1"]')).not.toBeNull();
    // FREE sees 3 months and a link to the plans.
    expect(el.querySelector('a[href="/billing"]')).not.toBeNull();
  });
});
