import { Component, DestroyRef, inject, input, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideX } from '@lucide/angular';
import { Subject, catchError, debounceTime, distinctUntilChanged, of, switchMap } from 'rxjs';
import { CpvOption } from '../../core/api/tender.model';
import { TenderService } from '../../core/api/tender.service';

let nextId = 0;

/**
 * CPV autocomplete (official CPV 2008 vocabulary, labels in the UI language). Accepts a code prefix ("45") or words.
 * Implements the ARIA combobox pattern: arrow keys move, Enter selects, Escape closes.
 */
@Component({
  selector: 'eti-cpv-picker',
  imports: [TranslocoPipe, LucideX],
  template: `
    <label class="eti-label" [for]="inputId">{{ label() }}</label>
    @if (selected().length) {
      <ul class="mt-1 flex flex-wrap gap-1">
        @for (code of selected(); track code) {
          <li class="inline-flex items-center gap-1 rounded-md bg-accent-soft px-2 py-0.5 text-xs text-accent">
            <span class="font-mono">{{ code }}</span>
            @if (labels()[code] ?? knownLabels()[code]; as l) {
              <span class="max-w-40 truncate">{{ l }}</span>
            }
            <button type="button" class="rounded hover:bg-accent/10"
                    [attr.aria-label]="'search.removeCpv' | transloco: { code }" (click)="remove(code)">
              <svg lucideX class="size-3" aria-hidden="true"></svg>
            </button>
          </li>
        }
      </ul>
    }
    <div class="relative">
      <input [id]="inputId" class="eti-input" type="text" autocomplete="off" role="combobox"
             [attr.aria-expanded]="open()" [attr.aria-controls]="listId"
             [attr.aria-activedescendant]="active() >= 0 ? listId + '-' + active() : null"
             [placeholder]="'search.cpvPlaceholder' | transloco" [value]="query()"
             (input)="onInput($any($event.target).value)" (keydown)="onKey($event)" (blur)="closeSoon()" />
      @if (open()) {
        <ul [id]="listId" role="listbox"
            class="absolute z-20 mt-1 max-h-72 w-full overflow-auto rounded-md border border-border bg-surface py-1 text-sm shadow-lg">
          @for (o of options(); track o.code; let i = $index) {
            <li [id]="listId + '-' + i" role="option" [attr.aria-selected]="i === active()"
                class="cursor-pointer px-3 py-1.5"
                [class.bg-accent-soft]="i === active()"
                (mousedown)="$event.preventDefault(); pick(o)">
              <span class="font-mono text-xs text-ink-muted">{{ o.code }}</span>
              <span class="ml-2">{{ o.label }}</span>
            </li>
          } @empty {
            <li class="px-3 py-1.5 text-ink-muted">{{ 'search.noCpv' | transloco }}</li>
          }
        </ul>
      }
    </div>
  `,
})
export class CpvPickerComponent {
  private readonly api = inject(TenderService);
  readonly label = input.required<string>();
  readonly lang = input.required<string>();
  readonly selected = model<string[]>([]);
  /** Labels of codes selected before (e.g. returned by the API with a saved profile). */
  readonly knownLabels = input<Record<string, string>>({});
  /** Known labels of selected codes (filled when picked). */
  protected readonly labels = signal<Record<string, string>>({});
  protected readonly query = signal('');
  protected readonly options = signal<CpvOption[]>([]);
  protected readonly open = signal(false);
  protected readonly active = signal(-1);
  protected readonly inputId = `cpv-${nextId++}`;
  protected readonly listId = `${this.inputId}-list`;
  private readonly queries = new Subject<string>();

  constructor() {
    this.queries
      .pipe(
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((q) => (q.trim().length < 2 ? of([]) : this.api.cpv(q.trim(), this.lang()).pipe(catchError(() => of([]))))),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((list) => {
        this.options.set(list);
        this.active.set(list.length ? 0 : -1);
        this.open.set(this.query().trim().length >= 2);
      });
  }

  protected onInput(value: string): void {
    this.query.set(value);
    this.queries.next(value);
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.options().length;
    if (event.key === 'ArrowDown' && n) {
      event.preventDefault();
      this.open.set(true);
      this.active.update((i) => (i + 1) % n);
    } else if (event.key === 'ArrowUp' && n) {
      event.preventDefault();
      this.active.update((i) => (i - 1 + n) % n);
    } else if (event.key === 'Enter' && this.open() && this.active() >= 0) {
      event.preventDefault();
      this.pick(this.options()[this.active()]);
    } else if (event.key === 'Escape') {
      this.open.set(false);
    }
  }

  protected pick(o: CpvOption): void {
    if (!this.selected().includes(o.code)) {
      this.selected.update((list) => [...list, o.code]);
    }
    this.labels.update((l) => ({ ...l, [o.code]: o.label }));
    this.query.set('');
    this.open.set(false);
    this.options.set([]);
  }

  protected remove(code: string): void {
    this.selected.update((list) => list.filter((c) => c !== code));
  }

  protected closeSoon(): void {
    setTimeout(() => this.open.set(false), 150);
  }
}
