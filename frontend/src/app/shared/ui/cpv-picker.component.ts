import { Component, DestroyRef, computed, inject, input, model, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideCheck, LucideChevronLeft, LucideChevronRight, LucideX } from '@lucide/angular';
import { Observable, Subject, catchError, combineLatest, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { CpvOption } from '../../core/api/tender.model';
import { TenderService } from '../../core/api/tender.service';

let nextId = 0;

/** Same rule as the server: up to two final letters cut, so "drogi" also finds "drogowe". */
export function cpvStems(query: string): string[] {
  const q = query.trim();
  if (!q || /^\d+$/.test(q)) {
    return [];
  }
  return q.split(/[^\p{L}\p{N}]+/u).filter(Boolean).slice(0, 6)
    .map((w) => foldChars(w.length <= 4 ? w : w.slice(0, Math.max(4, w.length - 2))));
}

/** Lower case without diacritics, one character for one character (indexes stay aligned with the original). */
function foldChars(s: string): string {
  return [...s].map((ch) => ch.toLocaleLowerCase().replace('ł', 'l').normalize('NFD').replace(/\p{M}+/gu, '')[0] ?? ch).join('');
}

export interface Segment {
  text: string;
  hit: boolean;
}

/** Splits a label into plain and highlighted parts: the stems found at the start of its words, or a code prefix. */
export function highlight(label: string, stems: string[]): Segment[] {
  if (!stems.length) {
    return [{ text: label, hit: false }];
  }
  const chars = [...label];
  const folded = foldChars(label);
  const hit = new Array<boolean>(chars.length).fill(false);
  for (const stem of stems) {
    let from = 0;
    for (;;) {
      const i = folded.indexOf(stem, from);
      if (i < 0) {
        break;
      }
      if (i === 0 || !/[\p{L}\p{N}]/u.test(folded[i - 1])) {
        hit.fill(true, i, i + stem.length);
      }
      from = i + 1;
    }
  }
  const out: Segment[] = [];
  chars.forEach((ch, i) => {
    const last = out[out.length - 1];
    if (last && last.hit === hit[i]) {
      last.text += ch;
    } else {
      out.push({ text: ch, hit: hit[i] });
    }
  });
  return out;
}

interface Crumb {
  code: string;
  label: string;
}

/**
 * CPV picker (official CPV 2008 vocabulary, labels in the UI language) with suggestions:
 * - typing a code prefix ("452") or words ("roboty drogowe", inflected forms work) searches;
 * - with an empty field the list can be browsed: divisions → groups → classes…, or the sub-codes of
 *   {@code suggest} (e.g. the company's own sectors when choosing exclusions);
 * - each suggestion shows its parent, its level and how many open tenders it currently has.
 * ARIA combobox: arrows move, Enter adds, → opens a code's sub-codes, ← goes back, Escape closes.
 */
@Component({
  selector: 'eti-cpv-picker',
  imports: [TranslocoPipe, LucideCheck, LucideChevronLeft, LucideChevronRight, LucideX],
  template: `
    <label class="eti-label" [for]="inputId">{{ label() }}</label>
    @if (selected().length) {
      <ul class="mt-1 flex flex-wrap gap-1">
        @for (code of selected(); track code) {
          <li class="inline-flex items-center gap-1 rounded-md bg-accent-soft px-2 py-0.5 text-xs text-accent">
            <span class="font-mono">{{ code }}</span>
            @if (labels()[code] ?? knownLabels()[code]; as l) {
              <span class="max-w-56 truncate" [title]="l">{{ l }}</span>
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
             [attr.aria-expanded]="open()" [attr.aria-controls]="listId" [attr.aria-describedby]="hintId"
             [attr.aria-activedescendant]="open() && active() >= 0 ? listId + '-' + active() : null"
             [placeholder]="'search.cpvPlaceholder' | transloco" [value]="query()"
             (input)="onInput($any($event.target).value)" (keydown)="onKey($event)"
             (focus)="openSuggestions()" (click)="openSuggestions()" (blur)="closeSoon()" />
      <p [id]="hintId" class="sr-only">{{ 'cpvPicker.srHint' | transloco }}</p>
      @if (open()) {
        <div class="absolute z-30 mt-1 flex max-h-[26rem] w-full min-w-72 flex-col overflow-hidden rounded-[var(--radius-control)] border border-border bg-surface shadow-xl"
             (mousedown)="$event.preventDefault()">
          @if (!searching()) {
            <div class="flex items-center gap-1.5 border-b border-border px-2 py-1.5 text-xs">
              @if (trail().length) {
                <button type="button" class="eti-icon-btn size-7 shrink-0" [attr.aria-label]="'cpvPicker.back' | transloco" (click)="up()">
                  <svg lucideChevronLeft class="size-4" aria-hidden="true"></svg>
                </button>
                <span class="min-w-0 flex-1 truncate font-medium text-ink">
                  <span class="font-mono text-ink-muted">{{ trail()[trail().length - 1].code }}</span> {{ trail()[trail().length - 1].label }}
                </span>
                @if (!selected().includes(trail()[trail().length - 1].code)) {
                  <button type="button" class="shrink-0 rounded px-2 py-1 font-medium text-accent hover:bg-accent-soft" (click)="pickCrumb()">
                    {{ 'cpvPicker.addWhole' | transloco }}
                  </button>
                }
              } @else {
                <span class="flex-1 px-1.5 py-1 font-semibold tracking-[0.06em] text-ink-faint uppercase">
                  {{ (suggest().length ? 'cpvPicker.yourSectors' : 'cpvPicker.divisions') | transloco }}
                </span>
              }
            </div>
            @if (!trail().length && !suggest().length) {
              <div class="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-2 text-[11px] text-ink-muted">
                {{ 'cpvPicker.try' | transloco }}
                @for (ex of examples(); track ex) {
                  <button type="button" class="rounded-full border border-border-strong px-2 py-0.5 text-ink-soft hover:border-accent hover:text-accent" (click)="onInput(ex)">{{ ex }}</button>
                }
              </div>
            }
          }
          <ul [id]="listId" role="listbox" class="flex-1 overflow-y-auto py-1 text-[13px]" [attr.aria-label]="label()">
            @for (o of options(); track o.code; let i = $index) {
              @let isSel = selected().includes(o.code);
              <li [id]="listId + '-' + i" role="option" [attr.aria-selected]="i === active()"
                  class="flex cursor-pointer items-start gap-2 px-3 py-2" [class.bg-accent-soft]="i === active()"
                  (mouseenter)="active.set(i)" (click)="pick(o)">
                <span class="mt-px w-[4.1rem] shrink-0 font-mono text-[11px] text-ink-muted sm:w-[4.6rem]">{{ o.code }}</span>
                <span class="min-w-0 flex-1">
                  <span class="block" [class.font-medium]="isSel" [class.text-accent]="isSel">
                    @for (s of segments(o.label); track $index) {<span [class.font-semibold]="s.hit" [class.text-ink]="s.hit">{{ s.text }}</span>}
                  </span>
                  <span class="mt-0.5 block truncate text-[11px] text-ink-faint">
                    @if (o.openTenders != null) {
                      <span class="sm:hidden" [class.text-success]="o.openTenders">{{ o.openTenders ? ('cpvPicker.open' | transloco: { n: o.openTenders }) : ('cpvPicker.noneOpen' | transloco) }} · </span>
                    }
                    {{ 'cpvPicker.levels.' + o.level | transloco }}@if (searching() && o.parentLabel) { · {{ o.parentLabel }}}
                  </span>
                </span>
                @if (o.openTenders != null) {
                  <span class="mt-px hidden shrink-0 text-[11px] whitespace-nowrap sm:inline" [class.text-ink-faint]="!o.openTenders" [class.text-success]="o.openTenders"
                        [title]="'cpvPicker.openTitle' | transloco">
                    {{ o.openTenders ? ('cpvPicker.open' | transloco: { n: o.openTenders }) : ('cpvPicker.noneOpen' | transloco) }}
                  </span>
                }
                @if (isSel) {
                  <svg lucideCheck class="mt-px size-4 shrink-0 text-accent" aria-hidden="true"></svg>
                }
                @if (o.hasChildren) {
                  <button type="button" class="eti-icon-btn -my-1 size-7 shrink-0" [attr.aria-label]="'cpvPicker.expand' | transloco: { code: o.code }"
                          (click)="$event.stopPropagation(); down(o)">
                    <svg lucideChevronRight class="size-4" aria-hidden="true"></svg>
                  </button>
                }
              </li>
            } @empty {
              <li class="px-3 py-2 text-ink-muted">{{ (loading() ? 'common.loading' : 'search.noCpv') | transloco }}</li>
            }
          </ul>
          <p class="hidden border-t border-border px-3 py-1.5 text-[11px] text-ink-faint sm:block">{{ 'cpvPicker.keys' | transloco }}</p>
        </div>
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
  /** Codes whose sub-codes are suggested when the field is empty (instead of all divisions). */
  readonly suggest = input<string[]>([]);
  /** Known labels of selected codes (filled when picked). */
  protected readonly labels = signal<Record<string, string>>({});
  protected readonly query = signal('');
  protected readonly options = signal<CpvOption[]>([]);
  protected readonly open = signal(false);
  protected readonly loading = signal(false);
  protected readonly active = signal(-1);
  /** Path of codes opened while browsing (empty = top level). */
  protected readonly trail = signal<Crumb[]>([]);
  protected readonly searching = computed(() => this.query().trim().length >= 2);
  protected readonly inputId = `cpv-${nextId++}`;
  protected readonly listId = `${this.inputId}-list`;
  protected readonly hintId = `${this.inputId}-hint`;
  private readonly stems = computed(() => cpvStems(this.query()));
  private readonly queries = new Subject<string>();
  private readonly browse = new Subject<string | null>();

  protected readonly examples = computed(() => (this.lang() === 'pl'
    ? ['roboty drogowe', 'sprzątanie', 'sprzęt komputerowy', '4523']
    : ['road works', 'cleaning', 'computer equipment', '4523']));

  constructor() {
    const destroy = inject(DestroyRef);
    this.queries
      .pipe(
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((q) => (q.trim().length < 2 ? of(null) : this.api.cpv(q.trim(), this.lang()).pipe(catchError(() => of([]))))),
        takeUntilDestroyed(destroy),
      )
      .subscribe((list) => {
        if (list === null) {
          return;
        }
        this.show(list);
      });
    this.browse
      .pipe(
        switchMap((parent) => this.level(parent).pipe(catchError(() => of([])))),
        takeUntilDestroyed(destroy),
      )
      .subscribe((list) => this.show(list));
  }

  /** Sub-codes of {@code parent}; at the top: the sub-codes of the suggested codes, or the divisions. */
  private level(parent: string | null): Observable<CpvOption[]> {
    const lang = this.lang();
    if (parent) {
      return this.api.cpvChildren(parent, lang);
    }
    const roots = this.suggest();
    return roots.length
      ? combineLatest(roots.slice(0, 10).map((c) => this.api.cpvChildren(c, lang))).pipe(map((lists) => lists.flat()))
      : this.api.cpvChildren(null, lang);
  }

  private show(list: CpvOption[]): void {
    this.loading.set(false);
    this.options.set(list);
    this.active.set(list.length ? 0 : -1);
  }

  protected segments(label: string) {
    return highlight(label, this.stems());
  }

  protected openSuggestions(): void {
    if (this.open()) {
      return;
    }
    this.open.set(true);
    if (!this.searching()) {
      this.load(this.trail()[this.trail().length - 1]?.code ?? null);
    }
  }

  private load(parent: string | null): void {
    this.loading.set(true);
    this.options.set([]);
    this.browse.next(parent);
  }

  protected onInput(value: string): void {
    this.query.set(value);
    this.open.set(true);
    if (value.trim().length >= 2) {
      this.loading.set(true);
      this.queries.next(value);
    } else {
      this.queries.next('');
      this.load(this.trail()[this.trail().length - 1]?.code ?? null);
    }
    document.getElementById(this.inputId)?.focus();
  }

  protected down(o: CpvOption): void {
    this.trail.update((t) => [...t, { code: o.code, label: o.label }]);
    if (this.searching()) {
      this.query.set('');
      this.queries.next('');
    }
    this.load(o.code);
  }

  protected up(): void {
    const t = this.trail().slice(0, -1);
    this.trail.set(t);
    this.load(t[t.length - 1]?.code ?? null);
  }

  protected onKey(event: KeyboardEvent): void {
    const n = this.options().length;
    const current = this.options()[this.active()];
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      if (!this.open()) {
        this.openSuggestions();
      } else if (n) {
        this.active.update((i) => (i + 1) % n);
        this.scrollActive();
      }
    } else if (event.key === 'ArrowUp' && n) {
      event.preventDefault();
      this.active.update((i) => (i - 1 + n) % n);
      this.scrollActive();
    } else if (event.key === 'Enter' && this.open() && current) {
      event.preventDefault();
      this.pick(current);
    } else if (event.key === 'ArrowRight' && this.open() && current?.hasChildren && this.isCaretAtEnd(event)) {
      event.preventDefault();
      this.down(current);
    } else if ((event.key === 'ArrowLeft' || event.key === 'Backspace') && !this.query() && this.trail().length) {
      event.preventDefault();
      this.up();
    } else if (event.key === 'Escape' && this.open()) {
      event.preventDefault();
      event.stopPropagation();
      this.open.set(false);
    }
  }

  private isCaretAtEnd(event: KeyboardEvent): boolean {
    const el = event.target as HTMLInputElement;
    return el.selectionStart === el.value.length;
  }

  private scrollActive(): void {
    setTimeout(() => document.getElementById(`${this.listId}-${this.active()}`)?.scrollIntoView({ block: 'nearest' }));
  }

  protected pick(o: CpvOption): void {
    this.add(o.code, o.label);
    this.query.set('');
    this.queries.next('');
    this.open.set(false);
    this.options.set([]);
  }

  protected pickCrumb(): void {
    const c = this.trail()[this.trail().length - 1];
    if (c) {
      this.add(c.code, c.label);
      this.open.set(false);
    }
  }

  private add(code: string, label: string): void {
    if (!this.selected().includes(code)) {
      this.selected.update((list) => [...list, code]);
    }
    this.labels.update((l) => ({ ...l, [code]: label }));
  }

  protected remove(code: string): void {
    this.selected.update((list) => list.filter((c) => c !== code));
  }

  protected closeSoon(): void {
    setTimeout(() => this.open.set(false), 150);
  }
}
