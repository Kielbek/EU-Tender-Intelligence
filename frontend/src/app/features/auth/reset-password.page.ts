import { Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { PASSWORD_MIN_LENGTH } from '../../core/auth/auth.model';
import { AuthService } from '../../core/auth/auth.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

@Component({
  selector: 'eti-reset-password-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoPipe, ApiErrorAlertComponent],
  template: `
    <h1 class="text-xl font-semibold tracking-tight">{{ 'auth.reset.title' | transloco }}</h1>
    @if (done()) {
      <p role="status" class="eti-alert-success mt-4">{{ 'auth.reset.done' | transloco }}</p>
      <a routerLink="/auth/login" class="eti-btn-primary mt-6 w-full">{{ 'auth.reset.login' | transloco }}</a>
    } @else if (!token()) {
      <p role="alert" class="eti-alert-error mt-4">{{ 'auth.verify.missing' | transloco }}</p>
    } @else {
      <form class="mt-6 space-y-4" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <eti-api-error [error]="error()" />
        <div>
          <label class="eti-label" for="password">{{ 'auth.reset.newPassword' | transloco }}</label>
          <input id="password" type="password" autocomplete="new-password" class="eti-input" formControlName="password"
                 [attr.aria-invalid]="form.controls.password.invalid && form.controls.password.touched"
                 aria-describedby="password-hint" />
          <p id="password-hint" class="eti-hint">{{ 'common.passwordHint' | transloco }}</p>
        </div>
        <button type="submit" class="eti-btn-primary w-full" [disabled]="busy()">
          {{ 'auth.reset.submit' | transloco }}
        </button>
      </form>
    }
  `,
})
export class ResetPasswordPage {
  private readonly auth = inject(AuthService);
  readonly token = input<string>();
  protected readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH), Validators.maxLength(128)]],
  });
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected submit(): void {
    this.form.markAllAsTouched();
    const token = this.token();
    if (this.form.invalid || this.busy() || !token) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.auth.resetPassword(token, this.form.controls.password.value).subscribe({
      next: () => {
        this.auth.clear();
        this.done.set(true);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.busy.set(false);
      },
    });
  }
}
