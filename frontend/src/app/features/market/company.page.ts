import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideBuilding2, LucideEye, LucideEyeOff } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { CompanyProfile, MarketService } from '../../core/api/market.service';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { MARKET_UI } from './market-ui';

@Component({
  selector: 'eti-company-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent, LucideBuilding2,
    LucideEye, LucideEyeOff, ...MARKET_UI],
  template: `
    @let lang = language.current();
    <eti-api-error [error]="error()" />
    @if (loading()) {
      <div class="space-y-3"><eti-skeleton size="h-8 w-1/2" /><eti-skeleton size="h-24 w-full" /></div>
    } @else if (p(); as c) {
      <div class="flex flex-wrap items-start gap-4">
        <span class="flex size-11 items-center justify-center rounded-lg bg-accent-soft text-accent" aria-hidden="true"><svg lucideBuilding2 class="size-5"></svg></span>
        <div class="min-w-0 flex-1">
          <p class="eti-overline">{{ 'market.company.kicker' | transloco }}</p>
          <h1 class="eti-h1 mt-1">{{ c.name }}</h1>
          <p class="eti-subtitle mt-2">
            {{ c.countryCode | country: lang }}
            @if (c.nationalId) { · <span class="font-mono text-xs">{{ c.nationalId }}</span> }
            @if (c.sme === true) { · {{ 'market.company.sme' | transloco }} }
          </p>
        </div>
        @if (c.watched) {
          <button type="button" class="eti-btn-secondary" [disabled]="busy()" (click)="toggle(c)">
            <svg lucideEyeOff class="size-3.5" aria-hidden="true"></svg>{{ 'market.company.unwatch' | transloco }}
          </button>
        } @else {
          <button type="button" class="eti-btn-primary" [disabled]="busy()" (click)="toggle(c)">
            <svg lucideEye class="size-3.5" aria-hidden="true"></svg>{{ 'market.company.watch' | transloco }}
          </button>
        }
      </div>
      <eti-api-error class="mt-3 block" [error]="actionError()" />
      @if (c.watched) {
        <p class="mt-3 text-xs text-ink-muted">{{ 'market.company.watchedNote' | transloco }}</p>
      }

      <section class="eti-card mt-7 grid grid-cols-3 divide-x divide-border">
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.company.wins' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ c.wins.toLocaleString(lang) }}</p></div>
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.company.total' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ c.totalEur ? (c.totalEur | money: 'EUR' : lang) : '—' }}</p></div>
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.company.offers' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ c.medianOffers ?? '—' }}</p></div>
      </section>

      <div class="mt-7 grid gap-5 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <section class="eti-card p-5" aria-labelledby="wins-h">
          <h2 id="wins-h" class="eti-h2">{{ 'market.company.recent' | transloco }}</h2>
          <eti-awards-list [awards]="c.recentWins" />
        </section>
        <div class="space-y-5">
          <section class="eti-card p-5" aria-labelledby="auth-h">
            <h2 id="auth-h" class="eti-h2">{{ 'market.company.authorities' | transloco }}</h2>
            <ul class="mt-2 divide-y divide-border text-xs">
              @for (a of c.topAuthorities; track a.id) {
                <li class="flex justify-between gap-3 py-2.5">
                  <a [routerLink]="['/authorities', a.id]" class="min-w-0 truncate font-medium text-ink hover:text-accent" [title]="a.name">{{ a.name }}</a>
                  <span class="eti-num shrink-0 text-ink-muted">{{ a.count }}</span>
                </li>
              } @empty {
                <li class="py-2 text-ink-muted">{{ 'market.noAwards' | transloco }}</li>
              }
            </ul>
          </section>
          <section class="eti-card p-5" aria-labelledby="cpv-h">
            <h2 id="cpv-h" class="eti-h2">{{ 'market.company.industries' | transloco }}</h2>
            <ul class="mt-2 flex flex-wrap gap-1.5">
              @for (b of c.topCpv; track b.key) {
                <li class="rounded bg-canvas px-2 py-1 text-[11px] text-ink-soft">{{ b.label ?? b.key }} · {{ b.count }}</li>
              }
            </ul>
            @if (c.countries.length > 1) {
              <h3 class="eti-overline mt-4">{{ 'market.company.countries' | transloco }}</h3>
              <p class="mt-1 text-xs text-ink-soft">
                @for (b of c.countries; track b.key; let last = $last) { {{ b.key | country: lang }} ({{ b.count }})@if (!last) {, } }
              </p>
            }
          </section>
        </div>
      </div>
      <div class="mt-4"><eti-scope-note [scope]="c.scope" /></div>
    }
  `,
})
export class CompanyPage {
  readonly id = input.required<string>();
  private readonly api = inject(MarketService);
  private readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly p = signal<CompanyProfile | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const id = this.id();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.api.company(orgId, id, lang).subscribe({
          next: (c) => { this.p.set(c); this.loading.set(false); },
          error: (err: unknown) => { this.error.set(toApiError(err)); this.loading.set(false); },
        }));
      }
    });
  }

  protected toggle(c: CompanyProfile): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    const call = c.watched ? this.api.unwatch(orgId, c.id) : this.api.watch(orgId, c.id);
    call.subscribe({
      next: () => { this.p.set({ ...c, watched: !c.watched }); this.busy.set(false); },
      error: (err: unknown) => { this.actionError.set(toApiError(err)); this.busy.set(false); },
    });
  }
}
