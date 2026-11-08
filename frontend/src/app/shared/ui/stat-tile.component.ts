import { Component, computed, input } from '@angular/core';
import { LucideArrowDown, LucideArrowUp } from '@lucide/angular';

/**
 * KPI card: title, one-line explanation, large figure and either the change vs the previous period or a short hint.
 * Compact on phones (two tiles per row): the explanation is hidden there and the figure is smaller.
 */
@Component({
  selector: 'eti-stat-tile',
  imports: [LucideArrowUp, LucideArrowDown],
  template: `
    <div class="eti-card flex h-full flex-col px-4 pt-4 pb-4 sm:px-[22px] sm:pt-5 sm:pb-[18px]">
      <p class="text-[14px] leading-snug font-semibold tracking-[-0.01em] text-ink sm:text-[16px]">{{ label() }}</p>
      @if (description()) {
        <p class="mt-1 hidden text-[13px] text-ink-muted sm:block">{{ description() }}</p>
      }
      <div class="mt-auto pt-4 sm:pt-5">
        <p class="eti-kpi truncate !text-[26px] sm:!text-[34px]">{{ value() }}</p>
        @if (trend() !== null) {
          <p class="mt-2 flex flex-wrap items-center gap-x-1 text-[12px] text-ink-muted sm:mt-2.5 sm:text-[13px]">
            <span class="inline-flex items-center gap-0.5 font-medium" [class.text-success]="trend()! >= 0"
                  [class.text-danger]="trend()! < 0">
              @if (trend()! >= 0) {
                <svg lucideArrowUp class="size-3.5" aria-hidden="true"></svg>
              } @else {
                <svg lucideArrowDown class="size-3.5" aria-hidden="true"></svg>
              }
              {{ trendText() }}
            </span>
            {{ trendLabel() }}
          </p>
        } @else if (hint()) {
          <p class="mt-2 text-[12px] text-ink-muted sm:mt-2.5 sm:text-[13px]">{{ hint() }}</p>
        }
      </div>
    </div>
  `,
})
export class StatTileComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly description = input<string | null>(null);
  readonly hint = input<string | null>(null);
  /** Relative change in percent (e.g. 12.5 = +12.5 %); null hides the line. */
  readonly trend = input<number | null>(null);
  readonly trendLabel = input('');
  readonly locale = input('pl');

  protected readonly trendText = computed(() => {
    const t = Math.abs(this.trend() ?? 0);
    return `${t.toLocaleString(this.locale(), { maximumFractionDigits: 1 })}%`;
  });
}
