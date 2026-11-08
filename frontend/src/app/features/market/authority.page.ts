import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideExternalLink, LucideLandmark } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AuthorityProfile, MarketService } from '../../core/api/market.service';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { MARKET_UI } from './market-ui';

@Component({
  selector: 'eti-authority-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent, DeadlineComponent,
    LucideExternalLink, LucideLandmark, ...MARKET_UI],
  template: `
    @let lang = language.current();
    <eti-api-error [error]="error()" />
    @if (loading()) {
      <div class="space-y-3"><eti-skeleton size="h-8 w-1/2" /><eti-skeleton size="h-24 w-full" /></div>
    } @else if (p(); as a) {
      <div class="flex flex-wrap items-start gap-4">
        <span class="flex size-11 items-center justify-center rounded-lg bg-accent-soft text-accent" aria-hidden="true"><svg lucideLandmark class="size-5"></svg></span>
        <div class="min-w-0 flex-1">
          <p class="eti-overline">{{ 'market.authority.kicker' | transloco }}</p>
          <h1 class="eti-h1 mt-1">{{ a.name }}</h1>
          <p class="eti-subtitle mt-2">
            @if (a.city) { {{ a.city }}, } {{ a.countryCode | country: lang }}
            @if (a.nationalId) { · <span class="font-mono text-xs">{{ a.nationalId }}</span> }
            @if (a.website) { · <a [href]="a.website" target="_blank" rel="noopener noreferrer nofollow" class="eti-link">{{ 'market.authority.website' | transloco }} <svg lucideExternalLink class="inline size-3" aria-hidden="true"></svg></a> }
          </p>
        </div>
        <a routerLink="/search" [queryParams]="{ authority: a.name }" class="eti-btn-secondary">{{ 'market.authority.allTenders' | transloco }}</a>
      </div>

      <section class="eti-card mt-7 grid grid-cols-3 divide-x divide-border" [attr.aria-label]="'market.authority.activity' | transloco">
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.authority.published' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ a.tendersPublished.toLocaleString(lang) }}</p></div>
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.authority.open' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ a.openNow.toLocaleString(lang) }}</p></div>
        <div class="p-4 sm:p-5"><p class="text-[11px] text-ink-muted sm:text-xs">{{ 'market.authority.cancelled' | transloco }}</p><p class="eti-kpi mt-2 text-xl sm:text-[29px]">{{ a.cancelled.toLocaleString(lang) }}</p></div>
      </section>

      <section class="mt-7" aria-labelledby="comp-h">
        <h2 id="comp-h" class="eti-h2 mb-3">{{ 'market.authority.competition' | transloco }}</h2>
        <eti-competition-stats [stats]="a.stats" />
      </section>

      <div class="mt-7 grid gap-5 xl:grid-cols-2">
        <section class="eti-card overflow-hidden" aria-labelledby="win-h">
          <h2 id="win-h" class="eti-h2 px-5 pt-5 pb-3">{{ 'market.authority.winners' | transloco }}</h2>
          <eti-winners-table [winners]="a.topWinners" />
        </section>
        <section class="eti-card p-5" aria-labelledby="open-h">
          <h2 id="open-h" class="eti-h2">{{ 'market.authority.openTenders' | transloco }}</h2>
          @if (!a.openTenders.length) {
            <p class="mt-2 text-xs text-ink-muted">{{ 'market.authority.noOpen' | transloco }}</p>
          } @else {
            <ul class="mt-2 divide-y divide-border">
              @for (t of a.openTenders; track t.id) {
                <li class="flex items-start justify-between gap-3 py-3 text-xs">
                  <a [routerLink]="['/tenders', t.id]" class="line-clamp-2 font-semibold text-ink hover:text-accent">{{ t.title }}</a>
                  <span class="shrink-0 text-right"><eti-deadline [iso]="t.submissionDeadline" [locale]="lang" [stacked]="true" [dateOnly]="true" /></span>
                </li>
              }
            </ul>
          }
          @if (a.topCpv.length) {
            <h3 class="eti-overline mt-5">{{ 'market.authority.buys' | transloco }}</h3>
            <ul class="mt-2 flex flex-wrap gap-1.5">
              @for (c of a.topCpv; track c.key) {
                <li class="rounded bg-canvas px-2 py-1 text-[11px] text-ink-soft">{{ c.label ?? c.key }} · {{ c.count }}</li>
              }
            </ul>
          }
        </section>
      </div>

      <section class="eti-card mt-5 p-5" aria-labelledby="aw-h">
        <h2 id="aw-h" class="eti-h2">{{ 'market.authority.recent' | transloco }}</h2>
        <eti-awards-list [awards]="a.recentAwards" [showAuthority]="false" />
      </section>
      <div class="mt-4"><eti-scope-note [scope]="a.scope" /></div>
    }
  `,
})
export class AuthorityPage {
  readonly id = input.required<string>();
  private readonly api = inject(MarketService);
  private readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly p = signal<AuthorityProfile | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const id = this.id();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.api.authority(orgId, id, lang).subscribe({
          next: (a) => { this.p.set(a); this.loading.set(false); },
          error: (err: unknown) => { this.error.set(toApiError(err)); this.loading.set(false); },
        }));
      }
    });
  }
}
