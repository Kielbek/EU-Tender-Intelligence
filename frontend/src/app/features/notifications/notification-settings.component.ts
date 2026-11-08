import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { NotificationPreference, NotificationService } from '../../core/api/notification.service';
import { AlertFrequency } from '../../core/api/tender.model';
import { AuthService } from '../../core/auth/auth.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

/** Per-type notification preferences of the signed-in person in the current organization. */
@Component({
  selector: 'eti-notification-settings',
  imports: [RouterLink, TranslocoPipe, ApiErrorAlertComponent, SelectComponent, SkeletonComponent],
  template: `
    <p class="text-sm text-ink-soft">
      {{ 'notifications.settings.intro' | transloco: { org: org.current()?.name ?? '', email: auth.user()?.email ?? '' } }}
    </p>
    @if (!emailVerified()) {
      <p class="mt-3 rounded-[var(--radius-control)] border border-warning/30 bg-warning-soft px-3 py-2 text-sm text-warning" role="note">
        {{ 'notifications.settings.unverified' | transloco }}
      </p>
    }
    <eti-api-error class="mt-3 block" [error]="error()" />

    @if (loading()) {
      <div class="eti-card mt-4 space-y-4 p-5">@for (i of [1, 2, 3]; track i) { <eti-skeleton size="h-5 w-2/3" /> }</div>
    } @else {
      <form class="mt-4" (submit)="$event.preventDefault(); save()">
        <ul class="eti-card divide-y divide-border">
          @for (p of prefs(); track p.type) {
            <li class="grid gap-3 p-5 md:grid-cols-[1fr_auto] md:items-center">
              <div>
                <p class="font-medium">{{ 'notifications.type.' + p.type | transloco }}</p>
                <p class="mt-0.5 text-sm text-ink-muted">{{ 'notifications.settings.desc.' + p.type | transloco }}</p>
              </div>
              <div class="flex flex-wrap items-end gap-3">
                <label class="flex h-10 cursor-pointer items-center gap-2 text-sm">
                  <input type="checkbox" role="switch" [checked]="p.inApp" (change)="patch(p.type, { inApp: $any($event.target).checked })" />
                  {{ 'notifications.settings.inApp' | transloco }}
                </label>
                <div class="w-52">
                  <span class="eti-label" [id]="'email-label-' + p.type">{{ 'notifications.settings.email' | transloco }}</span>
                  <eti-select [inputId]="'email-' + p.type" [spaced]="false" [ariaLabel]="('notifications.settings.email' | transloco) + ': ' + ('notifications.type.' + p.type | transloco)"
                              [options]="frequencyOptions()" [value]="p.emailFrequency"
                              (valueChange)="patch(p.type, { emailFrequency: $any($event) })" />
                </div>
                @if (p.type === 'NEW_MATCH') {
                  <div class="w-36">
                    <span class="eti-label">{{ 'notifications.settings.minScore' | transloco }}</span>
                    <eti-select inputId="min-score" [spaced]="false" [ariaLabel]="'notifications.settings.minScore' | transloco"
                                [options]="scoreOptions" [value]="String(p.minScore ?? 70)"
                                (valueChange)="patch(p.type, { minScore: Number($event) })" />
                  </div>
                }
              </div>
            </li>
          }
        </ul>
        <div class="mt-3 space-y-1 text-xs text-ink-muted">
          <p>{{ 'notifications.settings.digestNote' | transloco }}</p>
          @if (!instantAvailable()) {
            <p>{{ 'notifications.instantPaid' | transloco }} <a routerLink="/billing" class="eti-link">{{ 'billing.seePlans' | transloco }}</a></p>
          }
          <p>{{ 'notifications.settings.searchAlerts' | transloco }}
            <a routerLink="/saved-searches" class="eti-link">{{ 'notifications.settings.searchAlertsLink' | transloco }}</a></p>
        </div>
        <div class="mt-4 flex items-center gap-3">
          <button type="submit" class="eti-btn-primary" [disabled]="saving() || !dirty()">{{ 'notifications.settings.save' | transloco }}</button>
          @if (saved()) {
            <p class="text-sm text-success" role="status">{{ 'notifications.settings.saved' | transloco }}</p>
          }
        </div>
      </form>
    }
  `,
})
export class NotificationSettingsComponent {
  private readonly api = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });
  protected readonly String = String;
  protected readonly Number = Number;

  protected readonly prefs = signal<NotificationPreference[]>([]);
  protected readonly instantAvailable = signal(false);
  protected readonly emailVerified = signal(true);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly dirty = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  protected readonly scoreOptions: SelectOption[] = [50, 60, 70, 80, 90].map((v) => ({ value: String(v), label: `≥ ${v}%` }));
  protected readonly frequencyOptions = computed<SelectOption[]>(() => {
    this.translations();
    const values: AlertFrequency[] = this.instantAvailable() ? ['INSTANT', 'DAILY', 'WEEKLY', 'OFF'] : ['DAILY', 'WEEKLY', 'OFF'];
    return values.map((v) => ({ value: v, label: this.transloco.translate('notifications.frequency.' + v) }));
  });

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      if (orgId) {
        untracked(() => this.load(orgId));
      }
    });
  }

  private load(orgId: string): void {
    this.loading.set(true);
    this.api.preferences(orgId).subscribe({
      next: (r) => {
        this.instantAvailable.set(r.instantAvailable);
        this.emailVerified.set(r.emailVerified);
        // A stored INSTANT after a downgrade is delivered daily; show it as such.
        this.prefs.set(r.preferences.map((p) => (p.emailFrequency === 'INSTANT' && !r.instantAvailable ? { ...p, emailFrequency: 'DAILY' } : p)));
        this.dirty.set(false);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected patch(type: string, change: Partial<NotificationPreference>): void {
    this.prefs.update((list) => list.map((p) => (p.type === type ? { ...p, ...change } : p)));
    this.dirty.set(true);
    this.saved.set(false);
  }

  protected save(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api.savePreferences(orgId, this.prefs()).subscribe({
      next: (r) => {
        this.prefs.set(r.preferences);
        this.saving.set(false);
        this.dirty.set(false);
        this.saved.set(true);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.saving.set(false);
      },
    });
  }
}
