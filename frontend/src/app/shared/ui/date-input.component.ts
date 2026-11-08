import { Component, ElementRef, computed, forwardRef, inject, input, model, signal, viewChild } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideCalendar, LucideChevronLeft, LucideChevronRight, LucideX } from '@lucide/angular';
import { isSmallScreen } from './select.component';

let nextId = 0;

interface Day {
  iso: string;
  day: number;
  inMonth: boolean;
  today: boolean;
}

function iso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function parse(v: string | null | undefined): Date | null {
  if (!v || !/^\d{4}-\d{2}-\d{2}$/.test(v)) {
    return null;
  }
  const [y, m, d] = v.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/**
 * Date field with its own calendar (no native picker): popover on desktop, bottom sheet on phones. Value is an ISO
 * date string (YYYY-MM-DD) or ''. Keyboard: arrows move by day/week, PageUp/PageDown by month, Enter picks,
 * Escape closes. Weeks start on Monday (European convention).
 */
@Component({
  selector: 'eti-date-input',
  imports: [TranslocoPipe, LucideCalendar, LucideChevronLeft, LucideChevronRight, LucideX],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => DateInputComponent), multi: true }],
  host: { class: 'relative block', '(document:pointerdown)': 'onDocumentPointer($event)' },
  template: `
    <div class="relative mt-1">
      <button #trigger type="button" [id]="inputId()" aria-haspopup="dialog" [attr.aria-expanded]="open()"
              [attr.aria-label]="ariaLabel()" [disabled]="isDisabled()"
              class="flex h-10 w-full items-center gap-2 rounded-[var(--radius-control)] border border-border-strong bg-surface px-3 text-left text-sm text-ink transition-colors hover:border-ink-faint focus:border-accent focus:outline-none focus:ring-4 focus:ring-accent-soft disabled:cursor-not-allowed disabled:bg-canvas"
              [class.border-accent]="open()" [class.ring-4]="open()" [class.ring-accent-soft]="open()"
              [class.pr-9]="!!value()" (click)="toggle()" (keydown)="onTriggerKey($event)">
        <svg lucideCalendar class="size-4 shrink-0 text-ink-muted" aria-hidden="true"></svg>
        <span class="min-w-0 flex-1 truncate" [class.text-ink-faint]="!value()">{{ display() }}</span>
      </button>
      @if (value() && !isDisabled()) {
        <button type="button" class="absolute top-1/2 right-1.5 inline-flex size-7 -translate-y-1/2 items-center justify-center rounded-md text-ink-muted hover:bg-canvas hover:text-ink"
                [attr.aria-label]="'common.clearDate' | transloco" (click)="clear()">
          <svg lucideX class="size-3.5" aria-hidden="true"></svg>
        </button>
      }
    </div>

    @if (open()) {
      @if (mobile()) {
        <div class="fixed inset-0 z-50 bg-navy-950/40" aria-hidden="true" (click)="close(true)"></div>
      }
      <div role="dialog" [attr.aria-label]="ariaLabel() ?? ('common.chooseDate' | transloco)"
           class="z-50 border border-border bg-surface p-3 shadow-[0_12px_32px_-8px_rgba(10,15,28,0.18)]"
           [class]="mobile()
             ? 'fixed inset-x-0 bottom-0 rounded-t-[16px] p-4 pb-[max(1rem,env(safe-area-inset-bottom))]'
             : 'absolute left-0 mt-1.5 w-[296px] rounded-[var(--radius-control)]'"
           [class.bottom-full]="!mobile() && dropUp()" [class.mb-1.5]="!mobile() && dropUp()"
           (keydown)="onGridKey($event)">
        <div class="flex items-center gap-1">
          <button type="button" class="inline-flex size-9 items-center justify-center rounded-md text-ink-soft hover:bg-canvas"
                  [attr.aria-label]="'common.prevMonth' | transloco" (click)="shiftMonth(-1)">
            <svg lucideChevronLeft class="size-4" aria-hidden="true"></svg>
          </button>
          <p class="flex-1 text-center text-sm font-semibold text-ink capitalize" aria-live="polite">{{ monthLabel() }}</p>
          <button type="button" class="inline-flex size-9 items-center justify-center rounded-md text-ink-soft hover:bg-canvas"
                  [attr.aria-label]="'common.nextMonth' | transloco" (click)="shiftMonth(1)">
            <svg lucideChevronRight class="size-4" aria-hidden="true"></svg>
          </button>
        </div>
        <table class="mt-2 w-full table-fixed border-collapse text-center" role="grid">
          <thead>
            <tr>
              @for (w of weekdays(); track $index) {
                <th scope="col" class="pb-1 text-[11px] font-medium text-ink-muted uppercase">{{ w }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (week of weeks(); track $index) {
              <tr>
                @for (d of week; track d.iso) {
                  <td class="p-0.5">
                    <button type="button" [attr.data-iso]="d.iso" [attr.tabindex]="d.iso === focusIso() ? 0 : -1"
                            [attr.aria-pressed]="d.iso === value()" [attr.aria-label]="fullLabel(d.iso)"
                            class="inline-flex w-full items-center justify-center rounded-md text-sm tabular-nums outline-none focus-visible:ring-2 focus-visible:ring-accent"
                            [class]="dayClass(d)"
                            (click)="pick(d.iso)">
                      {{ d.day }}
                    </button>
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
        <div class="mt-2 flex justify-between border-t border-border pt-2">
          <button type="button" class="eti-btn-ghost h-8 px-2 text-[13px]" (click)="pick(todayIso)">{{ 'common.today' | transloco }}</button>
          <button type="button" class="eti-btn-ghost h-8 px-2 text-[13px]" (click)="clear()">{{ 'common.clear' | transloco }}</button>
        </div>
      </div>
    }
  `,
})
export class DateInputComponent implements ControlValueAccessor {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  readonly inputId = input<string>(`eti-date-${nextId++}`);
  readonly ariaLabel = input<string | null>(null);
  readonly locale = input('pl');
  readonly disabled = input(false);
  readonly value = model<string>('');

