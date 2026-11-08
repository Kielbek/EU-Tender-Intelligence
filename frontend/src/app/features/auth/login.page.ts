import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { LanguageService } from '../../core/i18n/language.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

@Component({
  selector: 'eti-login-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoPipe, ApiErrorAlertComponent],
  template: `
    <h1 class="eti-h1">{{ 'auth.login.title' | transloco }}</h1>
    <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <eti-api-error [error]="error()" />
      @if (notice(); as key) {
        <p role="status" class="eti-alert-success">{{ key | transloco }}</p>
      }
      @if (resent()) {
        <p role="status" class="eti-alert-success">{{ 'auth.login.resent' | transloco }}</p>
      }
      <div>
        <label class="eti-label" for="email">{{ 'common.email' | transloco }}</label>
        <input id="email" type="email" autocomplete="email" class="eti-input" formControlName="email"
               [attr.aria-invalid]="invalid('email')" aria-describedby="email-error" />
        @if (invalid('email')) {
          <p id="email-error" class="eti-field-error">{{ 'common.invalidEmail' | transloco }}</p>
        }
      </div>
      <div>
        <div class="flex items-center justify-between">
          <label class="eti-label" for="password">{{ 'common.password' | transloco }}</label>
          <a routerLink="/auth/forgot-password" class="eti-link text-xs">{{ 'auth.login.forgot' | transloco }}</a>
        </div>
        <input id="password" type="password" autocomplete="current-password" class="eti-input"
               formControlName="password" [attr.aria-invalid]="invalid('password')" aria-describedby="password-error" />
        @if (invalid('password')) {
          <p id="password-error" class="eti-field-error">{{ 'common.required' | transloco }}</p>
        }
      </div>
      <button type="submit" class="eti-btn-primary w-full" [disabled]="busy()">
        {{ (busy() ? 'auth.login.submitting' : 'auth.login.submit') | transloco }}
      </button>
      @if (error()?.code === 'email_not_verified') {
        <button type="button" class="eti-btn-secondary w-full" (click)="resend()">
          {{ 'auth.login.resend' | transloco }}
        </button>
      }
    </form>
    <p class="mt-6 text-center text-sm text-ink-muted">
      {{ 'auth.login.noAccount' | transloco }}
      <a routerLink="/auth/register" class="eti-link">{{ 'auth.login.register' | transloco }}</a>
    </p>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly language = inject(LanguageService);
  readonly returnUrl = input<string>();

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly resent = signal(false);
  /** Message passed via navigation state, e.g. after a password change. */
  protected readonly notice = signal<string | null>(
    typeof history !== 'undefined' && typeof history.state?.messageKey === 'string' ? history.state.messageKey : null,
  );

  protected invalid(name: 'email' | 'password'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || c.dirty);
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.resent.set(false);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: (user) => {
        this.language.adoptUserLocale(user.locale);
        void this.router.navigateByUrl(safeReturnUrl(this.returnUrl()));
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.busy.set(false);
      },
    });
  }

  protected resend(): void {
    this.auth.resendVerification(this.form.controls.email.value).subscribe({
      next: () => this.resent.set(true),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }
}

/** Only same-app relative paths are allowed as redirect targets (prevents open redirects). */
export function safeReturnUrl(url: string | undefined): string {
  return url && url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/auth/') ? url : '/';
}
