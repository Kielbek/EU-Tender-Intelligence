import { Component, computed, input } from '@angular/core';

export interface Segment {
  key: string;
  label: string;
  value: number;
  color: string;
}

/** One horizontal bar split into parts (2px gaps), with the legend as a list: colour, label, count and share. */
@Component({
  selector: 'eti-segmented-bar',
  template: `
    <div class="flex h-3 w-full gap-[2px] overflow-hidden rounded-[4px]" role="img" [attr.aria-label]="caption()">
      @for (s of parts(); track s.key) {
        <div class="h-full" [style.background]="s.color" [style.width.%]="s.share" [title]="s.label + ': ' + s.text"></div>
      }
    </div>
    <ul class="mt-5 space-y-3">
      @for (s of parts(); track s.key) {
        <li class="flex items-center gap-3 text-[14px]">
          <span class="size-2.5 shrink-0 rounded-full" [style.background]="s.color" aria-hidden="true"></span>
          <span class="min-w-0 flex-1 truncate text-ink-soft">{{ s.label }}</span>
          <span class="eti-num text-ink">{{ s.text }}</span>
          <span class="eti-num w-12 text-right text-ink-muted">{{ s.pctText }}</span>
        </li>
      }
    </ul>
  `,
})
export class SegmentedBarComponent {
  readonly segments = input.required<Segment[]>();
  readonly caption = input('');
  readonly format = input<(v: number) => string>((v) => String(v));

  protected readonly parts = computed(() => {
    const total = this.segments().reduce((a, s) => a + s.value, 0) || 1;
    return this.segments().map((s) => ({
      ...s,
      share: (s.value / total) * 100,
      text: this.format()(s.value),
      pctText: `${Math.round((s.value / total) * 100)}%`,
    }));
  });
}
