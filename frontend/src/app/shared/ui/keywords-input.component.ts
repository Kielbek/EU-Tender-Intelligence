import { Component, input, model, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideX } from '@lucide/angular';

/** A list of words as removable chips; Enter or comma adds the typed word. */
@Component({
  selector: 'eti-keywords-input',
  imports: [TranslocoPipe, LucideX],
  template: `
    <div class="mt-1.5 flex min-h-10 flex-wrap items-center gap-1.5 rounded-[var(--radius-control)] border border-border-strong bg-surface px-2 py-1.5 focus-within:border-accent focus-within:ring-[3px] focus-within:ring-accent/10">
      @for (w of words(); track w) {
        <span class="inline-flex items-center gap-1 rounded bg-canvas px-2 py-1 text-xs text-ink" [class.bg-danger-soft]="tone() === 'danger'" [class.text-danger]="tone() === 'danger'">
          {{ w }}
          <button type="button" class="rounded hover:text-accent" [attr.aria-label]="('common.remove' | transloco) + ': ' + w" (click)="remove(w)">
            <svg lucideX class="size-3" aria-hidden="true"></svg>
          </button>
        </span>
      }
      <input [id]="inputId()" type="text" class="min-w-32 flex-1 bg-transparent px-1 py-1 text-[13px] text-ink placeholder:text-ink-faint focus:outline-none"
             [placeholder]="placeholder()" [value]="draft()" maxlength="60" [disabled]="words().length >= max()"
             (input)="draft.set($any($event.target).value)" (keydown)="onKey($event)" (blur)="add()" />
    </div>
  `,
})
export class KeywordsInputComponent {
  readonly inputId = input.required<string>();
  readonly placeholder = input('');
  readonly tone = input<'neutral' | 'danger'>('neutral');
  readonly max = input(30);
  readonly words = model<string[]>([]);
  protected readonly draft = signal('');

  protected onKey(e: KeyboardEvent): void {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      this.add();
    } else if (e.key === 'Backspace' && !this.draft() && this.words().length) {
      this.words.update((w) => w.slice(0, -1));
    }
  }

  protected add(): void {
    const w = this.draft().trim().replace(/,$/, '').trim();
    if (w && !this.words().some((x) => x.toLowerCase() === w.toLowerCase()) && this.words().length < this.max()) {
      this.words.update((list) => [...list, w]);
    }
    this.draft.set('');
  }

  protected remove(w: string): void {
    this.words.update((list) => list.filter((x) => x !== w));
  }
}
