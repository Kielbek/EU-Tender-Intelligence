import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

export interface BarItem {
  key: string;
  label: string;
  value: number;
}

/**
 * Horizontal bars for one series (magnitude). Every bar carries its value at the tip, so no tooltip is needed;
 * a table view is available for screen readers and copying.
 */
@Component({
  selector: 'eti-bar-list',
  imports: [TranslocoPipe],
  template: `
    <ul class="space-y-2" [attr.aria-label]="caption()">
      @for (item of items(); track item.key) {
        <li class="grid grid-cols-[minmax(0,10rem)_1fr] items-center gap-3 text-sm sm:grid-cols-[minmax(0,14rem)_1fr]">
          <span class="truncate text-ink-muted" [title]="item.label">{{ item.label }}</span>
          <span class="flex items-center gap-2" aria-hidden="true">
            <span class="h-3 rounded-r-[4px] bg-chart" [style.width.%]="width(item.value)"></span>
            <span class="shrink-0 font-medium tabular-nums text-ink">{{ format()(item.value) }}</span>
          </span>
          <span class="sr-only">{{ format()(item.value) }}</span>
        </li>
      }
    </ul>
    <details class="mt-3 text-xs text-ink-muted">
      <summary class="cursor-pointer select-none">{{ 'charts.showTable' | transloco }}</summary>
      <table class="mt-2 w-full text-left">
        <caption class="sr-only">{{ caption() }}</caption>
        <tbody>
          @for (item of items(); track item.key) {
            <tr class="border-t border-border">
              <th scope="row" class="py-1 pr-2 font-normal">{{ item.label }}</th>
              <td class="py-1 text-right tabular-nums">{{ format()(item.value) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </details>
  `,
})
export class BarListComponent {
  readonly items = input.required<BarItem[]>();
  readonly caption = input('');
  readonly format = input<(v: number) => string>((v) => String(v));
  private readonly max = computed(() => Math.max(1, ...this.items().map((i) => i.value)));

  protected width(value: number): number {
    // Leave room for the value label at the tip.
    return Math.max(1, (value / this.max()) * 78);
  }
}
