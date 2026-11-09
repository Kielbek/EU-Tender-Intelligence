import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import {
  LucideCheck, LucideClock, LucideLock, LucideMail, LucideRefreshCw, LucideSearch, LucideShield, LucideUser,
  LucideUserMinus, LucideUserPlus, LucideX,
} from '@lucide/angular';
import { Observable, forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ApiError, toApiError } from '../../core/api/api-error';
import { BillingOverview, BillingService } from '../../core/api/billing.service';
import {
  Activity,
  Invitation,
  Member,
  Membership,
  Organization,
  OrganizationRole,
  OrganizationService,
} from '../../core/api/organization.service';
import { ReferenceService } from '../../core/api/reference.service';
import { AuthService } from '../../core/auth/auth.service';
import { countryName } from '../../core/format/format';
import { LanguageService } from '../../core/i18n/language.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { OptionsPipe } from '../../shared/ui/options.pipe';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

export type OrgTab = 'members' | 'invitations' | 'activity' | 'details';

/** At most this many addresses in one invitation round. */
export const MAX_INVITES = 10;
const ACTIVE_DAYS = 30;
const EMAIL = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]+$/;

/** Splits pasted text ("a@x.pl, b@y.pl; c@z.pl" or one per line) into unique, lower-cased addresses. */
export function parseEmails(text: string): { valid: string[]; invalid: string[] } {
  const parts = text.split(/[\s,;]+/).map((p) => p.trim().toLowerCase()).filter(Boolean);
  const unique = [...new Set(parts)];
  return { valid: unique.filter((p) => EMAIL.test(p)), invalid: unique.filter((p) => !EMAIL.test(p)) };
}

/** "3 days ago", "in 5 days" in the UI language; null for missing dates. */
export function relative(iso: string | null | undefined, locale: string, now = Date.now()): string | null {
  if (!iso) {
    return null;
  }
  const diff = (new Date(iso).getTime() - now) / 1000;
  const fmt = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  const abs = Math.abs(diff);
  if (abs < 60) {
    return fmt.format(Math.round(diff), 'second');
  }
  if (abs < 3600) {
    return fmt.format(Math.round(diff / 60), 'minute');
  }
  if (abs < 86400) {
    return fmt.format(Math.round(diff / 3600), 'hour');
  }
  if (abs < 86400 * 45) {
    return fmt.format(Math.round(diff / 86400), 'day');
  }
  return fmt.format(Math.round(diff / (86400 * 30)), 'month');
}

export function initials(name: string | null | undefined, email: string): string {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean);
  const letters = words.length >= 2 ? words[0][0] + words[words.length - 1][0] : (words[0] ?? email).slice(0, 2);
  return letters.toUpperCase();
}

