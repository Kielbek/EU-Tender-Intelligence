import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import {
  Invitation,
  Member,
  Membership,
  Organization,
  OrganizationRole,
  OrganizationService,
} from '../../core/api/organization.service';
import { ReferenceService } from '../../core/api/reference.service';
import { AuthService } from '../../core/auth/auth.service';
import { LanguageService } from '../../core/i18n/language.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

@Component({
  selector: 'eti-organization-page',
  imports: [ReactiveFormsModule, DatePipe, TranslocoPipe, ApiErrorAlertComponent, SkeletonComponent],
  templateUrl: './organization.page.html',
})
export class OrganizationPage implements OnInit {
  private readonly api = inject(OrganizationService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly auth = inject(AuthService);
  protected readonly language = inject(LanguageService);
  protected readonly countries = toSignal(inject(ReferenceService).countries(), { initialValue: [] });

  /** Selected organization id from the query string (?org=...). */
  readonly org = input<string>();

  protected readonly roles: OrganizationRole[] = ['COMPANY_ADMIN', 'TEAM_MEMBER'];
  protected readonly memberships = signal<Membership[]>([]);
  protected readonly organization = signal<Organization | null>(null);
  protected readonly members = signal<Member[]>([]);
  protected readonly invitations = signal<Invitation[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly saved = signal(false);
  protected readonly invitedEmail = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly current = computed(() => {
    const list = this.memberships();
    return list.find((m) => m.id === this.org()) ?? list[0] ?? null;
  });
  protected readonly isAdmin = computed(() => this.current()?.role === 'COMPANY_ADMIN');
  /** The last COMPANY_ADMIN can neither leave nor be removed (also enforced by the backend). */
  protected readonly adminCount = computed(() => this.members().filter((m) => m.role === 'COMPANY_ADMIN').length);

  protected canRemove(member: Member): boolean {
    const self = member.userId === this.auth.user()?.id;
    const lastAdmin = member.role === 'COMPANY_ADMIN' && this.adminCount() <= 1;
    return (this.isAdmin() || self) && !lastAdmin;
  }

  protected readonly details = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    countryCode: [''],
    vatId: ['', Validators.maxLength(32)],
    billingEmail: ['', [Validators.email, Validators.maxLength(254)]],
  });
  protected readonly inviteForm = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    role: ['TEAM_MEMBER' as OrganizationRole, Validators.required],
  });

  ngOnInit(): void {
    this.api.mine().subscribe({
      next: (list) => {
        this.memberships.set(list);
        this.reload();
      },
      error: (err: unknown) => {
        this.loadError.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected switchTo(id: string): void {
    void this.router.navigate([], { queryParams: { org: id } }).then(() => this.reload());
  }

  protected reload(): void {
    const m = this.current();
    if (!m) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.actionError.set(null);
    this.api.get(m.id).subscribe({
      next: (org) => {
        this.organization.set(org);
        this.details.reset({
          name: org.name,
          countryCode: org.countryCode ?? '',
          vatId: org.vatId ?? '',
          billingEmail: org.billingEmail ?? '',
        });
        if (m.role === 'COMPANY_ADMIN') {
          this.details.enable();
        } else {
          this.details.disable();
        }
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loadError.set(toApiError(err));
        this.loading.set(false);
      },
    });
    this.api.members(m.id).subscribe({ next: (list) => this.members.set(list) });
    if (m.role === 'COMPANY_ADMIN') {
      this.api.invitations(m.id).subscribe({ next: (list) => this.invitations.set(list) });
    } else {
      this.invitations.set([]);
    }
  }

  protected saveDetails(): void {
    const m = this.current();
    this.details.markAllAsTouched();
    if (!m || this.details.invalid || this.busy()) {
      return;
    }
    this.run(this.api.update(m.id, this.details.getRawValue()), (org) => {
      this.organization.set(org);
      this.saved.set(true);
      this.memberships.update((list) => list.map((x) => (x.id === org.id ? { ...x, name: org.name } : x)));
    });
  }

  protected changeRole(member: Member, event: Event): void {
    const m = this.current();
    const role = (event.target as HTMLSelectElement).value as OrganizationRole;
    if (!m || role === member.role) {
      return;
    }
    this.run(this.api.changeRole(m.id, member.userId, role), () => this.reload(), () => this.reload());
  }

  protected remove(member: Member): void {
    const m = this.current();
    const self = member.userId === this.auth.user()?.id;
    const question = self
      ? this.transloco.translate('org.leaveConfirm')
      : this.transloco.translate('org.removeConfirm', { name: member.fullName });
    if (!m || !confirm(question)) {
      return;
    }
    this.run(this.api.removeMember(m.id, member.userId), () => {
      if (self) {
        this.memberships.update((list) => list.filter((x) => x.id !== m.id));
        void this.router.navigate([], { queryParams: {} }).then(() => this.reload());
      } else {
        this.reload();
      }
    });
  }

  protected invite(): void {
    const m = this.current();
    this.inviteForm.markAllAsTouched();
    if (!m || this.inviteForm.invalid || this.busy()) {
      return;
    }
    const { email, role } = this.inviteForm.getRawValue();
    this.run(this.api.invite(m.id, email.trim(), role), () => {
      this.invitedEmail.set(email.trim());
      this.inviteForm.reset({ email: '', role: 'TEAM_MEMBER' });
      this.reload();
    });
  }

  protected revoke(invitation: Invitation): void {
    const m = this.current();
    if (m) {
      this.run(this.api.revokeInvitation(m.id, invitation.id), () => this.reload());
    }
  }

  private run<T>(
    request: import('rxjs').Observable<T>,
    onSuccess: (value: T) => void,
    onError?: () => void,
  ): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.saved.set(false);
    this.invitedEmail.set(null);
    request.subscribe({
      next: (value) => {
        this.busy.set(false);
        onSuccess(value);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.actionError.set(toApiError(err));
        onError?.();
      },
    });
  }
}
