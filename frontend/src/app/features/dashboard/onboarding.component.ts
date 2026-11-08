import { Component, computed, effect, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideCheck, LucideX } from '@lucide/angular';
import { DashboardSummary } from '../../core/api/tender.model';

interface Step {
  key: 'profile' | 'watched' | 'savedSearch' | 'aiAnalysis' | 'teammate';
  done: boolean;
  link: string;
  query?: Record<string, string>;
}

const KEY = 'eti.onboarding.hidden.';

/** First steps for a new organization; progress comes from real data, hiding it is remembered per organization. */
@Component({
  selector: 'eti-onboarding',
  imports: [RouterLink, TranslocoPipe, LucideCheck, LucideX],
  template: `
    @if (visible()) {
      <section class="eti-card px-[22px] py-5" aria-labelledby="onboarding-heading">
        <div class="flex items-start gap-3">
          <div class="min-w-0 flex-1">
            <h2 id="onboarding-heading" class="eti-h2">{{ 'onboarding.title' | transloco }}</h2>
            <p class="mt-1 text-[13px] text-ink-muted">{{ 'onboarding.subtitle' | transloco }}</p>
          </div>
          <span class="eti-num shrink-0 text-[13px] text-ink-muted">{{ done() }}/{{ steps().length }}</span>
          <button type="button" class="inline-flex size-8 shrink-0 items-center justify-center rounded-md text-ink-muted hover:bg-canvas hover:text-ink"
                  [attr.aria-label]="'onboarding.hide' | transloco" [title]="'onboarding.hide' | transloco" (click)="hide()">
            <svg lucideX class="size-4" aria-hidden="true"></svg>
          </button>
        </div>
        <div class="mt-3 h-2 rounded-full bg-track" role="progressbar" aria-valuemin="0" [attr.aria-valuemax]="steps().length"
             [attr.aria-valuenow]="done()" [attr.aria-label]="'onboarding.title' | transloco">
          <div class="h-2 rounded-full bg-accent transition-all" [style.width.%]="(done() / steps().length) * 100"></div>
        </div>
        <ol class="mt-4 grid gap-2 sm:grid-cols-2 xl:grid-cols-5">
          @for (s of steps(); track s.key; let i = $index) {
            <li>
              <a [routerLink]="s.link" [queryParams]="s.query ?? null"
                 class="flex h-full items-start gap-3 rounded-[var(--radius-control)] border px-3 py-3 transition-colors"
                 [class]="s.done ? 'border-border bg-subtle' : 'border-border-strong hover:border-accent hover:bg-accent-soft'">
                <span class="mt-0.5 inline-flex size-6 shrink-0 items-center justify-center rounded-full text-[12px] font-semibold"
                      [class]="s.done ? 'bg-success text-white' : 'bg-track text-ink-soft'" aria-hidden="true">
                  @if (s.done) {
                    <svg lucideCheck class="size-3.5"></svg>
                  } @else {
                    {{ i + 1 }}
                  }
                </span>
                <span class="min-w-0">
                  <span class="block text-[14px] font-medium" [class.text-ink-muted]="s.done" [class.line-through]="s.done"
                        [class.text-ink]="!s.done">{{ 'onboarding.steps.' + s.key + '.title' | transloco }}</span>
                  <span class="mt-0.5 block text-[12px] text-ink-muted">{{ 'onboarding.steps.' + s.key + '.hint' | transloco }}</span>
                  <span class="sr-only">{{ (s.done ? 'company.done' : 'company.todo') | transloco }}</span>
                </span>
              </a>
            </li>
          }
        </ol>
      </section>
    }
  `,
})
export class OnboardingComponent {
  readonly data = input.required<DashboardSummary>();
  readonly orgId = input.required<string>();
  private readonly hidden = signal(false);

  constructor() {
    effect(() => this.hidden.set(read(KEY + this.orgId())));
  }

  protected readonly steps = computed<Step[]>(() => {
    const o = this.data().onboarding;
    return [
      { key: 'profile', done: o.profile, link: '/company' },
      { key: 'watched', done: o.watched, link: o.profile ? '/matches' : '/search', query: o.profile ? undefined : { status: 'OPEN' } },
      { key: 'savedSearch', done: o.savedSearch, link: '/search', query: { status: 'OPEN' } },
      { key: 'aiAnalysis', done: o.aiAnalysis, link: '/watchlist' },
      { key: 'teammate', done: o.teammate, link: '/organization' },
    ];
  });
  protected readonly done = computed(() => this.steps().filter((s) => s.done).length);
  protected readonly visible = computed(() => !this.hidden() && this.done() < this.steps().length);

  protected hide(): void {
    this.hidden.set(true);
    try {
      localStorage.setItem(KEY + this.orgId(), '1');
    } catch {
      // storage unavailable – hidden for this visit only
    }
  }
}

function read(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}
