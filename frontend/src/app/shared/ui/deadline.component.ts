import { Component, computed, inject, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { dateTime, daysUntil } from '../../core/format/format';

/** Deadline in the user's time zone plus a "in N days" hint; urgent (≤ 3 days) is highlighted with text, not colour only. */
@Component({
  selector: 'eti-deadline',
  imports: [TranslocoPipe],
  template: `
    @if (iso(); as d) {
      <time [attr.datetime]="d" class="tabular-nums">{{ formatted() }}</time>
      @if (days() !== null && days()! >= 0 && showDays()) {
        <span class="ml-1 text-xs" [class.font-semibold]="urgent()" [class.text-warning]="urgent()"
              [class.text-ink-muted]="!urgent()">
          ({{ (days() === 0 ? 'tender.today' : 'tender.inDays') | transloco: { n: days() } }})
        </span>
      }
    } @else {
      <span class="text-ink-muted">—</span>
    }
  `,
})
export class DeadlineComponent {
  private readonly auth = inject(AuthService);
  readonly iso = input<string | undefined>();
  readonly locale = input.required<string>();
  readonly showDays = input(true);
  protected readonly formatted = computed(() => dateTime(this.iso(), this.locale(), this.auth.user()?.timezone));
  protected readonly days = computed(() => (this.iso() ? daysUntil(this.iso()!) : null));
  protected readonly urgent = computed(() => this.days() !== null && this.days()! <= 3);
}
