import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { MarketService, TenderMarket } from '../../core/api/market.service';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { MARKET_UI, pct } from './market-ui';

/** On the tender page: who wins similar contracts, for how much and against how many competitors. */
@Component({
  selector: 'eti-tender-market',
  imports: [RouterLink, TranslocoPipe, ApiErrorAlertComponent, SkeletonComponent, ...MARKET_UI],
  template: `
    <section class="eti-card p-5" aria-labelledby="market-heading">
      <h2 id="market-heading" class="eti-h2">{{ 'market.panel.title' | transloco }}</h2>
      <p class="eti-subtitle mt-1">{{ 'market.panel.subtitle' | transloco }}</p>
      <eti-api-error class="mt-3 block" [error]="error()" />
      @if (loading()) {
        <div class="mt-4 space-y-3"><eti-skeleton size="h-16 w-full" /><eti-skeleton size="h-4 w-2/3" /></div>
      } @else if (data(); as m) {
        <div class="mt-4"><eti-competition-stats [stats]="m.stats" /></div>
        @if (m.stats.priceRatioMedian) {
          <p class="mt-2 text-xs text-ink-soft">{{ 'market.panel.ratioHint' | transloco: { p: pct(m.stats.priceRatioMedian, language.current()) } }}</p>
        }
        <div class="mt-5 grid gap-6">
          <div>
            <h3 class="eti-overline">{{ 'market.panel.topWinners' | transloco }}</h3>
            <div class="mt-2 overflow-hidden rounded-[var(--radius-control)] border border-border"><eti-winners-table [winners]="m.topWinners.slice(0, 6)" /></div>
          </div>
          <div>
            <h3 class="eti-overline">{{ 'market.panel.recent' | transloco }}</h3>
            <div class="mt-1"><eti-awards-list [awards]="m.recentAwards.slice(0, 6)" /></div>
          </div>
        </div>
        @if (m.authority; as a) {
          <div class="mt-5 rounded-[var(--radius-control)] bg-canvas p-4">
            <div class="flex flex-wrap items-baseline justify-between gap-2">
              <h3 class="text-[13px] font-semibold">{{ 'market.panel.atAuthority' | transloco: { name: a.name } }}</h3>
              <a [routerLink]="['/authorities', a.id]" class="eti-link text-xs">{{ 'market.panel.authorityProfile' | transloco }}</a>
            </div>
            <p class="mt-1 text-xs text-ink-soft">
              {{ 'market.panel.authorityLine' | transloco: { n: a.stats.awardedTenders } }}
              @if (a.stats.medianOffers) { · {{ 'market.panel.authorityOffers' | transloco: { n: a.stats.medianOffers } }} }
            </p>
            @if (a.topWinners.length) {
              <p class="mt-2 text-xs">
                <span class="text-ink-muted">{{ 'market.panel.mostOften' | transloco }}:&nbsp;</span>
                @for (w of a.topWinners; track w.id; let last = $last) {
                  <a [routerLink]="['/companies', w.id]" class="font-medium text-ink hover:text-accent">{{ w.name }}</a> ({{ w.wins }})@if (!last) {, }
                }
              </p>
            }
          </div>
        }
        <div class="mt-4"><eti-scope-note [scope]="m.scope" /></div>
      }
    </section>
  `,
})
export class TenderMarketComponent {
  readonly tenderId = input.required<string>();
  private readonly api = inject(MarketService);
  private readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly data = signal<TenderMarket | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly pct = pct;

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const id = this.tenderId();
      const lang = this.language.current();
      if (!orgId) {
        return;
      }
      untracked(() => {
        this.loading.set(true);
        this.error.set(null);
        this.api.tender(orgId, id, lang).subscribe({
          next: (m) => {
            this.data.set(m);
            this.loading.set(false);
          },
          error: (err: unknown) => {
            this.error.set(toApiError(err));
            this.loading.set(false);
          },
        });
      });
    });
  }
}
