import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideLandmark } from '@lucide/angular';
import { catchError, of } from 'rxjs';
import { LanguageService } from '../../core/i18n/language.service';
import { LanguageSwitcherComponent } from '../../core/layout/language-switcher.component';
import { LEGAL_TEXTS, LegalDoc } from './legal-texts';

export interface LegalInfo {
  configured: boolean;
  operatorName?: string;
  operatorAddress?: string;
  operatorTaxId?: string;
  operatorEmail?: string;
  termsVersion: string;
  processors: { code: 'HOSTING' | 'EMAIL' | 'AI'; name?: string }[];
}

/**
 * Terms of service and privacy policy, public. Operator details come from the server configuration; when they are
 * missing the page says so plainly instead of showing invented company data.
 */
@Component({
  selector: 'eti-legal-page',
  imports: [RouterLink, TranslocoPipe, LanguageSwitcherComponent, LucideLandmark],
  template: `
    <div class="flex min-h-dvh flex-col bg-surface">
      <header class="flex h-16 items-center justify-between border-b border-border px-4 sm:px-8">
        <a routerLink="/" class="flex items-center gap-2.5">
          <svg lucideLandmark class="size-[22px] text-accent" aria-hidden="true"></svg>
          <span class="text-[15px] font-bold tracking-[-0.01em] text-ink">Tender Intelligence</span>
        </a>
        <eti-language-switcher />
      </header>
      <main id="main" class="mx-auto w-full max-w-3xl flex-1 px-4 py-10 sm:px-8">
        <nav class="flex gap-4 text-[13px]" [attr.aria-label]="'legal.nav' | transloco">
          <a routerLink="/legal/terms" class="eti-link" [class.!text-ink]="doc() === 'terms'"
             [attr.aria-current]="doc() === 'terms' ? 'page' : null">{{ 'legal.terms' | transloco }}</a>
          <a routerLink="/legal/privacy" class="eti-link" [class.!text-ink]="doc() === 'privacy'"
             [attr.aria-current]="doc() === 'privacy' ? 'page' : null">{{ 'legal.privacy' | transloco }}</a>
        </nav>
        <h1 class="eti-h1 mt-4">{{ text().title }}</h1>
        @if (info(); as i) {
          <p class="eti-subtitle mt-1">{{ 'legal.version' | transloco: { version: i.termsVersion } }}</p>
          @if (!i.configured) {
            <p class="mt-4 rounded-[var(--radius-control)] border border-warning/30 bg-warning-soft px-3 py-2 text-sm text-warning" role="note">
              {{ 'legal.notConfigured' | transloco }}
            </p>
          }
        }
        <p class="eti-note mt-4">{{ 'legal.draft' | transloco }}</p>

        <dl class="eti-card mt-6 grid gap-x-6 gap-y-2 p-5 text-sm sm:grid-cols-[12rem_1fr]">
          <dt class="text-ink-muted">{{ 'legal.operator' | transloco }}</dt>
          <dd class="text-ink">{{ info()?.operatorName ?? ('legal.missing' | transloco) }}</dd>
          <dt class="text-ink-muted">{{ 'legal.address' | transloco }}</dt>
          <dd class="text-ink">{{ info()?.operatorAddress ?? ('legal.missing' | transloco) }}</dd>
          <dt class="text-ink-muted">{{ 'legal.taxId' | transloco }}</dt>
          <dd class="text-ink">{{ info()?.operatorTaxId ?? ('legal.missing' | transloco) }}</dd>
          <dt class="text-ink-muted">{{ 'legal.contact' | transloco }}</dt>
          <dd class="text-ink">
            @if (info()?.operatorEmail; as e) {
              <a class="eti-link" [href]="'mailto:' + e">{{ e }}</a>
            } @else {
              {{ 'legal.missing' | transloco }}
            }
          </dd>
        </dl>

        @for (s of text().sections; track s.heading; let i = $index) {
          <section class="mt-8">
            <h2 class="eti-h2">{{ i + 1 }}. {{ s.heading }}</h2>
            @for (p of s.paragraphs; track $index) {
              <p class="mt-2 text-[15px] leading-relaxed text-ink-soft">{{ p }}</p>
            }
            @if (s.processors && info(); as inf) {
              <ul class="mt-3 list-disc space-y-1 pl-5 text-[15px] text-ink-soft">
                @for (p of inf.processors; track p.code) {
                  <li>{{ 'legal.processor.' + p.code | transloco }}: {{ p.name ?? ('legal.missing' | transloco) }}</li>
                }
              </ul>
            }
          </section>
        }
      </main>
    </div>
  `,
})
export class LegalPage {
  private readonly language = inject(LanguageService);
  /** From the route data: 'terms' or 'privacy'. */
  readonly doc = input<'terms' | 'privacy'>('terms');
  protected readonly info = toSignal(
    inject(HttpClient).get<LegalInfo>('/api/v1/system/legal').pipe(catchError(() => of(null))),
    { initialValue: null },
  );
  protected readonly text = computed<LegalDoc>(() => {
    const lang = this.language.current() === 'pl' ? 'pl' : 'en';
    return LEGAL_TEXTS[lang][this.doc()];
  });
}
