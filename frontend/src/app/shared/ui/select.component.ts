import {
  Component,
  ElementRef,
  computed,
  forwardRef,
  inject,
  input,
  model,
  signal,
  viewChild,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideCheck, LucideChevronDown, LucideSearch, LucideX } from '@lucide/angular';

export interface SelectOption {
  value: string;
  label: string;
  /** Secondary text shown on the right (e.g. a code). */
  hint?: string;
}

let nextId = 0;

/** Small screens get a bottom sheet with large touch targets; larger screens a dropdown under the field. */
export function isSmallScreen(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(max-width: 639px)').matches;
}

/**
 * Custom select (no native dropdown): a button opening a listbox – a popover on desktop, a bottom sheet on phones.
 * Works with formControlName / ngModel (ControlValueAccessor) or with [(value)]. ARIA select-only combobox pattern:
 * arrows, Home/End, Enter, Escape, type-ahead; a filter field appears for long lists.
 */
@Component({
  selector: 'eti-select',
  imports: [TranslocoPipe, LucideCheck, LucideChevronDown, LucideSearch, LucideX],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => SelectComponent), multi: true }],
  host: {
    class: 'relative block',
    '(document:pointerdown)': 'onDocumentPointer($event)',
  },
  template: `
    <button #trigger type="button" [id]="inputId()" role="combobox" aria-haspopup="listbox"
            [attr.aria-expanded]="open()" [attr.aria-controls]="listId" [attr.aria-label]="ariaLabel()"
            [attr.aria-activedescendant]="open() && !mobile() && active() >= 0 ? optionId(active()) : null"
            [disabled]="isDisabled()"
            class="flex w-full items-center gap-2 rounded-[var(--radius-control)] border border-border-strong bg-surface px-3 text-left text-[13px] text-ink transition-colors hover:border-ink-faint focus:border-accent focus:outline-none focus:ring-[3px] focus:ring-accent/10 disabled:cursor-not-allowed disabled:bg-canvas disabled:text-ink-muted"
            [class.h-10]="size() === 'md'" [class.h-8]="size() === 'sm'" [class.mt-1]="spaced()"
            [class.border-accent]="open()" [class.ring-4]="open()" [class.ring-accent-soft]="open()"
            (click)="toggle()" (keydown)="onTriggerKey($event)">
      @if (prefix()) {
        <span class="shrink-0 text-ink-muted">{{ prefix() }}</span>
      }
      <span class="min-w-0 flex-1 truncate" [class.text-ink-faint]="!selected()">
        {{ selected()?.label ?? placeholder() }}
      </span>
      <svg lucideChevronDown class="size-4 shrink-0 text-ink-muted transition-transform" [class.rotate-180]="open()"
           aria-hidden="true"></svg>
    </button>

    @if (open()) {
      @if (mobile()) {
        <div class="fixed inset-0 z-50 bg-navy-950/40" aria-hidden="true" (click)="close(true)"></div>
      }
      <div class="z-50 flex flex-col overflow-hidden border border-border bg-surface shadow-[0_12px_32px_-8px_rgba(10,15,28,0.18)]"
           [class]="mobile()
             ? 'fixed inset-x-0 bottom-0 max-h-[75dvh] rounded-t-[16px] pb-[env(safe-area-inset-bottom)]'
             : 'absolute left-0 mt-1.5 max-h-80 min-w-full w-max max-w-[min(28rem,90vw)] rounded-[var(--radius-control)]'"
           [class.bottom-full]="!mobile() && dropUp()" [class.mb-1.5]="!mobile() && dropUp()">
        @if (mobile()) {
          <div class="flex items-center gap-2 border-b border-border px-4 py-3">
            <span class="mx-auto h-1 w-10 rounded-full bg-border-strong absolute left-1/2 top-1.5 -translate-x-1/2" aria-hidden="true"></span>
            <p class="min-w-0 flex-1 truncate pt-1 text-[15px] font-semibold text-ink">{{ ariaLabel() ?? placeholder() }}</p>
            <button type="button" class="inline-flex size-9 items-center justify-center rounded-md text-ink-muted hover:bg-canvas"
                    [attr.aria-label]="'common.close' | transloco" (click)="close(true)">
              <svg lucideX class="size-5" aria-hidden="true"></svg>
            </button>
          </div>
        }
        @if (filterable()) {
          <div class="border-b border-border p-2">
            <div class="flex h-9 items-center gap-2 rounded-md border border-border-strong px-2.5 focus-within:border-accent">
              <svg lucideSearch class="size-4 shrink-0 text-ink-faint" aria-hidden="true"></svg>
              <input #filterInput type="text" class="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-ink-faint"
                     [placeholder]="'search.filterList' | transloco" [attr.aria-label]="'search.filterList' | transloco"
                     [attr.aria-controls]="listId" [attr.aria-activedescendant]="active() >= 0 ? optionId(active()) : null"
                     [value]="filter()" (input)="onFilter($any($event.target).value)" (keydown)="onListKey($event)" />
            </div>
          </div>
        }
        <ul #list [id]="listId" role="listbox" tabindex="-1" [attr.aria-label]="ariaLabel() ?? placeholder()"
            class="flex-1 overflow-y-auto py-1 outline-none" (keydown)="onListKey($event)">
          @for (o of visible(); track o.value; let i = $index) {
            <li [id]="optionId(i)" role="option" [attr.aria-selected]="o.value === value()"
                class="mx-1 flex cursor-pointer items-center gap-2 rounded-md px-2.5"
                [class]="optionClass(o, i)"
                (pointerenter)="active.set(i)" (click)="choose(o)">
              <span class="min-w-0 flex-1 truncate">{{ o.label }}</span>
              @if (o.hint) {
                <span class="shrink-0 text-xs text-ink-muted">{{ o.hint }}</span>
              }
              <span class="inline-flex size-4 shrink-0" aria-hidden="true">
                @if (o.value === value()) {
                  <svg lucideCheck class="size-4"></svg>
                }
              </span>
            </li>
          } @empty {
            <li class="px-3.5 py-2 text-sm text-ink-muted" role="presentation">{{ 'search.noMatches' | transloco }}</li>
          }
        </ul>
      </div>
    }
  `,
})
export class SelectComponent implements ControlValueAccessor {
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  readonly options = input.required<SelectOption[]>();
  readonly placeholder = input('—');
  /** Accessible name when no <label for> points at {@link inputId}; also the bottom-sheet title. */
  readonly ariaLabel = input<string | null>(null);
  readonly inputId = input<string>(`eti-select-${nextId}`);
  readonly size = input<'md' | 'sm'>('md');
  /** Text before the value inside the field (e.g. "Sort:"). */
  readonly prefix = input<string | null>(null);
  /** Top margin like .eti-input (fields under a label). */
  readonly spaced = input(true);
  readonly disabled = input(false);
  readonly value = model<string | null>(null);

