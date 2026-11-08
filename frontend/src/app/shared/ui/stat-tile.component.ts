import { Component, input } from '@angular/core';

@Component({
  selector: 'eti-stat-tile',
  template: `
    <div class="eti-card h-full p-4">
      <p class="text-sm text-ink-muted">{{ label() }}</p>
      <p class="mt-1 text-2xl font-semibold tracking-tight tabular-nums">{{ value() }}</p>
      @if (hint()) {
        <p class="mt-1 text-xs text-ink-muted">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatTileComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string>();
  readonly hint = input<string | null>(null);
}
