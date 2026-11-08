import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** Fit score 0–100 as a compact ring with the number inside. Colour bands: ≥ 80 strong, ≥ 60 good, below neutral. */
@Component({
  selector: 'eti-match-score',
  imports: [TranslocoPipe],
  template: `
    <span class="relative inline-flex shrink-0 items-center justify-center" [style.width.px]="px()" [style.height.px]="px()"
          role="img" [attr.aria-label]="'match.scoreAria' | transloco: { score: score() }">
      <svg [attr.viewBox]="'0 0 36 36'" class="absolute inset-0 -rotate-90" aria-hidden="true">
        <circle cx="18" cy="18" r="15.5" fill="none" stroke="var(--color-track)" stroke-width="3" />
        <circle cx="18" cy="18" r="15.5" fill="none" [attr.stroke]="color()" stroke-width="3" stroke-linecap="round"
                [attr.stroke-dasharray]="dash()" />
      </svg>
      <span class="eti-num relative font-semibold text-ink" [class.text-[13px]]="size() === 'sm'"
            [class.text-lg]="size() === 'lg'">{{ score() }}</span>
    </span>
  `,
})
export class MatchScoreComponent {
  readonly score = input.required<number>();
  readonly size = input<'sm' | 'lg'>('sm');
  protected readonly px = computed(() => (this.size() === 'lg' ? 64 : 40));
  /** Circumference of r = 15.5 ≈ 97.4. */
  protected readonly dash = computed(() => `${(Math.max(0, Math.min(100, this.score())) / 100) * 97.4} 97.4`);
  protected readonly color = computed(() =>
    this.score() >= 80 ? 'var(--color-success)' : this.score() >= 60 ? 'var(--color-accent)' : 'var(--color-ink-faint)',
  );
}
