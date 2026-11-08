import { Component, computed, input, model, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

export interface CheckOption {
  value: string;
  label: string;
}

let nextId = 0;

/** Multi-select as a disclosure with a filterable checkbox list (native controls – keyboard and screen-reader friendly). */
@Component({
  selector: 'eti-multi-check',
  imports: [TranslocoPipe],
  template: `
    <details class="group" [attr.open]="startOpen() ? '' : null">
      <summary class="eti-input flex cursor-pointer list-none items-center justify-between">
        <span class="truncate">
          <span class="text-ink-muted">{{ label() }}:</span>
          {{ summary() }}
        </span>
        <span aria-hidden="true" class="text-ink-muted group-open:rotate-180">▾</span>
      </summary>
      <div class="mt-1 rounded-md border border-border bg-surface p-2">
        @if (options().length > 8) {
          <label class="sr-only" [for]="filterId">{{ 'search.filterList' | transloco }}</label>
          <input [id]="filterId" class="eti-input mt-0 h-8" type="search"
                 [placeholder]="'search.filterList' | transloco" (input)="filter.set($any($event.target).value)" />
        }
        <fieldset class="mt-1 max-h-56 overflow-auto">
          <legend class="sr-only">{{ label() }}</legend>
          @for (o of visible(); track o.value) {
            <label class="flex items-center gap-2 rounded px-1 py-1 text-sm hover:bg-canvas">
              <input type="checkbox" class="size-4 accent-navy-900" [checked]="selected().includes(o.value)"
                     (change)="toggle(o.value, $any($event.target).checked)" />
              {{ o.label }}
            </label>
          }
        </fieldset>
        @if (selected().length) {
          <button type="button" class="eti-link mt-1 text-xs" (click)="selected.set([])">
            {{ 'search.clearSelection' | transloco }}
          </button>
        }
      </div>
    </details>
  `,
})
export class MultiCheckComponent {
  readonly label = input.required<string>();
  readonly options = input.required<CheckOption[]>();
  readonly allLabel = input.required<string>();
  readonly startOpen = input(false);
  readonly selected = model<string[]>([]);
  protected readonly filter = signal('');
  protected readonly filterId = `mc-${nextId++}`;

  protected readonly visible = computed(() => {
    const f = this.filter().trim().toLocaleLowerCase();
    return f ? this.options().filter((o) => o.label.toLocaleLowerCase().includes(f)) : this.options();
  });

  protected readonly summary = computed(() => {
    const sel = this.selected();
    if (!sel.length) {
      return this.allLabel();
    }
    const labels = this.options().filter((o) => sel.includes(o.value)).map((o) => o.label);
    return labels.length <= 2 ? labels.join(', ') : `${labels.slice(0, 2).join(', ')} +${labels.length - 2}`;
  });

  protected toggle(value: string, checked: boolean): void {
    this.selected.update((list) => (checked ? [...list, value] : list.filter((v) => v !== value)));
  }
}
