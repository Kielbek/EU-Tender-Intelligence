import { Component, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideRefreshCw } from '@lucide/angular';

/** Card title row: title, description, optional actions (projected) and a working refresh button. */
@Component({
  selector: 'eti-card-header',
  imports: [TranslocoPipe, LucideRefreshCw],
  template: `
    <div class="flex items-start gap-3">
      <div class="min-w-0 flex-1">
        <h2 [id]="headingId()" class="eti-h2">{{ title() }}</h2>
        @if (description()) {
          <p class="mt-1 text-[13px] text-ink-muted">{{ description() }}</p>
        }
      </div>
      <ng-content />
      @if (refreshable()) {
        <button type="button" class="inline-flex size-8 items-center justify-center rounded-md text-ink-muted hover:bg-canvas hover:text-ink"
                [attr.aria-label]="'common.refresh' | transloco" [title]="'common.refresh' | transloco" (click)="refresh.emit()">
          <svg lucideRefreshCw class="size-4" aria-hidden="true"></svg>
        </button>
      }
    </div>
  `,
})
export class CardHeaderComponent {
  readonly title = input.required<string>();
  readonly description = input<string | null>(null);
  readonly headingId = input<string | null>(null);
  readonly refreshable = input(false);
  readonly refresh = output<void>();
}
