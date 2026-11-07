import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideCircleAlert, LucideDatabase, LucideRefreshCw } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { Readiness, SystemStatus } from '../../core/api/system-status.model';
import { SystemStatusService } from '../../core/api/system-status.service';
import { LanguageService } from '../../core/i18n/language.service';
import { BadgeComponent, BadgeTone } from '../../shared/ui/badge.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

type ViewState =
  | { kind: 'loading' }
  | { kind: 'loaded'; data: SystemStatus }
  | { kind: 'error'; error: ApiError };

const READINESS_TONE: Record<Readiness, BadgeTone> = {
  READY: 'success',
  PENDING_CONFIGURATION: 'warning',
  NOT_IMPLEMENTED: 'neutral',
};

@Component({
  selector: 'eti-system-status-page',
  imports: [
    DatePipe,
    TranslocoPipe,
    BadgeComponent,
    SkeletonComponent,
    LucideRefreshCw,
    LucideCircleAlert,
    LucideDatabase,
  ],
  templateUrl: './system-status.page.html',
})
export class SystemStatusPage {
  private readonly service = inject(SystemStatusService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly language = inject(LanguageService);
  protected readonly state = signal<ViewState>({ kind: 'loading' });
  protected readonly readinessTone = READINESS_TONE;

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set({ kind: 'loading' });
    this.service
      .getStatus()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.state.set({ kind: 'loaded', data }),
        error: (err: unknown) => this.state.set({ kind: 'error', error: toApiError(err) }),
      });
  }
}
