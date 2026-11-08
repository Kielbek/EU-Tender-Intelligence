import { Component, ElementRef, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { SavedSearch } from '../../core/api/tender.model';
import { WorkspaceService } from '../../core/api/tender.service';
import { countryName } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { paramsFromApiFilters } from '../search/search-state';

@Component({
  selector: 'eti-saved-searches-page',
  imports: [FormsModule, RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent],
  template: `
    <h1 class="eti-h1">{{ 'savedSearches.title' | transloco }}</h1>
    <p class="eti-subtitle mt-1.5">{{ 'savedSearches.subtitle' | transloco: { org: org.current()?.name ?? '' } }}</p>
    <p class="mt-2 text-xs text-ink-muted">{{ 'savedSearches.alertsNote' | transloco }}</p>

    <div class="mt-5" aria-live="polite" [attr.aria-busy]="loading()">
      <eti-api-error [error]="error()" />
      @if (loading() && !items().length) {
        <div class="space-y-2">@for (i of [1, 2]; track i) { <div class="eti-card p-4"><eti-skeleton size="h-4 w-1/2" /></div> }</div>
      } @else if (!items().length && !error()) {
        <div class="eti-card p-8 text-center">
          <p class="font-medium">{{ 'savedSearches.empty' | transloco }}</p>
          <a routerLink="/search" [queryParams]="{ status: 'OPEN' }" class="eti-btn-primary mt-4">{{ 'savedSearches.emptyCta' | transloco }}</a>
        </div>
      } @else {
        <ul class="space-y-2">
          @for (s of items(); track s.id) {
            <li class="eti-card flex flex-wrap items-center gap-3 p-4">
              <div class="min-w-0 flex-1">
                <p class="font-medium">{{ s.name }}</p>
                <p class="mt-0.5 truncate text-xs text-ink-muted">{{ summary(s) }}</p>
                <p class="mt-0.5 text-xs text-ink-muted">
                  {{ 'savedSearches.lastRun' | transloco }}:
                  {{ s.lastRunAt ? (s.lastRunAt | userDateTime: language.current()) : ('savedSearches.never' | transloco) }}
                </p>
              </div>
              <div class="flex gap-2">
                <button type="button" class="eti-btn-primary h-9" (click)="open(s)">{{ 'savedSearches.open' | transloco }}</button>
                <button type="button" class="eti-btn-secondary h-9" (click)="startRename(s)">{{ 'common.rename' | transloco }}</button>
                <button type="button" class="eti-btn-danger h-9" (click)="remove(s)">{{ 'common.delete' | transloco }}</button>
              </div>
            </li>
          }
        </ul>
      }
    </div>

    <dialog #renameDlg class="w-full max-w-md eti-card p-0 text-ink shadow-xl backdrop:bg-navy-950/40"
            aria-labelledby="rename-title">
      <form class="space-y-4 p-6" (ngSubmit)="rename()">
        <h2 id="rename-title" class="text-lg font-semibold">{{ 'common.rename' | transloco }}</h2>
        <eti-api-error [error]="dialogError()" />
        <div>
          <label class="eti-label" for="new-name">{{ 'common.name' | transloco }}</label>
          <input id="new-name" name="newName" class="eti-input" [(ngModel)]="newName" required maxlength="120" />
        </div>
        <div class="flex justify-end gap-2">
          <button type="button" class="eti-btn-secondary" (click)="renameDlg.close()">{{ 'common.cancel' | transloco }}</button>
          <button type="submit" class="eti-btn-primary" [disabled]="!newName.trim()">{{ 'common.save' | transloco }}</button>
        </div>
      </form>
    </dialog>
  `,
})
export class SavedSearchesPage {
  private readonly api = inject(WorkspaceService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly renameDialog = viewChild<ElementRef<HTMLDialogElement>>('renameDlg');
  protected readonly items = signal<SavedSearch[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected newName = '';
  private renaming: SavedSearch | null = null;

  constructor() {
    effect(() => {
      if (this.org.currentId()) {
        untracked(() => this.load());
      }
    });
  }

  private load(): void {
    this.loading.set(true);
    this.api.savedSearches(this.org.currentId()!).subscribe({
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

  /** Short human-readable description of the filters, e.g. "„szkoła” · Niemcy, Polska · CPV 45000000". */
  protected summary(s: SavedSearch): string {
    const f = s.filters;
    const lang = this.language.current();
    const parts: string[] = [];
    if (f.q) parts.push(`„${f.q}”`);
    const countries = Array.isArray(f.country) ? f.country : [];
    if (countries.length) parts.push(countries.map((c) => countryName(c, lang)).join(', '));
    const cpv = Array.isArray(f.cpv) ? f.cpv : [];
    if (cpv.length) parts.push(`CPV ${cpv.join(', ')}`);
    const status = Array.isArray(f.status) ? f.status : [];
    if (status.length) parts.push(status.map((x) => this.transloco.translate('tender.status.' + x)).join(', '));
    if (f.valueMinEur !== undefined) parts.push(`≥ ${Number(f.valueMinEur).toLocaleString(lang)} EUR`);
    if (f.valueMaxEur !== undefined) parts.push(`≤ ${Number(f.valueMaxEur).toLocaleString(lang)} EUR`);
    return parts.length ? parts.join(' · ') : this.transloco.translate('savedSearches.noFilters');
  }

  protected open(s: SavedSearch): void {
    this.api.markSearchRun(this.org.currentId()!, s.id).subscribe({ error: () => undefined });
    void this.router.navigate(['/search'], { queryParams: paramsFromApiFilters(s.filters) });
  }

  protected startRename(s: SavedSearch): void {
    this.renaming = s;
    this.newName = s.name;
    this.dialogError.set(null);
    this.renameDialog()?.nativeElement.showModal();
  }

  protected rename(): void {
    if (!this.renaming || !this.newName.trim()) {
      return;
    }
    this.api.renameSearch(this.org.currentId()!, this.renaming.id, this.newName.trim()).subscribe({
      next: (updated) => {
        this.items.update((list) => list.map((x) => (x.id === updated.id ? updated : x)));
        this.renameDialog()?.nativeElement.close();
      },
      error: (err: unknown) => this.dialogError.set(toApiError(err)),
    });
  }

  protected remove(s: SavedSearch): void {
    if (!confirm(this.transloco.translate('savedSearches.deleteConfirm', { name: s.name }))) {
      return;
    }
    this.api.deleteSearch(this.org.currentId()!, s.id).subscribe({
      next: () => this.items.update((list) => list.filter((x) => x.id !== s.id)),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }
}
