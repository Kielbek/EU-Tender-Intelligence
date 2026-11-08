import { Component, computed, input } from '@angular/core';

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info';

const TONES: Record<BadgeTone, string> = {
  neutral: 'bg-canvas text-ink-muted ring-border',
  success: 'bg-emerald-50 text-emerald-700',
  warning: 'bg-warning-soft text-warning ring-warning/25',
  danger: 'bg-danger-soft text-danger ring-danger/25',
  info: 'bg-accent-soft text-accent ring-accent/25',
};

@Component({
  selector: 'eti-badge',
  template: `<span [class]="classes()"><ng-content /></span>`,
})
export class BadgeComponent {
  readonly tone = input<BadgeTone>('neutral');
  protected readonly classes = computed(
    () =>
      'inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium leading-none ' +
      TONES[this.tone()],
  );
}
