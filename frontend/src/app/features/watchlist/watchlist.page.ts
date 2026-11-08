import { Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideStickyNote } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { SavedTender, WatchStatus } from '../../core/api/tender.model';
import { WorkspaceService } from '../../core/api/tender.service';
import { WatchlistStore } from '../../core/api/watchlist.store';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { TenderStatusBadgeComponent } from '../../shared/ui/tender-status-badge.component';
import { WATCH_STATUSES } from '../tender/tender-detail.page';

@Component({
  selector: 'eti-watchlist-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, DeadlineComponent, SkeletonComponent,
    TenderStatusBadgeComponent, LucideStickyNote],
  template: `
    <h1 class="eti-h1">{{ 'watch.title' | transloco }}</h1>
    <p class="eti-subtitle mt-1.5">{{ 'watch.subtitle' | transloco: { org: org.current()?.name ?? '' } }}</p>

    <div class="mt-5 flex flex-wrap items-center gap-2" role="group" [attr.aria-label]="'watch.filter' | transloco">
      <button type="button" class="eti-btn-secondary h-8 px-3" [attr.aria-pressed]="filter() === null"
              [class.bg-accent-soft]="filter() === null" (click)="setFilter(null)">{{ 'common.all' | transloco }}</button>
      @for (s of statuses; track s) {
        <button type="button" class="eti-btn-secondary h-8 px-3" [attr.aria-pressed]="filter() === s"
                [class.bg-accent-soft]="filter() === s" (click)="setFilter(s)">{{ 'watch.status.' + s | transloco }}</button>
      }
    </div>

    <div class="mt-4" aria-live="polite" [attr.aria-busy]="loading()">
      <eti-api-error [error]="error()" />
      @if (loading() && !items().length) {
        <div class="space-y-2">@for (i of [1, 2, 3]; track i) { <div class="eti-card p-4"><eti-skeleton size="h-4 w-2/3" /></div> }</div>
      } @else if (!items().length && !error()) {
        <div class="eti-card p-8 text-center">
          <p class="font-medium">{{ 'watch.empty' | transloco }}</p>
          <a routerLink="/search" [queryParams]="{ status: 'OPEN' }" class="eti-btn-primary mt-4">{{ 'watch.emptyCta' | transloco }}</a>
        </div>
      } @else {
        <div class="eti-card overflow-x-auto">
          <table class="w-full text-left text-sm">
            <thead class="eti-table-head">
              <tr>
                <th scope="col" class="px-4 py-2 font-medium">{{ 'search.columns.tender' | transloco }}</th>
                <th scope="col" class="px-4 py-2 font-medium">{{ 'search.columns.deadline' | transloco }}</th>
                <th scope="col" class="px-4 py-2 text-right font-medium">{{ 'search.columns.value' | transloco }}</th>
                <th scope="col" class="px-4 py-2 font-medium">{{ 'watch.stage' | transloco }}</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-border">
              @for (s of items(); track s.tenderId) {
                @let lang = language.current();
                <tr>
                  <th scope="row" class="max-w-md px-4 py-3 font-normal">
                    @if (s.tender; as t) {
                      <div class="flex items-center gap-2 text-xs text-ink-muted">
                        <eti-tender-status [status]="t.status" /> {{ t.countryCode | country: lang }}
                        @if (s.notes) {
                          <svg lucideStickyNote class="size-3.5" [attr.aria-label]="'watch.notesIndicator' | transloco" role="img"></svg>
                        }
                      </div>
                      <a [routerLink]="['/tenders', t.id]" class="mt-1 block font-medium hover:text-accent hover:underline">{{ t.title }}</a>
                      <span class="block truncate text-xs text-ink-muted">{{ t.authorityName }}</span>
                    }
                  </th>
                  <td class="px-4 py-3 text-xs"><eti-deadline [iso]="s.tender?.submissionDeadline" [locale]="lang" /></td>
                  <td class="px-4 py-3 text-right tabular-nums">{{ s.tender?.estimatedValue | money: s.tender?.currency : lang }}</td>
                  <td class="px-4 py-3">
                    <label class="sr-only" [for]="'stage-' + s.tenderId">{{ 'watch.stage' | transloco }}</label>
                    <select [id]="'stage-' + s.tenderId" class="eti-input mt-0 h-8 w-auto" [value]="s.status"
                            (change)="setStage(s, $any($event.target).value)">
                      @for (st of statuses; track st) {
                        <option [value]="st" [selected]="st === s.status">{{ 'watch.status.' + st | transloco }}</option>
                      }
                    </select>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
})
export class WatchlistPage {
  private readonly api = inject(WorkspaceService);
  private readonly store = inject(WatchlistStore);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly statuses = WATCH_STATUSES;
  protected readonly items = signal<SavedTender[]>([]);
  protected readonly filter = signal<WatchStatus | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);

  constructor() {
    effect(() => {
      if (this.org.currentId()) {
        this.store.ids(); // reload when the watch-list changes elsewhere
        untracked(() => this.load());
      }
    });
  }

  protected setFilter(status: WatchStatus | null): void {
    this.filter.set(status);
    this.load();
  }

  private load(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.loading.set(true);
    this.api.savedTenders(orgId, this.language.current(), this.filter() ?? undefined).subscribe({
      next: (list) => {
        this.items.set(list);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected setStage(s: SavedTender, status: WatchStatus): void {
    this.api.saveTender(this.org.currentId()!, s.tenderId, { status }, this.language.current()).subscribe({
      next: (updated) => this.items.update((list) =>
        this.filter() && this.filter() !== status ? list.filter((x) => x.tenderId !== s.tenderId)
          : list.map((x) => (x.tenderId === s.tenderId ? updated : x))),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }
}
