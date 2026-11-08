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
    <ul class="space-y-3" [attr.aria-label]="caption()">
      @for (item of items(); track item.key) {
        <li class="grid grid-cols-[minmax(0,7.5rem)_1fr_3.5rem] items-center gap-2.5 text-sm sm:grid-cols-[minmax(0,10rem)_1fr_3.5rem]">
          <span class="truncate text-ink-soft" [title]="item.label">{{ item.label }}</span>
          <span class="h-2 rounded-r-[4px] bg-chart" [style.width.%]="width(item.value)" aria-hidden="true"></span>
          <span class="eti-num text-right text-ink">{{ format()(item.value) }}</span>
        </li>
      }
    </ul>
    <details class="mt-4 text-xs text-ink-muted">
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
    return Math.max(1, (value / this.max()) * 100);
  }
}
