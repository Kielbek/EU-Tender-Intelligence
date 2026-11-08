import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideLandmark, LucideMailX } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { NotificationService } from '../../core/api/notification.service';
import { LanguageSwitcherComponent } from '../../core/layout/language-switcher.component';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

/**
 * Target of the unsubscribe link in notification e-mails; works without signing in. The click on the button (POST)
 * unsubscribes – not opening the page, so link scanners in mail systems cannot unsubscribe anyone by accident.
 */
@Component({
  selector: 'eti-unsubscribe-page',
  imports: [RouterLink, TranslocoPipe, LanguageSwitcherComponent, ApiErrorAlertComponent, LucideLandmark, LucideMailX],
  template: `
    <div class="flex min-h-dvh flex-col bg-surface">
      <header class="flex h-16 items-center justify-between border-b border-border px-4 sm:px-8">
        <a routerLink="/" class="flex items-center gap-2.5">
          <svg lucideLandmark class="size-[22px] text-accent" aria-hidden="true"></svg>
          <span class="text-[15px] font-bold tracking-[-0.01em] text-ink">Tender Intelligence</span>
        </a>
        <eti-language-switcher />
      </header>
      <main id="main" class="mx-auto w-full max-w-lg flex-1 px-4 py-16 sm:px-8">
        <svg lucideMailX class="size-9 text-ink-muted" aria-hidden="true"></svg>
        <h1 class="eti-h1 mt-4">{{ 'notifications.unsubscribe.title' | transloco }}</h1>
        <div class="mt-4 space-y-4" aria-live="polite">
          @if (!token) {
            <p class="eti-alert-error" role="alert">{{ 'notifications.unsubscribe.missing' | transloco }}</p>
          } @else if (done()) {
            <p class="eti-alert-success" role="status">
              @if (orgName()) {
                {{ 'notifications.unsubscribe.done' | transloco: { org: orgName() } }}
              } @else {
                {{ 'notifications.unsubscribe.doneNoOrg' | transloco }}
              }
            </p>
            <p class="text-sm text-ink-muted">
              {{ 'notifications.unsubscribe.settings' | transloco }}
              <a routerLink="/notifications" [queryParams]="{ tab: 'settings' }" class="eti-link">{{ 'notifications.tabs.settings' | transloco }}</a>
            </p>
          } @else {
            <p class="text-ink-soft">{{ 'notifications.unsubscribe.intro' | transloco }}</p>
            <eti-api-error [error]="error()" />
            <button type="button" class="eti-btn-primary" [disabled]="busy()" (click)="confirm()">
              {{ 'notifications.unsubscribe.confirm' | transloco }}
            </button>
          }
        </div>
      </main>
    </div>
  `,
})
export class UnsubscribePage {
  private readonly api = inject(NotificationService);
  protected readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');
  protected readonly done = signal(false);
  protected readonly busy = signal(false);
  protected readonly orgName = signal<string | null>(null);
  protected readonly error = signal<ApiError | null>(null);

  protected confirm(): void {
    if (!this.token) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.api.unsubscribe(this.token).subscribe({
      next: (r) => {
        this.orgName.set(r.organizationName ?? null);
        this.done.set(true);
        this.busy.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.busy.set(false);
      },
    });
  }
}