  protected readonly open = signal(false);
  protected readonly mobile = signal(false);
  protected readonly dropUp = signal(false);
  /** First day of the month shown. */
  protected readonly month = signal(new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  protected readonly focusIso = signal('');
  protected readonly todayIso = iso(new Date());
  private readonly cvaDisabled = signal(false);
  private onChange: (v: string) => void = () => undefined;
  private onTouched: () => void = () => undefined;
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly isDisabled = computed(() => this.disabled() || this.cvaDisabled());

  protected readonly display = computed(() => {
    const d = parse(this.value());
    if (!d) {
      return this.locale() === 'pl' ? 'dd.mm.rrrr' : 'dd/mm/yyyy';
    }
    return new Intl.DateTimeFormat(this.locale(), { day: '2-digit', month: '2-digit', year: 'numeric' }).format(d);
  });

  protected readonly monthLabel = computed(() =>
    new Intl.DateTimeFormat(this.locale(), { month: 'long', year: 'numeric' }).format(this.month()),
  );

  protected readonly weekdays = computed(() => {
    const fmt = new Intl.DateTimeFormat(this.locale(), { weekday: 'short' });
    // 2024-01-01 was a Monday.
    return Array.from({ length: 7 }, (_, i) => fmt.format(new Date(2024, 0, 1 + i)).replace('.', ''));
  });

  protected readonly weeks = computed<Day[][]>(() => {
    const first = this.month();
    const offset = (first.getDay() + 6) % 7; // Monday = 0
    const start = new Date(first.getFullYear(), first.getMonth(), 1 - offset);
    const days: Day[] = [];
    for (let i = 0; i < 42; i++) {
      const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i);
      days.push({ iso: iso(d), day: d.getDate(), inMonth: d.getMonth() === first.getMonth(), today: iso(d) === this.todayIso });
    }
    const weeks: Day[][] = [];
    for (let i = 0; i < 6; i++) {
      weeks.push(days.slice(i * 7, i * 7 + 7));
    }
    return weeks;
  });

