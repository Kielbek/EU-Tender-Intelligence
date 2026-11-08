import { Component, OnInit, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Location } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideArrowLeft, LucideExternalLink } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { DiffEntry, SavedTender, TenderDetail, TenderVersion, WatchStatus } from '../../core/api/tender.model';
import { TenderService, WorkspaceService } from '../../core/api/tender.service';
import { MatchingService } from '../../core/api/company.service';
import { MatchView } from '../../core/api/matching.model';
import { WatchlistStore } from '../../core/api/watchlist.store';
import { AuthService } from '../../core/auth/auth.service';
import { dateTime, money } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { MatchExplanationComponent } from '../../shared/ui/match-explanation.component';
import { MatchScoreComponent } from '../../shared/ui/match-score.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { TenderStatusBadgeComponent } from '../../shared/ui/tender-status-badge.component';

export const WATCH_STATUSES: WatchStatus[] = ['WATCHING', 'PREPARING', 'SUBMITTED', 'WON', 'LOST', 'DROPPED'];

@Component({
  selector: 'eti-tender-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    TranslocoPipe,
    ...FORMAT_PIPES,
    ApiErrorAlertComponent,
    DeadlineComponent,
    MatchExplanationComponent,
    MatchScoreComponent,
    SkeletonComponent,
    TenderStatusBadgeComponent,
    LucideArrowLeft,
    LucideExternalLink,
  ],
  templateUrl: './tender-detail.page.html',
})
export class TenderDetailPage implements OnInit {
  private readonly tenders = inject(TenderService);
  private readonly workspace = inject(WorkspaceService);
  private readonly matching = inject(MatchingService);
  private readonly store = inject(WatchlistStore);
  private readonly transloco = inject(TranslocoService);
  private readonly location = inject(Location);
  private readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);

  readonly id = input.required<string>();
  protected readonly tender = signal<TenderDetail | null>(null);
  protected readonly versions = signal<TenderVersion[]>([]);
  protected readonly saved = signal<SavedTender | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly busy = signal(false);
  protected readonly notesSaved = signal(false);
  protected readonly notes = signal('');
  protected readonly watchStatuses = WATCH_STATUSES;
  /** Fit with the company profile: a result, 'noProfile', or null while loading / unavailable. */
  protected readonly match = signal<MatchView | 'noProfile' | null>(null);
  protected readonly matchView = computed(() => {
    const m = this.match();
    return m && m !== 'noProfile' ? m : null;
  });

  protected readonly cpvList = computed(() => {
    const t = this.tender();
    if (!t) {
      return [];
    }
    const codes = [...new Set([t.mainCpv, ...t.cpvCodes].filter((c): c is string => !!c))];
    return codes.map((code) => ({ code, label: t.cpvLabels[code], main: code === t.mainCpv }));
  });

  ngOnInit(): void {
    const lang = this.language.current();
    this.tenders.get(this.id(), lang).subscribe({
      next: (t) => this.tender.set(t),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
    this.tenders.versions(this.id()).subscribe({ next: (v) => this.versions.set(v), error: () => undefined });
  }

  /** The organization list may still be loading after a page reload – (re)load the watch state when it is known. */
  private readonly loadWatchState = effect(() => {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    untracked(() =>
      this.workspace.savedTender(orgId, this.id(), this.language.current()).subscribe({
        next: (s) => {
          this.saved.set(s);
          this.notes.set(s.notes ?? '');
        },
        error: () => this.saved.set(null), // 404 = not watched
      }),
    );
  });

  private readonly loadMatch = effect(() => {
    const orgId = this.org.currentId();
    const lang = this.language.current();
    if (!orgId) {
      return;
    }
    untracked(() =>
      this.matching.explain(orgId, this.id(), lang).subscribe({
        next: (m) => this.match.set(m),
        error: (err: unknown) => this.match.set(toApiError(err).code === 'profile_missing' ? 'noProfile' : null),
      }),
    );
  });

  protected back(): void {
    this.location.back();
  }

  protected watch(): void {
    this.update(this.store.watch(this.id()), true);
  }

  protected unwatch(): void {
    if (!confirm(this.transloco.translate('watch.removeConfirm'))) {
      return;
    }
    this.busy.set(true);
    this.store.unwatch(this.id()).subscribe({
      next: () => {
        this.saved.set(null);
        this.notes.set('');
        this.busy.set(false);
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  protected setStage(status: WatchStatus): void {
    this.update(this.workspace.saveTender(this.org.currentId()!, this.id(), { status }, this.language.current()));
  }

  protected saveNotes(): void {
    this.notesSaved.set(false);
    this.update(this.workspace.saveTender(this.org.currentId()!, this.id(), { notes: this.notes() }, this.language.current()),
      true, () => this.notesSaved.set(true));
  }

  /**
   * @param syncNotes replace the notes field with the server value – only when the notes themselves were saved
   *        (or on first watch); a stage change must not wipe text the user is typing.
   */
  private update(op: import('rxjs').Observable<SavedTender>, syncNotes = false, after?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    op.subscribe({
      next: (s) => {
        this.saved.set(s);
        if (syncNotes) {
          this.notes.set(s.notes ?? '');
        }
        this.busy.set(false);
        after?.();
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    this.actionError.set(toApiError(err));
    this.busy.set(false);
  }

  /** Human-readable value of a diff entry (dates and amounts formatted, lists joined). */
  protected diffValue(entry: DiffEntry, which: 'old' | 'new'): string {
    const v = entry[which];
    const lang = this.language.current();
    if (v === null || v === undefined || v === '') {
      return '—';
    }
    if (entry.field === 'submissionDeadline') {
      return dateTime(String(v), lang, this.auth.user()?.timezone);
    }
    if (entry.field === 'estimatedValue') {
      return money(Number(v), this.tender()?.currency, lang);
    }
    if (entry.field === 'status') {
      return this.transloco.translate('tender.status.' + v);
    }
    if (entry.field === 'contractType') {
      return this.transloco.translate('tender.contractType.' + v);
    }
    if (entry.field === 'procedureType') {
      return this.transloco.translate('tender.procedureType.' + v);
    }
    return Array.isArray(v) ? v.join(', ') : String(v);
  }

  protected tz(offset: string | undefined): string {
    return !offset || offset === 'Z' ? '' : offset;
  }
}
