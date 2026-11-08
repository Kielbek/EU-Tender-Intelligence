import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { MatchingService } from '../../core/api/company.service';
import { MatchView } from '../../core/api/matching.model';
import { Page } from '../../core/api/tender.model';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { MatchExplanationComponent } from '../../shared/ui/match-explanation.component';
import { reasonText } from '../../shared/ui/match-reasons';
import { MatchScoreComponent } from '../../shared/ui/match-score.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

const PAGE_SIZE = 20;

@Component({
  selector: 'eti-matches-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, DeadlineComponent,
    MatchExplanationComponent, MatchScoreComponent, SkeletonComponent],
  templateUrl: './matches.page.html',
})
export class MatchesPage {
  private readonly api = inject(MatchingService);
  private readonly transloco = inject(TranslocoService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);

  protected readonly thresholds = [40, 60, 80] as const;
  protected readonly minScore = signal<number>(60);
  protected readonly page = signal(0);
  protected readonly data = signal<Page<MatchView> | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly loading = signal(false);
  protected readonly expanded = signal<string | null>(null);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const lang = this.language.current();
      const min = this.minScore();
      const page = this.page();
      if (orgId) {
        untracked(() => this.load(orgId, min, page, lang));
      }
    });
  }

  protected load(orgId = this.org.currentId()!, min = this.minScore(), page = this.page(),
      lang = this.language.current()): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.list(orgId, min, page, PAGE_SIZE, lang).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected setMin(value: number): void {
    this.page.set(0);
    this.minScore.set(value);
  }

  protected toggle(id: string): void {
    this.expanded.update((current) => (current === id ? null : id));
  }

  /** The strongest positive reason (shown on the row) – the rest is in the explanation. */
  protected readonly topReasons = computed(() => {
    this.translations();
    const lang = this.language.current();
    const map = new Map<string, string[]>();
    for (const m of this.data()?.items ?? []) {
      map.set(m.tender.id, m.breakdown.met.slice(0, 2).map((r) => reasonText(r, this.transloco, lang)));
    }
    return map;
  });
}
