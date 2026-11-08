import { Component, DestroyRef, ElementRef, HostListener, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideBookmarkPlus, LucideDatabase, LucideDownload, LucideSearch, LucideSlidersHorizontal } from '@lucide/angular';
import { ApiError, toApiError, toApiErrorFromBlob } from '../../core/api/api-error';
import { EU_LANGUAGES, ReferenceService } from '../../core/api/reference.service';
import { DashboardSummary, Page, TenderSummary } from '../../core/api/tender.model';
import { TenderService, WorkspaceService } from '../../core/api/tender.service';
import { countryName, languageName } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { CpvPickerComponent } from '../../shared/ui/cpv-picker.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { DateInputComponent } from '../../shared/ui/date-input.component';
import { CheckOption, MultiCheckComponent } from '../../shared/ui/multi-check.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { TenderStatusBadgeComponent } from '../../shared/ui/tender-status-badge.component';
import { WatchToggleComponent } from '../../shared/ui/watch-toggle.component';
import { SearchForm, activeFilterCount, apiFilters, emptyForm, formFromParams, paramsFromForm } from './search-state';

const PAGE_SIZE = 20;
const STATUSES = ['OPEN', 'PLANNED', 'CLOSED', 'AWARDED', 'CANCELLED'];
const CONTRACT_TYPES = ['WORKS', 'SUPPLIES', 'SERVICES'];
const PROCEDURES = ['open', 'pl-basic', 'restricted', 'neg-w-call', 'neg-wo-call', 'comp-dial', 'innovation', 'oth-single', 'oth-mult'];

