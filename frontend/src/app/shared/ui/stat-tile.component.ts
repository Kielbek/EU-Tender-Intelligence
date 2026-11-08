import { Component, computed, input, signal } from '@angular/core';
import { LucideArrowDown, LucideArrowUp } from '@lucide/angular';

export interface MiniBar {
  label: string;
  value: number;
}

/**
 * KPI card: title, one-line explanation, large figure, optional change vs the previous period and a mini bar chart
 * (every bar has a tooltip; the figure itself is the headline, so the bars carry no axis).
 */
@Component({
  selector: 'eti-stat-tile',
  imports: [LucideArrowUp, LucideArrowDown],
  template: `
    <div class="eti-card flex h-full flex-col px-[22px] pt-5 pb-[18px]">
      <p class="text-[16px] font-semibold tracking-[-0.01em] text-ink">{{ label() }}</p>
      @if (description()) {
        <p class="mt-1 text-[13px] text-ink-muted">{{ description() }}</p>
      }
      <div class="mt-auto flex items-end gap-4 pt-5">
        <div class="min-w-0 flex-1">
          <p class="eti-kpi truncate">{{ value() }}</p>
          @if (trend() !== null) {
            <p class="mt-2.5 flex items-center gap-1 text-[13px] text-ink-muted">
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
            <p class="mt-2.5 text-[13px] text-ink-muted">{{ hint() }}</p>
          }
        </div>
        @if (bars().length) {
          <div class="relative flex h-16 shrink-0 items-end gap-[6px]" role="img" [attr.aria-label]="barsLabel()">
            @for (b of bars(); track $index; let i = $index) {
              <div class="flex h-full w-[7px] items-end" (mouseenter)="active.set(i)" (mouseleave)="active.set(null)">
                <div class="w-full rounded-t-[4px] rounded-b-[2px]" [style.background]="color()"
                     [style.height.%]="height(b.value)" [style.opacity]="active() === null || active() === i ? 1 : 0.45"></div>
              </div>
            }
            @if (active() !== null) {
              <div role="tooltip"
                   class="pointer-events-none absolute right-0 bottom-full z-10 mb-1 whitespace-nowrap rounded-[var(--radius-control)] border border-border-strong bg-surface px-2.5 py-1.5 text-xs shadow-md">
                <span class="block font-semibold tabular-nums text-ink">{{ bars()[active()!].value }}</span>
                <span class="block text-ink-muted">{{ bars()[active()!].label }}</span>
              </div>
            }
          </div>
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
  readonly bars = input<MiniBar[]>([]);
  readonly color = input('var(--color-series-1)');
  protected readonly active = signal<number | null>(null);

  protected readonly trendText = computed(() => {
    const t = Math.abs(this.trend() ?? 0);
    return `${t.toLocaleString(this.locale(), { maximumFractionDigits: 1 })}%`;
  });
  protected readonly barsLabel = computed(() => this.bars().map((b) => `${b.label}: ${b.value}`).join(', '));
  private readonly max = computed(() => Math.max(1, ...this.bars().map((b) => b.value)));

  protected height(v: number): number {
    return v <= 0 ? 4 : Math.max(8, (v / this.max()) * 100);
  }
}
