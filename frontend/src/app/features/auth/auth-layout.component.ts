import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideLandmark } from '@lucide/angular';
import { LanguageSwitcherComponent } from '../../core/layout/language-switcher.component';

@Component({
  selector: 'eti-auth-layout',
  imports: [RouterOutlet, TranslocoPipe, LanguageSwitcherComponent, LucideLandmark],
  template: `
    <div class="flex min-h-dvh flex-col">
      <header class="flex h-14 items-center justify-between px-4 sm:px-6">
        <div class="flex items-center gap-2">
          <svg lucideLandmark class="size-5 text-accent" aria-hidden="true"></svg>
          <span class="text-sm font-semibold tracking-tight">{{ 'app.name' | transloco }}</span>
        </div>
        <eti-language-switcher />
      </header>
      <main id="main" class="flex flex-1 items-start justify-center px-4 pb-16 pt-8 sm:pt-16">
        <div class="w-full max-w-md">
          <p class="mb-6 text-center text-sm text-ink-muted">{{ 'auth.tagline' | transloco }}</p>
          <div class="eti-card p-6 sm:p-8">
            <router-outlet />
          </div>
        </div>
      </main>
    </div>
  `,
})
export class AuthLayoutComponent {}