@Component({
  selector: 'eti-search-page',
  imports: [
    FormsModule,
    RouterLink,
    TranslocoPipe,
    ...FORMAT_PIPES,
    ApiErrorAlertComponent,
    CpvPickerComponent,
    MultiCheckComponent,
    SelectComponent,
    DateInputComponent,
    SkeletonComponent,
    TenderStatusBadgeComponent,
    DeadlineComponent,
    WatchToggleComponent,
    LucideSearch,
    LucideDownload,
    LucideSlidersHorizontal,
    LucideBookmarkPlus,
    LucideDatabase,
  ],
  templateUrl: './search.page.html',
})
export class SearchPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tenders = inject(TenderService);
  private readonly workspace = inject(WorkspaceService);
  private readonly transloco = inject(TranslocoService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  private readonly reference = inject(ReferenceService);
  private readonly saveDialog = viewChild<ElementRef<HTMLDialogElement>>('saveDialog');

  protected form: SearchForm = emptyForm();
  protected page = 0;
  protected readonly results = signal<Page<TenderSummary> | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  /** Filter panel: shown next to the results on wide screens, toggled with the "Filters" button. */
  protected readonly filtersOpen = signal(typeof window !== 'undefined' && window.matchMedia('(min-width: 1280px)').matches);
  protected readonly exportBusy = signal(false);
  protected readonly exportNote = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly exportMenu = signal(false);
  private readonly exportMenuHost = viewChild<ElementRef<HTMLElement>>('exportMenuHost');

  @HostListener('document:click', ['$event'])
  protected closeMenus(e: MouseEvent): void {
    if (this.exportMenu() && !this.exportMenuHost()?.nativeElement.contains(e.target as Node)) {
      this.exportMenu.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected escape(): void {
    this.exportMenu.set(false);
  }
  /** Database-wide figures for the strip above the results (from the dashboard summary; real counts only). */
  protected readonly stats = signal<Pick<DashboardSummary, 'openTenders' | 'openCountries' | 'newLast7Days' | 'dueWithin7Days'> | null>(null);
  protected saveName = '';
  protected readonly saveError = signal<ApiError | null>(null);

  private readonly countries = toSignal(this.reference.countries(), { initialValue: [] });
  protected readonly currencies = toSignal(this.reference.currencies(), { initialValue: [] });

  protected readonly countryOptions = computed<CheckOption[]>(() => {
    const lang = this.language.current();
    const opts = this.countries().map((c) => ({ value: c.code, label: countryName(c.code, lang), eu: c.isEu }));
    const byName = (a: { label: string }, b: { label: string }) => a.label.localeCompare(b.label, lang);
    return [...opts.filter((o) => o.eu).sort(byName), ...opts.filter((o) => !o.eu).sort(byName)];
  });
  protected readonly statusOptions = computed(() => this.options(STATUSES, 'tender.status.'));
  protected readonly typeOptions = computed(() => this.options(CONTRACT_TYPES, 'tender.contractType.'));
  protected readonly procedureOptions = computed(() => this.options(PROCEDURES, 'tender.procedureType.'));
  protected readonly languages = computed(() =>
    EU_LANGUAGES.map((code) => ({ code, label: languageName(code, this.language.current()) })).sort((a, b) =>
      a.label.localeCompare(b.label, this.language.current()),
    ),
  );
  protected readonly sortOptions = ['RELEVANCE', 'PUBLISHED', 'DEADLINE', 'VALUE'];
  protected readonly languageSelect = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('search.anyLanguage') },
      ...this.languages().map((l) => ({ value: l.code, label: l.label, hint: l.code.toUpperCase() }))];
  });
  protected readonly currencySelect = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('search.anyCurrency') },
      ...this.currencies().map((c) => ({ value: c.code, label: c.code }))];
  });
  /** The default sort ("") is relevance with a text query, newest otherwise. */
  protected readonly sortSelect = computed(() => {
    this.translations();
    const rest = this.sortOptions.map((s) => ({ value: s, label: this.transloco.translate('search.sortOptions.' + s) }));
    return {
      withQuery: [{ value: '', label: this.transloco.translate('search.sortOptions.RELEVANCE') }, ...rest.slice(1)],
      withoutQuery: [{ value: '', label: this.transloco.translate('search.sortOptions.PUBLISHED') },
        ...rest.filter((o) => o.value !== 'PUBLISHED')],
    };
  });

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.tenders.dashboard(orgId, lang).subscribe({ next: (d) => this.stats.set(d), error: () => this.stats.set(null) }));
      }
    });
    this.route.queryParams.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      this.form = formFromParams(params);
      this.page = Math.max(0, Number(params['page'] ?? 0) || 0);
      this.load();
    });
  }

  /** Emits whenever the active translation is (re)loaded, so option labels never show raw keys. */
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  private options(values: string[], prefix: string): CheckOption[] {
    this.translations(); // re-evaluate once translations are loaded / the language changes
    return values.map((v) => ({ value: v, label: this.transloco.translate(prefix + v) }));
  }

  protected activeFilters(): number {
    return activeFilterCount(this.form);
  }

  protected submit(): void {
    void this.router.navigate([], { queryParams: paramsFromForm(this.form) });
  }

  protected clear(): void {
    this.form = { ...emptyForm(), q: this.form.q };
    this.submit();
  }

  protected goTo(page: number): void {
    void this.router.navigate([], { queryParams: paramsFromForm(this.form, page) });
    document.getElementById('results-heading')?.focus();
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.exportNote.set(null);
    this.tenders.search(apiFilters(this.form), this.page, PAGE_SIZE, this.language.current()).subscribe({
      next: (page) => {
        this.results.set(page);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected export(format: 'csv' | 'xlsx'): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.exportBusy.set(true);
    this.exportNote.set(null);
    this.error.set(null);
    this.tenders.export(orgId, apiFilters(this.form), format, this.language.current()).subscribe({
      next: (res) => {
        const total = Number(res.headers.get('X-Total-Count') ?? 0);
        const limit = Number(res.headers.get('X-Export-Limit') ?? 0);
        const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? `tenders.${format}`;
        const url = URL.createObjectURL(res.body!);
        const a = document.createElement('a');
        a.href = url;
        a.download = name;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        if (limit && total > limit) {
          this.exportNote.set(this.transloco.translate('search.exportTruncated', { limit, total }));
        }
        this.exportBusy.set(false);
      },
      error: (err: unknown) => {
        void toApiErrorFromBlob(err).then((e) => {
          this.error.set(e);
          this.exportBusy.set(false);
        });
      },
    });
  }

  protected openSave(): void {
    this.saveName = this.form.q.trim();
    this.saveError.set(null);
    this.saveDialog()?.nativeElement.showModal();
  }

  protected closeSave(): void {
    this.saveDialog()?.nativeElement.close();
  }

  protected save(): void {
    const orgId = this.org.currentId();
    const name = this.saveName.trim();
    if (!orgId || !name) {
      return;
    }
    this.workspace.createSearch(orgId, name, apiFilters(this.form)).subscribe({
      next: (s) => {
        this.closeSave();
        this.notice.set(this.transloco.translate('search.saved', { name: s.name }));
      },
      error: (err: unknown) => this.saveError.set(toApiError(err)),
    });
  }

  /** Up to five page numbers around the current page (0-based). */
  protected pageNumbers(r: Page<TenderSummary>): number[] {
    const last = Math.min(r.totalPages, 500) - 1;
    const start = Math.max(0, Math.min(r.page - 2, last - 4));
    const out: number[] = [];
    for (let n = start; n <= Math.min(last, start + 4); n++) {
      out.push(n);
    }
    return out;
  }
}
