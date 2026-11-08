import { Component, computed, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideStar } from '@lucide/angular';
import { WatchlistStore } from '../../core/api/watchlist.store';

/** Star toggle adding/removing a tender from the organization's watch-list (aria-pressed button). */
@Component({
  selector: 'eti-watch-toggle',
  imports: [TranslocoPipe, LucideStar],
  template: `
    <button type="button" class="inline-flex size-8 items-center justify-center rounded-md hover:bg-canvas disabled:opacity-50"
            [attr.aria-pressed]="watched()" [disabled]="busy()"
            [attr.aria-label]="(watched() ? 'watch.untoggleAria' : 'watch.toggleAria') | transloco: { title: title() }"
            [title]="(watched() ? 'watch.remove' : 'watch.add') | transloco"
            (click)="toggle()">
      <svg lucideStar class="size-4" [class.fill-current]="watched()" [class.text-accent]="watched()"
           [class.text-ink-muted]="!watched()" aria-hidden="true"></svg>
    </button>
  `,
})
export class WatchToggleComponent {
  private readonly store = inject(WatchlistStore);
  readonly tenderId = input.required<string>();
  readonly title = input('');
  protected readonly busy = signal(false);
  protected readonly watched = computed(() => this.store.ids().has(this.tenderId()));

  protected toggle(): void {
    this.busy.set(true);
    const op: import('rxjs').Observable<unknown> = this.watched() ? this.store.unwatch(this.tenderId()) : this.store.watch(this.tenderId());
    op.subscribe({ next: () => this.busy.set(false), error: () => this.busy.set(false) });
  }
}
