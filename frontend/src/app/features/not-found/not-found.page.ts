import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

@Component({
  selector: 'eti-not-found-page',
  imports: [RouterLink, TranslocoPipe],
  template: `
    <div class="mx-auto max-w-md py-16 text-center">
      <p class="font-mono text-sm text-ink-muted">404</p>
      <h1 class="eti-h1 mt-2">{{ 'notFound.title' | transloco }}</h1>
      <p class="mt-2 text-sm text-ink-muted">{{ 'notFound.body' | transloco }}</p>
      <a routerLink="/status" class="mt-6 inline-flex h-9 items-center rounded-md bg-accent px-4 text-sm font-semibold text-white hover:bg-accent-hover">
        {{ 'notFound.back' | transloco }}
      </a>
    </div>
  `,
})
export class NotFoundPage {}