  protected readonly listId = `eti-select-list-${nextId++}`;
  protected readonly open = signal(false);
  protected readonly mobile = signal(false);
  protected readonly dropUp = signal(false);
  protected readonly active = signal(-1);
  protected readonly filter = signal('');
  private readonly cvaDisabled = signal(false);
  private typeahead = '';
  private typeaheadAt = 0;
  private onChange: (v: string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private readonly list = viewChild<ElementRef<HTMLUListElement>>('list');
  private readonly filterInput = viewChild<ElementRef<HTMLInputElement>>('filterInput');

  protected readonly isDisabled = computed(() => this.disabled() || this.cvaDisabled());
  protected readonly selected = computed(() => this.options().find((o) => o.value === (this.value() ?? '')) ?? null);
  protected readonly filterable = computed(() => this.options().length > 10);
  protected readonly visible = computed(() => {
    const f = this.filter().trim().toLocaleLowerCase();
    return f ? this.options().filter((o) => o.label.toLocaleLowerCase().includes(f)) : this.options();
  });

  protected optionClass(o: SelectOption, i: number): string {
    const size = this.mobile() ? 'py-3 text-[15px]' : 'py-2 text-[13px]';
    const bg = i === this.active() ? 'bg-canvas' : '';
    const tone = o.value === this.value() ? 'font-medium text-accent' : i === this.active() ? 'text-ink' : 'text-ink-soft';
    return `${size} ${bg} ${tone}`;
  }

  protected optionId(i: number): string {
    return `${this.listId}-${i}`;
  }

  // --- ControlValueAccessor -------------------------------------------------------------------------------------

  writeValue(v: string | null): void {
    this.value.set(v ?? '');
  }

  registerOnChange(fn: (v: string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.cvaDisabled.set(disabled);
  }

  // --- behaviour ------------------------------------------------------------------------------------------------

  protected toggle(): void {
    if (this.open()) {
      this.close(false);
    } else {
      this.openPanel();
    }
  }

  private openPanel(): void {
    if (this.isDisabled()) {
      return;
    }
    this.mobile.set(isSmallScreen());
    const rect = this.trigger().nativeElement.getBoundingClientRect();
    this.dropUp.set(!this.mobile() && window.innerHeight - rect.bottom < 300 && rect.top > 340);
    this.filter.set('');
    this.active.set(Math.max(0, this.visible().findIndex((o) => o.value === this.value())));
    this.open.set(true);
    setTimeout(() => {
      (this.filterInput()?.nativeElement ?? (this.mobile() ? this.list()?.nativeElement : null))?.focus();
      this.scrollActive();
    });
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

  protected choose(o: SelectOption): void {
    if (o.value !== this.value()) {
      this.value.set(o.value);
      this.onChange(o.value);
    }
    this.close(true);
  }

  protected onFilter(v: string): void {
    this.filter.set(v);
    this.active.set(this.visible().length ? 0 : -1);
  }

  protected onTriggerKey(e: KeyboardEvent): void {
    if (this.open()) {
      this.onListKey(e);
      return;
    }
    if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(e.key)) {
      e.preventDefault();
      this.openPanel();
    } else if (e.key.length === 1 && /\S/.test(e.key)) {
      this.jumpTo(e.key, true);
    }
  }

  protected onListKey(e: KeyboardEvent): void {
    const n = this.visible().length;
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        this.active.update((i) => Math.min(n - 1, i + 1));
        break;
      case 'ArrowUp':
        e.preventDefault();
        this.active.update((i) => Math.max(0, i - 1));
        break;
      case 'Home':
        e.preventDefault();
        this.active.set(0);
        break;
      case 'End':
        e.preventDefault();
        this.active.set(n - 1);
        break;
      case 'Enter':
        e.preventDefault();
        if (this.active() >= 0 && this.active() < n) {
          this.choose(this.visible()[this.active()]);
        }
        break;
      case 'Escape':
        e.preventDefault();
        e.stopPropagation();
        this.close(true);
        break;
      case 'Tab':
        this.close(false);
        break;
      default:
        if (!this.filterable() && e.key.length === 1 && /\S/.test(e.key)) {
          this.jumpTo(e.key, false);
        }
        return;
    }
    this.scrollActive();
  }

  /** Type-ahead: jump to the first option starting with the typed letters. */
  private jumpTo(key: string, selectDirectly: boolean): void {
    const now = Date.now();
    this.typeahead = now - this.typeaheadAt < 700 ? this.typeahead + key.toLowerCase() : key.toLowerCase();
    this.typeaheadAt = now;
    const i = this.visible().findIndex((o) => o.label.toLowerCase().startsWith(this.typeahead));
    if (i < 0) {
      return;
    }
    if (selectDirectly) {
      const o = this.visible()[i];
      this.value.set(o.value);
      this.onChange(o.value);
    } else {
      this.active.set(i);
      this.scrollActive();
    }
  }

  private scrollActive(): void {
    setTimeout(() => {
      const el = this.list()?.nativeElement.querySelector<HTMLElement>(`#${this.optionId(this.active())}`);
      el?.scrollIntoView({ block: 'nearest' });
    });
  }

  protected onDocumentPointer(e: Event): void {
    if (this.open() && !this.mobile() && !this.host.nativeElement.contains(e.target as Node)) {
      this.close(false);
    }
  }
}
