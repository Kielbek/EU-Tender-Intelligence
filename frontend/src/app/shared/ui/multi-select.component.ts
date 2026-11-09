import { Component, ElementRef, HostListener, computed, inject, input, model, signal, viewChild } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideChevronDown, LucideSearch, LucideX } from '@lucide/angular';
import { fold, isSmallScreen } from './select.component';

export interface MultiOption {
  value: string;
  label: string;
  /** Heading the option is listed under (keep options of one group together). */
  group?: string;
  hint?: string;
}

/** A shortcut such as "All EU countries": selects exactly these values. */
export interface MultiShortcut {
  label: string;
  values: string[];
}

let nextId = 0;

/**
 * Multiple choice from a long list: opens above the content (a popover on desktop, a bottom sheet on phones) with a
 * search field, group headings and shortcuts; the chosen values are shown as removable chips under the field.
 */
@Component({
  selector: 'eti-multi-select',
  imports: [TranslocoPipe, LucideChevronDown, LucideSearch, LucideX],
  template: `
    <div class="relative">
      <button #trigger type="button" [id]="inputId()" aria-haspopup="listbox" [attr.aria-expanded]="open()"
              [attr.aria-controls]="listId" [disabled]="disabled()"
              class="mt-1.5 flex h-10 w-full items-center gap-2 rounded-[var(--radius-control)] border border-border-strong bg-surface px-3 text-left text-[13px] text-ink transition-colors hover:border-ink-faint focus:border-accent focus:ring-[3px] focus:ring-accent/10 focus:outline-none disabled:cursor-not-allowed disabled:bg-canvas disabled:text-ink-muted"
              [class.border-accent]="open()" (click)="toggle()">
        <span class="min-w-0 flex-1 truncate" [class.text-ink-faint]="!selected().length">{{ summary() }}</span>
        @if (selected().length) {
          <span class="eti-num rounded bg-accent-soft px-1.5 text-[11px] font-semibold text-accent">{{ selected().length }}</span>
        }
        <svg lucideChevronDown class="size-4 shrink-0 text-ink-muted transition-transform" [class.rotate-180]="open()" aria-hidden="true"></svg>
      </button>

      @if (open()) {
        @if (mobile()) {
          <div class="fixed inset-0 z-40 bg-navy-950/30" aria-hidden="true" (click)="close()"></div>
        }
        <div class="z-50 flex flex-col overflow-hidden border border-border bg-surface shadow-xl"
             [class]="mobile() ? 'fixed inset-x-0 bottom-0 max-h-[80dvh] rounded-t-2xl' : 'absolute left-0 right-0 mt-1 max-h-96 rounded-[var(--radius-control)]'"
             role="dialog" [attr.aria-label]="label()">
          <div class="flex items-center gap-2 border-b border-border px-3 py-2">
            <svg lucideSearch class="size-4 shrink-0 text-ink-faint" aria-hidden="true"></svg>
            <input #search type="text" class="min-w-0 flex-1 bg-transparent py-1 text-[13px] text-ink placeholder:text-ink-faint focus:outline-none"
                   [placeholder]="'search.filterList' | transloco" [attr.aria-label]="'search.filterList' | transloco"
                   [value]="filter()" (input)="filter.set($any($event.target).value)" (keydown.escape)="close(); $event.stopPropagation()" />
            @if (mobile()) {
              <button type="button" class="eti-icon-btn size-8" [attr.aria-label]="'common.close' | transloco" (click)="close()"><svg lucideX class="size-4" aria-hidden="true"></svg></button>
            }
          </div>
          @if (shortcuts().length || selected().length) {
            <div class="flex flex-wrap gap-1.5 border-b border-border px-3 py-2">
              @for (s of shortcuts(); track s.label) {
                <button type="button" class="rounded-full border border-border-strong px-2.5 py-1 text-[11px] font-medium text-ink-soft hover:border-accent hover:text-accent" (click)="apply(s)">{{ s.label }}</button>
              }
              @if (selected().length) {
                <button type="button" class="rounded-full px-2.5 py-1 text-[11px] font-medium text-accent hover:underline" (click)="clear()">{{ 'common.clearSelection' | transloco }}</button>
              }
            </div>
          }
          <ul [id]="listId" role="listbox" aria-multiselectable="true" class="flex-1 overflow-y-auto py-1">
            @for (o of visible(); track o.value; let i = $index) {
              @if (o.group && (i === 0 || visible()[i - 1].group !== o.group)) {
                <li role="presentation" class="px-3.5 pt-2.5 pb-1 text-[10px] font-semibold tracking-[0.08em] text-ink-faint uppercase">{{ o.group }}</li>
              }
              <li role="option" [attr.aria-selected]="isSelected(o.value)">
                <label class="mx-1 flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-2 text-[13px] hover:bg-canvas" [class.py-3]="mobile()">
                  <input type="checkbox" [checked]="isSelected(o.value)" (change)="toggleValue(o.value)" />
                  <span class="min-w-0 flex-1 truncate" [class.text-accent]="isSelected(o.value)" [class.font-medium]="isSelected(o.value)">{{ o.label }}</span>
                  @if (o.hint) { <span class="shrink-0 text-[11px] text-ink-muted">{{ o.hint }}</span> }
                </label>
              </li>
            } @empty {
              <li class="px-3.5 py-2 text-[13px] text-ink-muted" role="presentation">{{ 'search.noMatches' | transloco }}</li>
            }
          </ul>
          <div class="flex items-center justify-between border-t border-border px-3 py-2">
            <span class="text-[11px] text-ink-muted">{{ 'common.selectedN' | transloco: { n: selected().length } }}</span>
            <button type="button" class="eti-btn-primary h-8 px-3 text-xs" (click)="close()">{{ 'common.done' | transloco }}</button>
          </div>
        </div>
      }
    </div>

    @if (showChips() && selected().length) {
      <ul class="mt-2 flex flex-wrap gap-1.5" [attr.aria-label]="label()">
        @for (v of selected(); track v) {
          <li class="inline-flex items-center gap-1 rounded bg-canvas py-1 pr-1 pl-2 text-xs text-ink">
            {{ labelOf(v) }}
            @if (!disabled()) {
              <button type="button" class="rounded p-0.5 hover:text-accent" [attr.aria-label]="('common.remove' | transloco) + ': ' + labelOf(v)" (click)="toggleValue(v)">
                <svg lucideX class="size-3" aria-hidden="true"></svg>
              </button>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class MultiSelectComponent {
  readonly inputId = input(`eti-multi-${nextId++}`);
  readonly label = input.required<string>();
  readonly placeholder = input('—');
  readonly options = input.required<MultiOption[]>();
  readonly shortcuts = input<MultiShortcut[]>([]);
  readonly disabled = input(false);
  readonly showChips = input(true);
  readonly selected = model<string[]>([]);

  protected readonly listId = `eti-multi-list-${nextId++}`;
  protected readonly open = signal(false);
  protected readonly filter = signal('');
  protected readonly mobile = signal(false);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  private readonly search = viewChild<ElementRef<HTMLInputElement>>('search');

  protected readonly visible = computed(() => {
    const f = fold(this.filter().trim());
    return f ? this.options().filter((o) => fold(o.label).includes(f) || fold(o.hint ?? '').includes(f)
      || fold(o.group ?? '').includes(f)) : this.options();
  });
  protected readonly summary = computed(() => {
    const s = this.selected();
    if (!s.length) {
      return this.placeholder();
    }
    const names = s.map((v) => this.labelOf(v));
    return names.length <= 2 ? names.join(', ') : `${names.slice(0, 2).join(', ')} +${names.length - 2}`;
  });

  protected labelOf(v: string): string {
    return this.options().find((o) => o.value === v)?.label ?? v;
  }

  protected isSelected(v: string): boolean {
    return this.selected().includes(v);
  }

  protected toggle(): void {
    if (this.open()) {
      this.close();
      return;
    }
    this.mobile.set(isSmallScreen());
    this.filter.set('');
    this.open.set(true);
    setTimeout(() => this.search()?.nativeElement.focus());
  }

  protected close(): void {
    if (this.open()) {
      this.open.set(false);
      this.trigger()?.nativeElement.focus();
    }
  }

  protected toggleValue(v: string): void {
    this.selected.update((list) => (list.includes(v) ? list.filter((x) => x !== v) : [...list, v]));
  }

  protected apply(s: MultiShortcut): void {
    this.selected.set([...s.values]);
  }

  protected clear(): void {
    this.selected.set([]);
  }

  @HostListener('document:pointerdown', ['$event'])
  protected outside(e: PointerEvent): void {
    if (this.open() && !this.mobile() && !this.host.nativeElement.contains(e.target as Node)) {
      this.open.set(false);
    }
  }

  @HostListener('keydown.escape')
  protected escape(): void {
    this.close();
  }
}
