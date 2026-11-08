import { Component, input } from '@angular/core';

/** KPI tile: label, large tabular value, optional context line. */
@Component({
  selector: 'eti-stat-tile',
  template: `
    <div class="eti-card h-full px-5 py-[18px]">
      <p class="text-[13px] text-ink-muted">{{ label() }}</p>
      <p class="eti-num mt-2.5 text-[30px] leading-none font-semibold tracking-[-0.02em] text-ink">{{ value() }}</p>
      @if (hint()) {
        <p class="mt-2 text-xs text-ink-muted">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatTileComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string | null>(null);
}
