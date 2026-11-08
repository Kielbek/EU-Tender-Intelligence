import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AccountService } from '../../core/api/account.service';
import { ApiError, toApiError } from '../../core/api/api-error';
import { PASSWORD_MIN_LENGTH } from '../../core/auth/auth.model';
import { AuthService } from '../../core/auth/auth.service';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

function timeZones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return ['Europe/Warsaw'];
  }
}

@Component({
  selector: 'eti-account-page',
  imports: [ReactiveFormsModule, TranslocoPipe, ApiErrorAlertComponent, SelectComponent],
  templateUrl: './account.page.html',
})
export class AccountPage {
  private readonly accountApi = inject(AccountService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly zones = timeZones();
  protected readonly zoneOptions: SelectOption[] = this.zones.map((z) => ({ value: z, label: z.replace(/_/g, " ") }));
  protected readonly profile = this.fb.group({
    fullName: [this.auth.user()?.fullName ?? '', [Validators.required, Validators.minLength(2), Validators.maxLength(120)]],
    timezone: [this.auth.user()?.timezone ?? 'Europe/Warsaw', Validators.required],
  });
  protected readonly password = this.fb.group({
    currentPassword: ['', Validators.required],
    newPassword: ['', [Validators.required, Validators.minLength(PASSWORD_MIN_LENGTH), Validators.maxLength(128)]],
  });

  protected readonly profileBusy = signal(false);
  protected readonly profileSaved = signal(false);
  protected readonly profileError = signal<ApiError | null>(null);
  protected readonly passwordBusy = signal(false);
  protected readonly passwordError = signal<ApiError | null>(null);

  protected saveProfile(): void {
    this.profile.markAllAsTouched();
    if (this.profile.invalid || this.profileBusy()) {
      return;
    }
    this.profileBusy.set(true);
    this.profileSaved.set(false);
    this.profileError.set(null);
    const v = this.profile.getRawValue();
    this.accountApi.update({ fullName: v.fullName.trim(), timezone: v.timezone }).subscribe({
      next: (user) => {
        this.auth.updateUser(user);
        this.profileSaved.set(true);
        this.profileBusy.set(false);
      },
      error: (err: unknown) => {
        this.profileError.set(toApiError(err));
        this.profileBusy.set(false);
      },
    });
  }

  /** The backend revokes every refresh token on password change, so the user signs in again. */
  protected changePassword(): void {
    this.password.markAllAsTouched();
    if (this.password.invalid || this.passwordBusy()) {
      return;
    }
    this.passwordBusy.set(true);
    this.passwordError.set(null);
    const v = this.password.getRawValue();
    this.accountApi.changePassword(v.currentPassword, v.newPassword).subscribe({
      next: () => this.signOutTo('account.passwordChanged'),
      error: (err: unknown) => {
        this.passwordError.set(toApiError(err));
        this.passwordBusy.set(false);
      },
    });
  }

  protected revokeAll(): void {
    if (!confirm(this.transloco.translate('account.revokeConfirm'))) {
      return;
    }
    this.accountApi.revokeAllSessions().subscribe({ next: () => this.signOutTo(null) });
  }

  private signOutTo(messageKey: string | null): void {
    this.auth.clear();
    void this.router.navigate(['/auth/login'], messageKey ? { state: { messageKey } } : {});
  }

  protected invalid(group: 'profile' | 'password', name: string): boolean {
    const form: import('@angular/forms').AbstractControl = group === 'profile' ? this.profile : this.password;
    const c = form.get(name);
    return !!c && c.invalid && (c.touched || c.dirty);
  }
}
