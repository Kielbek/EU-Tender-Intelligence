import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideBuilding2, LucideLandmark, LucideSearch, LucideTrash } from '@lucide/angular';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of, catchError } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AuthorityHit, CompanyHit, CompetitorsView, MarketService } from '../../core/api/market.service';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/** Followed competitors and a search over companies (winners) and contracting authorities. */
@Component({
  selector: 'eti-competitors-page',
  imports: [FormsModule, RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent,
    LucideBuilding2, LucideLandmark, LucideSearch, LucideTrash],
  template: `
    @let lang = language.current();
    <h1 class="eti-h1">{{ 'market.competitors.title' | transloco }}</h1>
    <p class="eti-subtitle mt-2">{{ 'market.competitors.subtitle' | transloco }}</p>

    <section class="eti-card mt-8" aria-labelledby="find-h">
      <h2 id="find-h" class="sr-only">{{ 'market.competitors.find' | transloco }}</h2>
      <div class="flex flex-wrap gap-2 p-3">
        <label for="market-q" class="sr-only">{{ 'market.competitors.find' | transloco }}</label>
        <div class="flex min-w-[200px] flex-1 items-center gap-3 px-2">
          <svg lucideSearch class="size-4 shrink-0 text-ink-faint" aria-hidden="true"></svg>
          <input id="market-q" type="search" class="w-full bg-transparent py-2 text-[13px] text-ink placeholder:text-ink-faint focus:outline-none"
                 [placeholder]="'market.competitors.placeholder' | transloco" maxlength="200"
                 [(ngModel)]="q" (ngModelChange)="queries.next($event)" />
        </div>
        <div class="flex rounded-[var(--radius-control)] border border-border-strong p-0.5" role="group">
          <button type="button" class="h-8 rounded-[5px] px-3 text-xs font-medium" [attr.aria-pressed]="kind() === 'companies'"
                  [class.bg-ink]="kind() === 'companies'" [class.text-white]="kind() === 'companies'" (click)="setKind('companies')">{{ 'market.competitors.companies' | transloco }}</button>
          <button type="button" class="h-8 rounded-[5px] px-3 text-xs font-medium" [attr.aria-pressed]="kind() === 'authorities'"
                  [class.bg-ink]="kind() === 'authorities'" [class.text-white]="kind() === 'authorities'" (click)="setKind('authorities')">{{ 'market.competitors.authorities' | transloco }}</button>
        </div>
      </div>
      <div class="border-t border-border px-4 py-2.5 text-[11px] text-ink-muted">{{ (kind() === 'companies' ? 'market.competitors.searchHint' : 'market.competitors.searchHintAuthorities') | transloco }}</div>
      @if (q.trim().length >= 2) {
        <ul class="divide-y divide-border border-t border-border" aria-live="polite">
          @if (kind() === 'companies') {
            @for (c of companies(); track c.id) {
              <li class="flex items-center gap-3 px-4 py-3 text-xs hover:bg-subtle">
                <svg lucideBuilding2 class="size-4 shrink-0 text-ink-faint" aria-hidden="true"></svg>
                <a [routerLink]="['/companies', c.id]" class="min-w-0 flex-1 truncate font-semibold text-ink hover:text-accent">{{ c.name }}</a>
                <span class="hidden text-ink-muted sm:inline">{{ c.countryCode }} @if (c.nationalId) { · {{ c.nationalId }} }</span>
                <span class="eti-num shrink-0 text-ink-muted">{{ 'market.competitors.wins' | transloco: { n: c.wins } }}</span>
                @if (!isWatched(c.id)) {
                  <button type="button" class="eti-btn-secondary h-8 px-3 text-xs" (click)="watch(c)">{{ 'market.company.watch' | transloco }}</button>
                }
              </li>
            } @empty {
              <li class="px-4 py-3 text-xs text-ink-muted">{{ 'search.noMatches' | transloco }}</li>
            }
          } @else {
            @for (a of authorities(); track a.id) {
              <li class="flex items-center gap-3 px-4 py-3 text-xs hover:bg-subtle">
                <svg lucideLandmark class="size-4 shrink-0 text-ink-faint" aria-hidden="true"></svg>
                <a [routerLink]="['/authorities', a.id]" class="min-w-0 flex-1 truncate font-semibold text-ink hover:text-accent">{{ a.name }}</a>
                <span class="hidden text-ink-muted sm:inline">@if (a.city) { {{ a.city }}, } {{ a.countryCode }}</span>
                <span class="eti-num shrink-0 text-ink-muted">{{ 'market.competitors.tenders' | transloco: { n: a.tenders } }}</span>
              </li>
            } @empty {
              <li class="px-4 py-3 text-xs text-ink-muted">{{ 'search.noMatches' | transloco }}</li>
            }
          }
        </ul>
      }
    </section>

    <section class="mt-8" aria-labelledby="watched-h">
      <div class="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <h2 id="watched-h" class="eti-h2">{{ 'market.competitors.watched' | transloco }}</h2>
          <p class="mt-1 text-[11px] text-ink-muted">{{ 'market.competitors.watchedHint' | transloco }}</p>
        </div>
        @if (view(); as v) {
          <span class="text-xs text-ink-muted">{{ 'market.competitors.usage' | transloco: { used: v.items.length, limit: v.limit } }}</span>
        }
      </div>
      <eti-api-error [error]="error()" />
      @if (loading()) {
        <div class="eti-card p-5"><eti-skeleton size="h-4 w-1/2" /></div>
      } @else if (view(); as v) {
        @if (!v.items.length) {
          <div class="eti-card p-8 text-center text-sm text-ink-muted">{{ 'market.competitors.empty' | transloco }}</div>
        } @else {
          <div class="eti-card overflow-x-auto">
            <table class="w-full min-w-[560px] text-xs">
              <thead class="eti-table-head">
                <tr>
                  <th scope="col" class="px-4 py-3">{{ 'market.col.company' | transloco }}</th>
                  <th scope="col" class="px-4 py-3 text-right">{{ 'market.competitors.winsIn' | transloco: { m: v.months } }}</th>
                  <th scope="col" class="px-4 py-3 text-right">{{ 'market.col.total' | transloco }}</th>
                  <th scope="col" class="px-4 py-3">{{ 'market.col.lastWin' | transloco }}</th>
                  <th scope="col" class="px-4 py-3"><span class="sr-only">{{ 'common.delete' | transloco }}</span></th>
                </tr>
              </thead>
              <tbody class="divide-y divide-border">
                @for (c of v.items; track c.id) {
                  <tr class="hover:bg-subtle">
                    <td class="px-4 py-3">
                      <a [routerLink]="['/companies', c.id]" class="font-semibold text-ink hover:text-accent">{{ c.name }}</a>
                      @if (c.note) { <span class="mt-0.5 block text-[11px] text-ink-muted">{{ c.note }}</span> }
                    </td>
                    <td class="eti-num px-4 py-3 text-right">{{ c.winsInWindow }}</td>
                    <td class="eti-num px-4 py-3 text-right">{{ c.totalEurInWindow ? (c.totalEurInWindow | money: 'EUR' : lang) : '—' }}</td>
                    <td class="px-4 py-3 whitespace-nowrap text-ink-muted">{{ c.lastWin ? (c.lastWin | userDate: lang) : '—' }}</td>
                    <td class="px-4 py-3 text-right">
                      <button type="button" class="eti-icon-btn size-8" [attr.aria-label]="'market.company.unwatch' | transloco" [title]="'market.company.unwatch' | transloco" (click)="unwatch(c.id)">
                        <svg lucideTrash class="size-4" aria-hidden="true"></svg>
                      </button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </section>
  `,
})
export class CompetitorsPage {
  private readonly api = inject(MarketService);
  private readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly view = signal<CompetitorsView | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly kind = signal<'companies' | 'authorities'>('companies');
  protected readonly companies = signal<CompanyHit[]>([]);
  protected readonly authorities = signal<AuthorityHit[]>([]);
  protected readonly queries = new Subject<string>();
  protected q = '';

