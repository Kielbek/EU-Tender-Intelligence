import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideCheck, LucideCircleHelp, LucideX } from '@lucide/angular';
import { MatchBreakdown } from '../../core/api/matching.model';
import { reasonText } from './match-reasons';

/** Score components and the reasons behind them: met, not met and missing information. */
@Component({
  selector: 'eti-match-explanation',
  imports: [TranslocoPipe, LucideCheck, LucideX, LucideCircleHelp],
  template: `
    <dl class="space-y-2.5">
      @for (c of breakdown().components; track c.key) {
        <div>
          <div class="flex justify-between gap-2 text-[13px]">
            <dt class="text-ink-soft">{{ 'match.component.' + c.key | transloco }}</dt>
            <dd class="eti-num text-ink">{{ c.points }}<span class="text-ink-faint"> / {{ c.max }}</span></dd>
          </div>
          <div class="mt-1 h-1.5 rounded bg-track" aria-hidden="true">
            <div class="h-1.5 rounded bg-chart" [style.width.%]="(c.points / c.max) * 100"></div>
          </div>
        </div>
      }
    </dl>
    @if (reasons().met.length) {
      <h3 class="mt-5 text-xs font-semibold text-ink-muted">{{ 'match.met' | transloco }}</h3>
      <ul class="mt-1.5 space-y-1.5 text-[13px]">
        @for (r of reasons().met; track $index) {
          <li class="flex gap-2"><svg lucideCheck class="mt-0.5 size-3.5 shrink-0 text-success" aria-hidden="true"></svg>{{ r }}</li>
        }
      </ul>
    }
    @if (reasons().unmet.length) {
      <h3 class="mt-4 text-xs font-semibold text-ink-muted">{{ 'match.unmet' | transloco }}</h3>
      <ul class="mt-1.5 space-y-1.5 text-[13px]">
        @for (r of reasons().unmet; track $index) {
          <li class="flex gap-2"><svg lucideX class="mt-0.5 size-3.5 shrink-0 text-danger" aria-hidden="true"></svg>{{ r }}</li>
        }
      </ul>
    }
    @if (reasons().missing.length) {
      <h3 class="mt-4 text-xs font-semibold text-ink-muted">{{ 'match.missing' | transloco }}</h3>
      <ul class="mt-1.5 space-y-1.5 text-[13px]">
        @for (r of reasons().missing; track $index) {
          <li class="flex gap-2 text-ink-muted"><svg lucideCircleHelp class="mt-0.5 size-3.5 shrink-0" aria-hidden="true"></svg>{{ r }}</li>
        }
      </ul>
    }
  `,
})
export class MatchExplanationComponent {
  private readonly transloco = inject(TranslocoService);
  readonly breakdown = input.required<MatchBreakdown>();
  readonly lang = input.required<string>();
  /** Re-evaluated once the active language's translations are loaded. */
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  protected readonly reasons = computed(() => {
    this.translations();
    const b = this.breakdown();
    const lang = this.lang();
    const text = (list: MatchBreakdown['met']) => list.map((r) => reasonText(r, this.transloco, lang));
    return { met: text(b.met), unmet: text(b.unmet), missing: text(b.missing) };
  });
}