  protected dayClass(d: Day): string {
    const size = this.mobile() ? 'h-11' : 'h-9';
    if (d.iso === this.value()) {
      return `${size} bg-accent font-semibold text-white`;
    }
    const tone = d.inMonth ? 'text-ink' : 'text-ink-faint';
    const today = d.today ? 'font-semibold ring-1 ring-inset ring-border-strong' : '';
    return `${size} ${tone} ${today} hover:bg-canvas`;
  }

  protected fullLabel(isoDate: string): string {
    return new Intl.DateTimeFormat(this.locale(), { dateStyle: 'full' }).format(parse(isoDate)!);
  }

  writeValue(v: string | null): void {
    this.value.set(v ?? '');
  }

  registerOnChange(fn: (v: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.cvaDisabled.set(disabled);
  }

  protected toggle(): void {
    if (this.open()) {
      this.close(false);
      return;
    }
    const d = parse(this.value()) ?? new Date();
    this.month.set(new Date(d.getFullYear(), d.getMonth(), 1));
    this.focusIso.set(iso(d));
    this.mobile.set(isSmallScreen());
    const rect = this.trigger().nativeElement.getBoundingClientRect();
    this.dropUp.set(!this.mobile() && window.innerHeight - rect.bottom < 380 && rect.top > 400);
    this.open.set(true);
    this.focusDay();
  }

  close(returnFocus: boolean): void {
    if (!this.open()) {
      return;
    }
    this.open.set(false);
    this.onTouched();
    if (returnFocus) {
      this.trigger().nativeElement.focus();
    }
  }

  protected pick(isoDate: string): void {
    this.set(isoDate);
    this.close(true);
  }

  protected clear(): void {
    this.set('');
    this.close(true);
  }

  private set(v: string): void {
    if (v !== this.value()) {
      this.value.set(v);
      this.onChange(v);
    }
  }

  protected shiftMonth(delta: number): void {
    const m = this.month();
    this.month.set(new Date(m.getFullYear(), m.getMonth() + delta, 1));
    const f = parse(this.focusIso()) ?? m;
    const target = new Date(f.getFullYear(), f.getMonth() + delta, Math.min(f.getDate(), 28));
    this.focusIso.set(iso(target));
  }

  protected onTriggerKey(e: KeyboardEvent): void {
    if (!this.open() && (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      this.toggle();
    }
  }

  protected onGridKey(e: KeyboardEvent): void {
    const f = parse(this.focusIso());
    const moves: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 };
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      this.close(true);
      return;
    }
    if (!f || !(e.target as HTMLElement).dataset['iso']) {
      return;
    }
    let next: Date | null = null;
    if (e.key in moves) {
      next = new Date(f.getFullYear(), f.getMonth(), f.getDate() + moves[e.key]);
    } else if (e.key === 'PageUp' || e.key === 'PageDown') {
      next = new Date(f.getFullYear(), f.getMonth() + (e.key === 'PageUp' ? -1 : 1), f.getDate());
    } else if (e.key === 'Home' || e.key === 'End') {
      const dow = (f.getDay() + 6) % 7;
      next = new Date(f.getFullYear(), f.getMonth(), f.getDate() + (e.key === 'Home' ? -dow : 6 - dow));
    } else {
      return;
    }
    e.preventDefault();
    this.focusIso.set(iso(next));
    if (next.getMonth() !== this.month().getMonth() || next.getFullYear() !== this.month().getFullYear()) {
      this.month.set(new Date(next.getFullYear(), next.getMonth(), 1));
    }
    this.focusDay();
  }

  private focusDay(): void {
    setTimeout(() => {
      this.host.nativeElement.querySelector<HTMLElement>(`[data-iso="${this.focusIso()}"]`)?.focus();
    });
  }

  protected onDocumentPointer(e: Event): void {
    if (this.open() && !this.mobile() && !this.host.nativeElement.contains(e.target as Node)) {
      this.close(false);
    }
  }
}
