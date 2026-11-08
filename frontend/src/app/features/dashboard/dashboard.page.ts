import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { DashboardSummary } from '../../core/api/tender.model';
import { TenderService } from '../../core/api/tender.service';
import { AuthService } from '../../core/auth/auth.service';
import { compactNumber, countryName, integer, money } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { BarItem, BarListComponent } from '../../shared/ui/bar-list.component';
import { ColumnChartComponent, ColumnPoint } from '../../shared/ui/column-chart.component';
import { DeadlineComponent } from '../../shared/ui/deadline.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { StatTileComponent } from '../../shared/ui/stat-tile.component';

@Component({
  selector: 'eti-dashboard-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, BarListComponent, ColumnChartComponent,
    DeadlineComponent, SkeletonComponent, StatTileComponent],
  templateUrl: './dashboard.page.html',
})
export class DashboardPage {
  private readonly api = inject(TenderService);
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly data = signal<DashboardSummary | null>(null);
  protected readonly error = signal<ApiError | null>(null);

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

  protected readonly perDay = computed<ColumnPoint[]>(() => {
    const lang = this.language.current();
    const fmt = new Intl.DateTimeFormat(lang, { day: 'numeric', month: 'short', timeZone: 'UTC' });
    return (this.data()?.noticesPerDay ?? []).map((d) => ({
      key: d.day,
      label: fmt.format(new Date(d.day + 'T00:00:00Z')),
      value: d.count,
    }));
  });

  protected readonly cpvBars = computed<BarItem[]>(() =>
    (this.data()?.topCpvDivisions ?? []).map((b) => ({ key: b.key, label: `${b.key.slice(0, 2)} · ${b.label ?? b.key}`, value: b.count })),
  );

  protected readonly countryBars = computed<BarItem[]>(() => {
    const lang = this.language.current();
    return (this.data()?.byCountry ?? []).map((b) => ({ key: b.key, label: countryName(b.key, lang), value: b.count }));
  });

  protected watchedValue(d: DashboardSummary): string {
    return money(d.watchedValueEur, 'EUR', this.language.current());
  }
}