@Component({
  selector: 'eti-organization-page',
  imports: [ReactiveFormsModule, DatePipe, RouterLink, TranslocoPipe, ApiErrorAlertComponent, SkeletonComponent,
    SelectComponent, OptionsPipe, LucideCheck, LucideClock, LucideLock, LucideMail, LucideRefreshCw, LucideSearch,
    LucideShield, LucideUser, LucideUserMinus, LucideUserPlus, LucideX],
  templateUrl: './organization.page.html',
})
export class OrganizationPage implements OnInit {
  private readonly api = inject(OrganizationService);
  private readonly billing = inject(BillingService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly auth = inject(AuthService);
  protected readonly language = inject(LanguageService);
  protected readonly countries = toSignal(inject(ReferenceService).countries(), { initialValue: [] });

  /** Selected organization id from the query string (?org=...). */
  readonly org = input<string>();

  protected readonly roles: OrganizationRole[] = ['COMPANY_ADMIN', 'TEAM_MEMBER'];
  protected readonly initials = initials;
  protected readonly maxInvites = MAX_INVITES;
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });
  protected readonly countryOptions = computed<SelectOption[]>(() => {
    this.translations();
    const lang = this.language.current();
    const list = this.countries()
      .map((c) => ({ value: c.code, label: countryName(c.code, lang) }))
      .sort((a, b) => a.label.localeCompare(b.label, lang));
    return [{ value: '', label: this.transloco.translate('org.noCountry') }, ...list];
  });
  protected readonly orgOptions = computed<SelectOption[]>(() =>
    this.memberships().map((m) => ({ value: m.id, label: m.name })),
  );
  protected readonly roleFilterOptions = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('org.allRoles') },
      ...this.roles.map((r) => ({ value: r, label: this.transloco.translate('org.roles.' + r) }))];
  });

  protected readonly memberships = signal<Membership[]>([]);
  protected readonly organization = signal<Organization | null>(null);
  protected readonly members = signal<Member[]>([]);
  protected readonly invitations = signal<Invitation[]>([]);
  protected readonly usage = signal<BillingOverview | null>(null);
  protected readonly activity = signal<Activity[]>([]);
  protected readonly activityPage = signal(0);
  protected readonly activityMore = signal(false);
  protected readonly activityLoading = signal(false);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly tab = signal<OrgTab>('members');
  protected readonly query = signal('');
  protected readonly roleFilter = signal<string>('');

  protected readonly current = computed(() => {
    const list = this.memberships();
    return list.find((m) => m.id === this.org()) ?? list[0] ?? null;
  });
  protected readonly isAdmin = computed(() => this.current()?.role === 'COMPANY_ADMIN');
  protected readonly tabs = computed<OrgTab[]>(() =>
    this.isAdmin() ? ['members', 'invitations', 'activity', 'details'] : ['members', 'details']);
  /** The last COMPANY_ADMIN can neither leave nor be removed (also enforced by the backend). */
  protected readonly adminCount = computed(() => this.members().filter((m) => m.role === 'COMPANY_ADMIN').length);
  protected readonly me = computed(() => this.members().find((m) => m.userId === this.auth.user()?.id) ?? null);
  protected readonly activeCount = computed(() => {
    const since = Date.now() - ACTIVE_DAYS * 86400_000;
    return this.members().filter((m) => m.lastActiveAt && new Date(m.lastActiveAt).getTime() >= since).length;
  });
  protected readonly seats = computed(() => this.usage()?.usage.seats ?? null);
  /** Members plus open invitations reach the plan's limit: no new invitations until a seat is freed or the plan grows. */
  protected readonly seatsFull = computed(() => {
    const s = this.seats();
    return !!s && s.limit != null && s.used >= s.limit;
  });

  protected readonly filteredMembers = computed(() => {
    const q = this.query().trim().toLocaleLowerCase();
    const role = this.roleFilter();
    const me = this.auth.user()?.id;
    return this.members()
      .filter((m) => (!role || m.role === role)
        && (!q || m.fullName.toLocaleLowerCase().includes(q) || m.email.toLocaleLowerCase().includes(q)))
      .sort((a, b) => (a.userId === me ? -1 : b.userId === me ? 1 : 0)
        || (a.role === b.role ? a.fullName.localeCompare(b.fullName) : a.role === 'COMPANY_ADMIN' ? -1 : 1));
  });

  protected readonly details = this.fb.group({
    name: ['', [Validators.required, Validators.minLength(2), Validators.maxLength(200)]],
    countryCode: [''],
    vatId: ['', Validators.maxLength(32)],
    billingEmail: ['', [Validators.email, Validators.maxLength(254)]],
  });
  protected readonly inviteForm = this.fb.group({
    emails: ['', Validators.required],
    role: ['TEAM_MEMBER' as OrganizationRole, Validators.required],
  });
  protected readonly inviteText = signal('');
  protected readonly parsed = computed(() => parseEmails(this.inviteText()));

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

  protected canRemove(member: Member): boolean {
    const self = member.userId === this.auth.user()?.id;
    const lastAdmin = member.role === 'COMPANY_ADMIN' && this.adminCount() <= 1;
    return (this.isAdmin() || self) && !lastAdmin;
  }

  protected isSelf(member: Member): boolean {
    return member.userId === this.auth.user()?.id;
  }

  protected ago(iso: string | null | undefined): string {
    return relative(iso, this.language.current()) ?? this.transloco.translate('org.never');
  }

  protected daysLeft(inv: Invitation): number {
    return Math.max(0, Math.ceil((new Date(inv.expiresAt).getTime() - Date.now()) / 86400_000));
  }

  protected openTab(t: OrgTab): void {
    this.tab.set(t);
    if (t === 'activity' && !this.activity().length) {
      this.loadActivity(0);
    }
  }

  protected switchTo(id: string): void {
    void this.router.navigate([], { queryParams: { org: id } }).then(() => {
      this.activity.set([]);
      this.tab.set('members');
      this.reload();
    });
  }

  protected reload(): void {
    const m = this.current();
    if (!m) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    this.actionError.set(null);
    const admin = m.role === 'COMPANY_ADMIN';
    forkJoin({
      org: this.api.get(m.id),
      members: this.api.members(m.id),
      invitations: admin ? this.api.invitations(m.id) : of([] as Invitation[]),
      usage: this.billing.overview(m.id).pipe(catchError(() => of(null))),
    }).subscribe({
      next: ({ org, members, invitations, usage }) => {
        this.organization.set(org);
        this.members.set(members);
        this.invitations.set(invitations);
        this.usage.set(usage);
        this.details.reset({
          name: org.name,
          countryCode: org.countryCode ?? '',
          vatId: org.vatId ?? '',
          billingEmail: org.billingEmail ?? '',
        });
        if (admin) {
          this.details.enable();
        } else {
          this.details.disable();
          if (!this.tabs().includes(this.tab())) {
            this.tab.set('members');
          }
        }
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loadError.set(toApiError(err));
        this.loading.set(false);
      },
    });
    if (this.tab() === 'activity' && admin) {
      this.loadActivity(0);
    }
  }

  protected loadActivity(page: number): void {
    const m = this.current();
    if (!m || m.role !== 'COMPANY_ADMIN') {
      return;
    }
    this.activityLoading.set(true);
    this.api.activity(m.id, page).subscribe({
      next: (p) => {
        this.activity.update((list) => (page === 0 ? p.items : [...list, ...p.items]));
        this.activityPage.set(page);
        this.activityMore.set(page + 1 < p.totalPages);
        this.activityLoading.set(false);
      },
      error: (err: unknown) => {
        this.actionError.set(toApiError(err));
        this.activityLoading.set(false);
      },
    });
  }

  /** One sentence for an activity entry, e.g. "Anna Nowak invited jan@firma.pl as Team member". */
  protected describe(a: Activity): string {
    const t = (key: string, params: Record<string, unknown> = {}) => this.transloco.translate(key, params);
    const role = (r: unknown) => (typeof r === 'string' ? t('org.roles.' + r) : '');
    const params = {
      actor: a.actorName ?? a.actorEmail ?? t('org.activity.system'),
      target: a.target ?? t('org.activity.deletedUser'),
      role: role(a.details['role']),
      from: role(a.details['from']),
      to: role(a.details['to']),
      planFrom: String(a.details['from'] ?? '—'),
      planTo: String(a.details['to'] ?? '—'),
      rows: String(a.details['rows'] ?? ''),
      format: String(a.details['format'] ?? '').toUpperCase(),
    };
    // Removing oneself is leaving.
    const action = a.action === 'MEMBER_REMOVED' && a.actorName && a.actorName === a.target ? 'MEMBER_LEFT' : a.action;
    const key = 'org.activity.actions.' + action;
    const text = t(key, params);
    return text === key ? t('org.activity.other', { ...params, action: a.action }) : text;
  }

  protected activityIcon(a: Activity): 'invite' | 'member' | 'remove' | 'role' | 'other' {
    switch (a.action) {
      case 'MEMBER_INVITED':
      case 'INVITATION_ACCEPTED':
        return 'invite';
      case 'MEMBER_REMOVED':
      case 'INVITATION_REVOKED':
        return 'remove';
      case 'MEMBER_ROLE_CHANGED':
        return 'role';
      case 'ORGANIZATION_CREATED':
        return 'member';
      default:
        return 'other';
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
      this.details.markAsPristine();
      this.notice.set(this.transloco.translate('common.saved'));
      this.memberships.update((list) => list.map((x) => (x.id === org.id ? { ...x, name: org.name } : x)));
    });
  }

  protected changeRole(member: Member, role: OrganizationRole): void {
    const m = this.current();
    if (!m || role === member.role) {
      return;
    }
    this.run(this.api.changeRole(m.id, member.userId, role), () => {
      this.notice.set(this.transloco.translate('org.roleChanged', { name: member.fullName }));
      this.reload();
    }, () => this.reload());
  }

  protected remove(member: Member): void {
    const m = this.current();
    const self = this.isSelf(member);
    const question = self
      ? this.transloco.translate('org.leaveConfirm')
      : this.transloco.translate('org.removeConfirm', { name: member.fullName });
    if (!m || !confirm(question)) {
      return;
    }
    this.run(this.api.removeMember(m.id, member.userId), () => {
      if (self) {
        this.memberships.update((list) => list.filter((x) => x.id !== m.id));
        void this.router.navigate(this.memberships().length ? ['/organization'] : ['/setup'], { queryParams: {} })
          .then(() => this.reload());
      } else {
        this.notice.set(this.transloco.translate('org.removed', { name: member.fullName }));
        this.reload();
      }
    });
  }

  protected leave(): void {
    const me = this.me();
    if (me) {
      this.remove(me);
    }
  }

  /** Invites every valid address; stops at the first error (e.g. the seat limit) and reports who was invited. */
  protected invite(): void {
    const m = this.current();
    const { valid, invalid } = this.parsed();
    this.inviteForm.markAllAsTouched();
    if (!m || this.busy() || !valid.length || invalid.length || valid.length > MAX_INVITES) {
      return;
    }
    const role = this.inviteForm.getRawValue().role;
    this.busy.set(true);
    this.actionError.set(null);
    this.notice.set(null);
    const sent: string[] = [];
    const next = (i: number): void => {
      if (i >= valid.length) {
        this.finishInvite(sent, null);
        return;
      }
      this.api.invite(m.id, valid[i], role).subscribe({
        next: () => {
          sent.push(valid[i]);
          next(i + 1);
        },
        error: (err: unknown) => this.finishInvite(sent, toApiError(err)),
      });
    };
    next(0);
  }

  private finishInvite(sent: string[], error: ApiError | null): void {
    this.busy.set(false);
    if (sent.length) {
      this.notice.set(this.transloco.translate('org.invitedN', { n: sent.length, emails: sent.join(', ') }));
      const left = parseEmails(this.inviteText()).valid.filter((e) => !sent.includes(e));
      this.inviteText.set(left.join(', '));
      this.inviteForm.patchValue({ emails: left.join(', ') });
    }
    this.actionError.set(error);
    this.reload();
  }

  protected onEmails(value: string): void {
    this.inviteText.set(value);
  }

  protected resend(inv: Invitation): void {
    const m = this.current();
    if (m) {
      this.run(this.api.resendInvitation(m.id, inv.id), () => {
        this.notice.set(this.transloco.translate('org.resent', { email: inv.email }));
        this.reload();
      });
    }
  }

  protected revoke(inv: Invitation): void {
    const m = this.current();
    if (m && confirm(this.transloco.translate('org.revokeConfirm', { email: inv.email }))) {
      this.run(this.api.revokeInvitation(m.id, inv.id), () => this.reload());
    }
  }

  protected tabKey(e: KeyboardEvent): void {
    const tabs = this.tabs();
    const i = tabs.indexOf(this.tab());
    const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: tabs.length - 1 }[e.key];
    if (next === undefined) {
      return;
    }
    e.preventDefault();
    const t = tabs[(next + tabs.length) % tabs.length];
    this.openTab(t);
    setTimeout(() => document.getElementById('org-tab-' + t)?.focus());
  }

  private run<T>(request: Observable<T>, onSuccess: (value: T) => void, onError?: () => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.notice.set(null);
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
