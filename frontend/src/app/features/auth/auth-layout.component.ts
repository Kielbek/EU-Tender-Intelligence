import { Component } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideLandmark } from '@lucide/angular';
import { LanguageSwitcherComponent } from '../../core/layout/language-switcher.component';

@Component({
  selector: 'eti-auth-layout',
  imports: [RouterOutlet, RouterLink, TranslocoPipe, LanguageSwitcherComponent, LucideLandmark],
  template: `
    <div class="flex min-h-dvh flex-col bg-surface">
      <header class="flex h-16 items-center justify-between border-b border-border px-4 sm:px-8">
        <a href="/" class="flex items-center gap-2.5">
          <svg lucideLandmark class="size-[22px] text-accent" aria-hidden="true"></svg>
          <span class="text-[15px] font-bold tracking-[-0.01em] text-ink">Tender Intelligence</span>
        </a>
        <eti-language-switcher />
      </header>
      <main id="main" class="flex flex-1 items-start justify-center px-4 pt-10 pb-16 sm:pt-20">
        <div class="w-full max-w-[420px]">
          <div class="eti-card px-6 py-7 sm:px-8 sm:py-8">
            <router-outlet />
          </div>
          <p class="mt-6 text-center text-[13px] text-ink-muted">{{ 'auth.tagline' | transloco }}</p>
          <p class="mt-2 text-center text-[13px]">
            <a routerLink="/legal/terms" class="eti-link">{{ 'legal.terms' | transloco }}</a>
            <span class="text-ink-faint"> · </span>
            <a routerLink="/legal/privacy" class="eti-link">{{ 'legal.privacy' | transloco }}</a>
          </p>
        </div>
      </main>
    </div>
  `,
})
export class AuthLayoutComponent {}
