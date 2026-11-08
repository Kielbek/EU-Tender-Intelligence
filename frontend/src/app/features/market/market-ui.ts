import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideInfo } from '@lucide/angular';
import { AwardRow, CompetitionStats, MarketScope, WinnerRow } from '../../core/api/market.service';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';

/** Percent with one decimal at most, e.g. 0.8734 → "87%", used for price ratios and shares. */
export function pct(v: number | null | undefined, lang: string, digits = 0): string {
  return v === null || v === undefined ? '' : new Intl.NumberFormat(lang, { style: 'percent', maximumFractionDigits: digits }).format(v);
}

/** Four facts about competition and prices; empty values say "too little data" instead of a guess. */
@Component({
  selector: 'eti-competition-stats',
  imports: [TranslocoPipe, ...FORMAT_PIPES],
  template: `
    @let s = stats();
    @let lang = language.current();
    <dl class="grid grid-cols-2 divide-border overflow-hidden rounded-[var(--radius-card)] border border-border lg:grid-cols-4 lg:divide-x">
      <div class="border-b border-border p-4 lg:border-b-0">
        <dt class="text-[11px] text-ink-muted">{{ 'market.stats.awarded' | transloco }}</dt>
        <dd class="eti-kpi mt-2 text-2xl">{{ s.awardedTenders.toLocaleString(lang) }}</dd>
      </div>
      <div class="border-b border-l border-border p-4 lg:border-b-0 lg:border-l-0">
        <dt class="text-[11px] text-ink-muted">{{ 'market.stats.offers' | transloco }}</dt>
        @if (s.medianOffers !== null && s.medianOffers !== undefined) {
          <dd class="eti-kpi mt-2 text-2xl">{{ s.medianOffers.toLocaleString(lang) }}</dd>
          <dd class="mt-1 text-[11px] text-ink-muted">{{ 'market.stats.singleBid' | transloco: { p: pct(s.singleBidShare, lang) } }}</dd>
        } @else {
          <dd class="mt-2 text-xs text-ink-faint">{{ 'market.tooLittle' | transloco }}</dd>
        }
      </div>
      <div class="p-4">
        <dt class="text-[11px] text-ink-muted">{{ 'market.stats.ratio' | transloco }}</dt>
        @if (s.priceRatioMedian !== null && s.priceRatioMedian !== undefined) {
          <dd class="eti-kpi mt-2 text-2xl">{{ pct(s.priceRatioMedian, lang) }}</dd>
          <dd class="mt-1 text-[11px] text-ink-muted">{{ 'market.stats.ratioRange' | transloco: { from: pct(s.priceRatioP25, lang), to: pct(s.priceRatioP75, lang), n: s.priceRatioSample } }}</dd>
        } @else {
          <dd class="mt-2 text-xs text-ink-faint">{{ 'market.tooLittle' | transloco }}</dd>
        }
      </div>
      <div class="border-l border-border p-4 lg:border-l-0">
        <dt class="text-[11px] text-ink-muted">{{ 'market.stats.medianValue' | transloco }}</dt>
        @if (s.medianAwardEur) {
          <dd class="eti-kpi mt-2 text-2xl">{{ s.medianAwardEur | money: 'EUR' : lang }}</dd>
        } @else {
          <dd class="mt-2 text-xs text-ink-faint">{{ 'market.tooLittle' | transloco }}</dd>
        }
      </div>
    </dl>
  `,
})
export class CompetitionStatsComponent {
  readonly stats = input.required<CompetitionStats>();
  protected readonly language = inject(LanguageService);
  protected readonly pct = pct;
}

