import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { DashboardSummary } from '../../core/api/tender.model';
import { TenderService } from '../../core/api/tender.service';
import { AuthService } from '../../core/auth/auth.service';
import { compactNumber, countryName, integer, money } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { CardHeaderComponent } from '../../shared/ui/card-header.component';
import { ColumnChartComponent, ColumnPoint } from '../../shared/ui/column-chart.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { DonutChartComponent, DonutSlice } from '../../shared/ui/donut-chart.component';
import { MatchScoreComponent } from '../../shared/ui/match-score.component';
import { ProgressListComponent, ProgressRow } from '../../shared/ui/progress-list.component';
import { Segment, SegmentedBarComponent } from '../../shared/ui/segmented-bar.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { StatTileComponent } from '../../shared/ui/stat-tile.component';

/** Categorical series in fixed order (validated palette, see styles.css). */
const SERIES = [1, 2, 3, 4, 5, 6].map((i) => `var(--color-series-${i})`);
const STAGES = ['WATCHING', 'PREPARING', 'SUBMITTED', 'WON', 'LOST', 'DROPPED'] as const;
const CONTRACT_TYPES = ['WORKS', 'SERVICES', 'SUPPLIES', 'MIXED', 'UNKNOWN'] as const;

@Component({
  selector: 'eti-dashboard-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, CardHeaderComponent,
    ColumnChartComponent, DeadlineComponent, DonutChartComponent, MatchScoreComponent, ProgressListComponent,
    SegmentedBarComponent, SkeletonComponent, StatTileComponent],
  templateUrl: './dashboard.page.html',
})
export class DashboardPage {
  private readonly api = inject(TenderService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly data = signal<DashboardSummary | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.load(orgId, lang));
      }
    });
  }

  protected load(orgId = this.org.currentId()!, lang = this.language.current()): void {
    this.error.set(null);
    this.api.dashboard(orgId, lang).subscribe({
      next: (d) => this.data.set(d),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }

  protected readonly fmtInt = computed(() => {
    const lang = this.language.current();
    return (v: number) => integer(v, lang);
  });
  protected readonly fmtCompact = computed(() => {
    const lang = this.language.current();
    return (v: number) => compactNumber(v, lang);
  });

  private dayLabel(day: string, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'short' }): string {
    return new Intl.DateTimeFormat(this.language.current(), { ...opts, timeZone: 'UTC' }).format(
      new Date(day + 'T00:00:00Z'),
    );
  }

  protected readonly perDay = computed<ColumnPoint[]>(() =>
    (this.data()?.noticesPerDay ?? []).map((d) => ({ key: d.day, label: this.dayLabel(d.day), value: d.count })),
  );

  // --- KPI tiles ------------------------------------------------------------------------------------------------

  /** New tenders in the last 7 days vs the 7 days before; null when there is nothing to compare with. */
  protected readonly newTrend = computed(() => {
    const days = this.data()?.newPerDay ?? [];
    if (days.length < 14) {
      return null;
    }
    const last = days.slice(-7).reduce((a, d) => a + d.count, 0);
    const prev = days.slice(-14, -7).reduce((a, d) => a + d.count, 0);
    return prev === 0 ? null : ((last - prev) / prev) * 100;
  });

  // --- part-to-whole cards --------------------------------------------------------------------------------------

  protected readonly typeSlices = computed<DonutSlice[]>(() => {
    this.translations();
    const buckets = this.data()?.byContractType ?? [];
    return CONTRACT_TYPES.map((t, i) => ({
      key: t,
      label: this.transloco.translate(t === 'UNKNOWN' ? 'dashboard.typeUnknown' : `tender.contractType.${t}`),
      value: buckets.find((b) => b.key === t)?.count ?? 0,
      color: SERIES[i],
    })).filter((s) => s.value > 0);
  });
  protected readonly typeTotal = computed(() => this.typeSlices().reduce((a, s) => a + s.value, 0));

  protected readonly countrySegments = computed<Segment[]>(() => {
    this.translations();
    const lang = this.language.current();
    const list = this.data()?.byCountry ?? [];
    const open = this.data()?.openTenders ?? 0;
    const top = list.slice(0, 5).map((b, i) => ({ key: b.key, label: countryName(b.key, lang), value: b.count, color: SERIES[i] }));
    const rest = open - top.reduce((a, s) => a + s.value, 0);
    return rest > 0
      ? [...top, { key: 'other', label: this.transloco.translate('dashboard.otherCountries'), value: rest, color: 'var(--color-ink-faint)' }]
      : top;
  });

  protected readonly pipelineRows = computed<ProgressRow[]>(() => {
    this.translations();
    const lang = this.language.current();
    const stages = this.data()?.pipeline ?? [];
    const total = stages.reduce((a, s) => a + s.count, 0) || 1;
    return STAGES.map((st, i) => {
      const s = stages.find((x) => x.status === st);
      const count = s?.count ?? 0;
      return {
        key: st,
        label: this.transloco.translate(`watch.status.${st}`),
        valueText: integer(count, lang),
        hint: s && s.valueEur > 0 ? money(s.valueEur, 'EUR', lang) : undefined,
        percent: (count / total) * 100,
        color: SERIES[i],
      };
    });
  });
  protected readonly pipelineTotal = computed(() => (this.data()?.pipeline ?? []).reduce((a, s) => a + s.count, 0));

  protected readonly cpvItems = computed(() => {
    const list = (this.data()?.topCpvDivisions ?? []).slice(0, 6);
    const max = Math.max(1, ...list.map((b) => b.count));
    return list.map((b) => ({ key: b.key, division: b.key.slice(0, 2), label: b.label ?? b.key, count: b.count,
      share: (b.count / max) * 100 }));
  });

  protected readonly firstName = computed(() => (this.auth.user()?.fullName ?? '').split(/\s+/)[0] ?? '');

  /** Open tenders whose deadline falls within the next 7 days (same rule as the KPI). */
  protected readonly dueSoonQuery = computed(() => {
    const pad = (n: number) => String(n).padStart(2, '0');
    const d = new Date();
    const from = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    d.setDate(d.getDate() + 7);
    const to = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    return { status: 'OPEN', deadlineFrom: from, deadlineTo: to, sort: 'DEADLINE' };
  });

  protected watchedValue(d: DashboardSummary): string {
    return money(d.watchedValueEur, 'EUR', this.language.current());
  }
}
