import { Component, input } from '@angular/core';

export interface ProgressRow {
  key: string;
  label: string;
  valueText: string;
  /** 0–100 */
  percent: number;
  color: string;
  hint?: string;
}

/** Labelled progress bars: label and value above a thick rounded track. */
@Component({
  selector: 'eti-progress-list',
  template: `
    <ul class="space-y-5">
      @for (r of rows(); track r.key) {
        <li>
          <div class="flex items-baseline gap-2 text-[14px]">
            <span class="min-w-0 flex-1 truncate text-ink-soft">{{ r.label }}</span>
            <span class="eti-num text-ink">{{ r.valueText }}</span>
            @if (r.hint) {
              <span class="eti-num text-ink-muted">{{ r.hint }}</span>
            }
          </div>
          <div class="mt-2 h-2.5 rounded-full bg-track" role="progressbar" [attr.aria-label]="r.label"
               aria-valuemin="0" aria-valuemax="100" [attr.aria-valuenow]="r.percent">
            <div class="h-2.5 rounded-full" [style.background]="r.color" [style.width.%]="r.percent"></div>
          </div>
        </li>
      }
    </ul>
  `,
})
export class ProgressListComponent {
  readonly rows = input.required<ProgressRow[]>();
}
