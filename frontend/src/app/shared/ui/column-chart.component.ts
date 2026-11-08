import { Component, computed, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

export interface ColumnPoint {
  key: string;
  label: string;
  value: number;
}

/**
 * Time series as columns (one series). Each column is focusable and shows a tooltip on hover and keyboard focus;
 * all values are also available in the table view.
 */
@Component({
  selector: 'eti-column-chart',
  imports: [TranslocoPipe],
  template: `
    <figure>
      <div class="relative h-40 pl-10" role="group" [attr.aria-label]="caption()">
        <!-- Recessive hairline grid: top, middle, baseline. -->
        @for (tick of ticks(); track tick.value) {
          <div class="pointer-events-none absolute right-0 left-10 border-t border-border"
               [style.bottom.%]="tick.position" aria-hidden="true"></div>
          <span class="absolute left-0 w-8 -translate-y-1/2 text-right text-[11px] tabular-nums text-ink-muted"
                [style.bottom.%]="tick.position" style="transform: translateY(50%)" aria-hidden="true">
            {{ format()(tick.value) }}
          </span>
        }
        <div class="relative flex h-full items-end gap-[2px]">
          @for (p of points(); track p.key; let i = $index) {
            <div class="relative flex h-full flex-1 items-end justify-center outline-none"
                 tabindex="0"
                 [attr.aria-label]="p.label + ': ' + format()(p.value)"
                 (mouseenter)="active.set(i)" (mouseleave)="active.set(null)"
                 (focus)="active.set(i)" (blur)="active.set(null)">
              <div class="w-full max-w-6 rounded-t-[4px] bg-chart transition-opacity"
                   [class.opacity-70]="active() !== null && active() !== i"
                   [style.height.%]="height(p.value)"></div>
              @if (active() === i) {
                <div role="tooltip"
                     class="pointer-events-none absolute bottom-full z-10 mb-1 whitespace-nowrap rounded-md border border-border bg-surface px-2 py-1 text-xs shadow-sm">
                  <span class="block font-semibold tabular-nums text-ink">{{ format()(p.value) }}</span>
                  <span class="block text-ink-muted">{{ p.label }}</span>
                </div>
              }
            </div>
          }
        </div>
      </div>
      <div class="mt-1 flex justify-between pl-10 text-[11px] text-ink-muted" aria-hidden="true">
        <span>{{ points()[0]?.label }}</span>
        <span>{{ points()[points().length - 1]?.label }}</span>
      </div>
      <figcaption class="sr-only">{{ caption() }}</figcaption>
    </figure>
    <details class="mt-3 text-xs text-ink-muted">
      <summary class="cursor-pointer select-none">{{ 'charts.showTable' | transloco }}</summary>
      <table class="mt-2 w-full text-left">
        <caption class="sr-only">{{ caption() }}</caption>
        <tbody>
          @for (p of points(); track p.key) {
            <tr class="border-t border-border">
              <th scope="row" class="py-1 pr-2 font-normal">{{ p.label }}</th>
              <td class="py-1 text-right tabular-nums">{{ format()(p.value) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </details>
  `,
})
export class ColumnChartComponent {
  readonly points = input.required<ColumnPoint[]>();
  readonly caption = input('');
  readonly format = input<(v: number) => string>((v) => String(v));
  protected readonly active = signal<number | null>(null);

  /** Clean axis maximum: 1, 2 or 5 × 10^n. */
  private readonly axisMax = computed(() => {
    const max = Math.max(1, ...this.points().map((p) => p.value));
    const pow = 10 ** Math.floor(Math.log10(max));
    return [1, 2, 5, 10].map((m) => m * pow).find((v) => v >= max) ?? max;
  });

  protected readonly ticks = computed(() => [
    { value: 0, position: 0 },
    { value: this.axisMax() / 2, position: 50 },
    { value: this.axisMax(), position: 100 },
  ]);

  protected height(value: number): number {
    return value === 0 ? 0 : Math.max(1, (value / this.axisMax()) * 100);
  }
}
