import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AuthService } from '../../core/auth/auth.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

@Component({
  selector: 'eti-forgot-password-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoPipe, ApiErrorAlertComponent],
  template: `
    <h1 class="eti-h1">{{ 'auth.forgot.title' | transloco }}</h1>
    @if (done()) {
      <p role="status" class="eti-alert-success mt-4">{{ 'auth.forgot.done' | transloco }}</p>
    } @else {
      <p class="mt-2 text-sm text-ink-muted">{{ 'auth.forgot.body' | transloco }}</p>
      <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <eti-api-error [error]="error()" />
        <div>
          <label class="eti-label" for="email">{{ 'common.email' | transloco }}</label>
          <input id="email" type="email" autocomplete="email" class="eti-input" formControlName="email"
                 [attr.aria-invalid]="form.controls.email.invalid && form.controls.email.touched"
                 aria-describedby="email-error" />
          @if (form.controls.email.invalid && form.controls.email.touched) {
            <p id="email-error" class="eti-field-error">{{ 'common.invalidEmail' | transloco }}</p>
          }
        </div>
        <button type="submit" class="eti-btn-primary w-full" [disabled]="busy()">
          {{ 'auth.forgot.submit' | transloco }}
        </button>
      </form>
    }
    <p class="mt-6 text-center text-sm">
      <a routerLink="/auth/login" class="eti-link">{{ 'auth.forgot.back' | transloco }}</a>
    </p>
  `,
})
export class ForgotPasswordPage {
  private readonly auth = inject(AuthService);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.auth.forgotPassword(this.form.controls.email.value.trim()).subscribe({
      next: () => this.done.set(true),
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.busy.set(false);
      },
    });
  }
}