@Component({
  selector: 'eti-winners-table',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES],
  template: `
    @let lang = language.current();
    @if (!winners().length) {
      <p class="text-xs text-ink-muted">{{ 'market.noAwards' | transloco }}</p>
    } @else {
      <div class="overflow-x-auto">
        <table class="w-full min-w-[420px] text-xs">
          <thead class="eti-table-head">
            <tr>
              <th scope="col" class="px-3 py-2.5">{{ 'market.col.company' | transloco }}</th>
              <th scope="col" class="px-3 py-2.5 text-right">{{ 'market.col.wins' | transloco }}</th>
              <th scope="col" class="px-3 py-2.5 text-right">{{ 'market.col.total' | transloco }}</th>
              <th scope="col" class="px-3 py-2.5">{{ 'market.col.lastWin' | transloco }}</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-border">
            @for (w of winners(); track w.id) {
              <tr class="hover:bg-subtle">
                <td class="px-3 py-2.5">
                  <a [routerLink]="['/companies', w.id]" class="font-semibold text-ink hover:text-accent">{{ w.name }}</a>
                  @if (w.countryCode) { <span class="ml-1 text-ink-muted">{{ w.countryCode }}</span> }
                </td>
                <td class="eti-num px-3 py-2.5 text-right">{{ w.wins }}</td>
                <td class="eti-num px-3 py-2.5 text-right">{{ w.totalEur ? (w.totalEur | money: 'EUR' : lang) : '—' }}</td>
                <td class="px-3 py-2.5 whitespace-nowrap text-ink-muted">{{ w.lastWin ? (w.lastWin | userDate: lang) : '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class WinnersTableComponent {
  readonly winners = input.required<WinnerRow[]>();
  protected readonly language = inject(LanguageService);
}

@Component({
  selector: 'eti-awards-list',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES],
  template: `
    @let lang = language.current();
    @if (!awards().length) {
      <p class="text-xs text-ink-muted">{{ 'market.noAwards' | transloco }}</p>
    } @else {
      <ul class="divide-y divide-border">
        @for (a of awards(); track a.tenderId + a.winnerId) {
          <li class="grid gap-1 py-3 text-xs sm:grid-cols-[minmax(0,1fr)_9rem_6rem] sm:items-center sm:gap-3">
            <div class="min-w-0">
              <a [routerLink]="['/tenders', a.tenderId]" class="line-clamp-2 font-semibold text-ink hover:text-accent" [title]="a.title">{{ a.title }}</a>
              <p class="mt-1 truncate text-[11px] text-ink-muted">
                <a [routerLink]="['/companies', a.winnerId]" class="text-ink-soft hover:text-accent">{{ a.winnerName }}</a>
                @if (showAuthority() && a.authorityId) {
                  · <a [routerLink]="['/authorities', a.authorityId]" class="hover:text-accent">{{ a.authorityName }}</a>
                }
              </p>
            </div>
            <div class="eti-num font-medium sm:text-right">{{ a.valueEur ? (a.valueEur | money: 'EUR' : lang) : '—' }}</div>
            <div class="text-[11px] text-ink-muted sm:text-right">
              {{ a.awardDate ? (a.awardDate | userDate: lang) : '' }}
              @if (a.offers) { <span class="block">{{ 'market.offersN' | transloco: { n: a.offers } }}</span> }
            </div>
          </li>
        }
      </ul>
    }
  `,
})
export class AwardsListComponent {
  readonly awards = input.required<AwardRow[]>();
  readonly showAuthority = input(true);
  protected readonly language = inject(LanguageService);
}

/** Where the numbers come from and how far back they reach (and that FREE sees 3 months only). */
@Component({
  selector: 'eti-scope-note',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, LucideInfo],
  template: `
    @let s = scope();
    @let lang = language.current();
    <p class="flex items-start gap-2 text-[11px] text-ink-muted">
      <svg lucideInfo class="mt-px size-3.5 shrink-0" aria-hidden="true"></svg>
      <span>
        {{ 'market.scope' | transloco: { since: (s.since | userDate: lang) } }}
        @if (s.countryCode) { · {{ s.countryCode | country: lang }} }
        @if (s.cpvPrefix) { · CPV {{ s.cpvPrefix }}…@if (s.cpvLabel) { ({{ s.cpvLabel }})} }
        @if (s.dataSince) { · {{ 'market.dataSince' | transloco: { date: (s.dataSince | userDate: lang) } }} }
        · {{ 'market.vatNote' | transloco }}
        @if (limited()) {
          · {{ 'market.freeWindow' | transloco }} <a routerLink="/billing" class="eti-link">{{ 'billing.seePlans' | transloco }}</a>
        }
      </span>
    </p>
  `,
})
export class ScopeNoteComponent {
  readonly scope = input.required<MarketScope>();
  protected readonly language = inject(LanguageService);
  private readonly org = inject(OrgContextService);
  protected readonly limited = computed(() => this.scope().months < 24 && (this.org.current()?.planCode ?? 'FREE') === 'FREE');
}

export const MARKET_UI = [CompetitionStatsComponent, WinnersTableComponent, AwardsListComponent, ScopeNoteComponent] as const;
