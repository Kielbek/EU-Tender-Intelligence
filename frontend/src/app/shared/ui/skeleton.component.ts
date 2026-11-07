import { Component, input } from '@angular/core';

/** Decorative loading placeholder; the surrounding container announces loading state to assistive tech. */
@Component({
  selector: 'eti-skeleton',
  host: { 'aria-hidden': 'true', class: 'block' },
  template: `<div class="animate-pulse rounded-md bg-border/70" [class]="size()"></div>`,
})
export class SkeletonComponent {
  readonly size = input('h-4 w-full');
}