  constructor() {
    effect(() => {
      if (this.org.currentId()) {
        untracked(() => this.load());
      }
    });
    this.queries.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap((q) => this.search(q)),
      takeUntilDestroyed(),
    ).subscribe();
  }

  protected setKind(k: 'companies' | 'authorities'): void {
    this.kind.set(k);
    this.search(this.q).subscribe();
  }

  private search(q: string) {
    const orgId = this.org.currentId();
    if (!orgId || q.trim().length < 2) {
      this.companies.set([]);
      this.authorities.set([]);
      return of(null);
    }
    if (this.kind() === 'companies') {
      return this.api.searchCompanies(orgId, q.trim()).pipe(
        catchError(() => of([] as CompanyHit[])),
        switchMap((r) => { this.companies.set(r); return of(null); }),
      );
    }
    return this.api.searchAuthorities(orgId, q.trim()).pipe(
      catchError(() => of([] as AuthorityHit[])),
      switchMap((r) => { this.authorities.set(r); return of(null); }),
    );
  }

  private load(): void {
    this.api.competitors(this.org.currentId()!).subscribe({
      next: (v) => { this.view.set(v); this.loading.set(false); },
      error: (err: unknown) => { this.error.set(toApiError(err)); this.loading.set(false); },
    });
  }

  protected isWatched(id: string): boolean {
    return !!this.view()?.items.some((c) => c.id === id);
  }

  protected watch(c: CompanyHit): void {
    this.error.set(null);
    this.api.watch(this.org.currentId()!, c.id).subscribe({
      next: () => this.load(),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }

  protected unwatch(id: string): void {
    this.api.unwatch(this.org.currentId()!, id).subscribe({
      next: () => this.view.update((v) => (v ? { ...v, items: v.items.filter((c) => c.id !== id) } : v)),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }
}
