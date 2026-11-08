import { Component, computed, input, signal } from '@angular/core';

export interface DonutSlice {
  key: string;
  label: string;
  value: number;
  color: string;
}

/**
 * Part-to-whole ring (≤ 6 slices, fixed categorical order). A 2px surface gap separates slices; hovering a slice
 * shows its label and share in the centre. The legend with values is rendered next to it by the caller.
 */
@Component({
  selector: 'eti-donut-chart',
  template: `
    <figure class="relative mx-auto aspect-square w-full max-w-[220px]">
      <svg viewBox="0 0 120 120" class="size-full -rotate-90" role="img" [attr.aria-label]="caption()">
        @for (a of arcs(); track a.key; let i = $index) {
          <circle cx="60" cy="60" r="48" fill="none" [attr.stroke]="a.color" stroke-width="16"
                  [attr.stroke-dasharray]="a.dash" [attr.stroke-dashoffset]="a.offset"
                  [style.opacity]="active() === null || active() === i ? 1 : 0.4"
                  (mouseenter)="active.set(i)" (mouseleave)="active.set(null)">
            <title>{{ a.label }}: {{ a.value }} ({{ a.pct }}%)</title>
          </circle>
        }
      </svg>
      <figcaption class="absolute inset-0 flex flex-col items-center justify-center text-center">
        @if (active() !== null) {
          <span class="eti-num text-[22px] font-medium tracking-[-0.02em] text-ink">{{ arcs()[active()!].pct }}%</span>
          <span class="max-w-[110px] truncate text-xs text-ink-muted">{{ arcs()[active()!].label }}</span>
        } @else {
          <span class="eti-num text-[22px] font-medium tracking-[-0.02em] text-ink">{{ totalText() }}</span>
          <span class="text-xs text-ink-muted">{{ centerLabel() }}</span>
        }
      </figcaption>
    </figure>
  `,
})
export class DonutChartComponent {
  readonly slices = input.required<DonutSlice[]>();
  readonly caption = input('');
  readonly centerLabel = input('');
  readonly totalText = input('');
  protected readonly active = signal<number | null>(null);

  protected readonly arcs = computed(() => {
    const items = this.slices().filter((s) => s.value > 0);
    const total = items.reduce((a, s) => a + s.value, 0) || 1;
    const c = 2 * Math.PI * 48;
    const gap = items.length > 1 ? 1.6 : 0; // ~2px surface gap at this size
    let acc = 0;
    return items.map((s) => {
      const len = (s.value / total) * c;
      const arc = {
        ...s,
        pct: Math.round((s.value / total) * 1000) / 10,
        dash: `${Math.max(0.5, len - gap)} ${c}`,
        offset: -acc,
      };
      acc += len;
      return arc;
    });
  });
}
