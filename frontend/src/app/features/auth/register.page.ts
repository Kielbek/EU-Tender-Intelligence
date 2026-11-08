import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { PASSWORD_MIN_LENGTH } from '../../core/auth/auth.model';
import { AuthService } from '../../core/auth/auth.service';
import { LanguageService } from '../../core/i18n/language.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

@Component({
  selector: 'eti-register-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoPipe, ApiErrorAlertComponent],
  template: `
    @if (doneFor(); as email) {
      <div role="status">
        <h1 class="eti-h1">{{ 'auth.register.doneTitle' | transloco }}</h1>
        <p class="mt-3 text-sm text-ink-muted">{{ 'auth.register.doneBody' | transloco: { email } }}</p>
        <a routerLink="/auth/login" class="eti-btn-secondary mt-6 w-full">{{ 'auth.register.login' | transloco }}</a>
      </div>
    } @else {
      <h1 class="eti-h1">{{ 'auth.register.title' | transloco }}</h1>
      <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <eti-api-error [error]="error()" />
        <div>
          <label class="eti-label" for="fullName">{{ 'auth.register.fullName' | transloco }}</label>
          <input id="fullName" autocomplete="name" class="eti-input" formControlName="fullName"
                 [attr.aria-invalid]="invalid('fullName')" aria-describedby="fullName-error" />
          @if (invalid('fullName')) {
            <p id="fullName-error" class="eti-field-error">{{ 'common.required' | transloco }}</p>
          }
        </div>
        <div>
          <label class="eti-label" for="organizationName">{{ 'auth.register.organizationName' | transloco }}</label>
          <input id="organizationName" autocomplete="organization" class="eti-input" formControlName="organizationName"
                 aria-describedby="organizationName-hint" />
          <p id="organizationName-hint" class="eti-hint">{{ 'auth.register.organizationHint' | transloco }}</p>
        </div>
        <div>
          <label class="eti-label" for="email">{{ 'common.email' | transloco }}</label>
          <input id="email" type="email" autocomplete="email" class="eti-input" formControlName="email"
                 [attr.aria-invalid]="invalid('email')" aria-describedby="email-error" />
          @if (invalid('email')) {
            <p id="email-error" class="eti-field-error">{{ 'common.invalidEmail' | transloco }}</p>
          }
        </div>
        <div>
          <label class="eti-label" for="password">{{ 'common.password' | transloco }}</label>
          <input id="password" type="password" autocomplete="new-password" class="eti-input" formControlName="password"
                 [attr.aria-invalid]="invalid('password')" aria-describedby="password-hint" />
          <p id="password-hint" [class]="invalid('password') ? 'eti-field-error' : 'eti-hint'">
            {{ (invalid('password') ? 'common.passwordMin' : 'common.passwordHint') | transloco }}
          </p>
        </div>
        <div>
          <label class="flex items-start gap-2 text-sm">
            <input type="checkbox" class="mt-0.5 size-4 accent-accent" formControlName="acceptTerms"
                   [attr.aria-invalid]="invalid('acceptTerms')" aria-describedby="terms-error" />
            <span>
              {{ 'auth.register.termsPrefix' | transloco }}
              <a routerLink="/legal/terms" target="_blank" class="eti-link">{{ 'auth.register.termsLink' | transloco }}</a>
              {{ 'auth.register.termsAnd' | transloco }}
              <a routerLink="/legal/privacy" target="_blank" class="eti-link">{{ 'auth.register.privacyLink' | transloco }}</a>
            </span>
          </label>
          @if (invalid('acceptTerms')) {
            <p id="terms-error" class="eti-field-error">{{ 'auth.register.termsRequired' | transloco }}</p>
          }
        </div>
        <button type="submit" class="eti-btn-primary w-full" [disabled]="busy()">
          {{ (busy() ? 'auth.register.submitting' : 'auth.register.submit') | transloco }}
        </button>
      </form>
      <p class="mt-6 text-center text-sm text-ink-muted">
        {{ 'auth.register.haveAccount' | transloco }}
        <a routerLink="/auth/login" class="eti-link">{{ 'auth.register.login' | transloco }}</a>
      </p>
    }
  `,
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly language = inject(LanguageService);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    organizationName: ['', Validators.maxLength(200)],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH), Validators.maxLength(128)]],
    acceptTerms: [false, Validators.requiredTrue],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly doneFor = signal<string | null>(null);

  protected invalid(name: keyof typeof this.form.controls): boolean {
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
    const v = this.form.getRawValue();
    this.auth
      .register({
        email: v.email.trim(),
        password: v.password,
        fullName: v.fullName.trim(),
        organizationName: v.organizationName.trim() || undefined,
        locale: this.language.current(),
        acceptTerms: v.acceptTerms,
      })
      .subscribe({
        next: () => this.doneFor.set(v.email.trim()),
        error: (err: unknown) => {
          this.error.set(toApiError(err));
          this.busy.set(false);
        },
      });
  }
}
