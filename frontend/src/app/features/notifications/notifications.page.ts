import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideBell, LucideCalendarClock, LucideCheckCheck, LucideFilePen, LucideSearch, LucideTarget } from '@lucide/angular';
import { map } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import { AppNotification, NotificationService } from '../../core/api/notification.service';
import { AuthService } from '../../core/auth/auth.service';
import { dateTime, money } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { UnreadNotificationsStore } from '../../core/notifications/unread.store';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { paramsFromApiFilters } from '../search/search-state';
import { NotificationSettingsComponent } from './notification-settings.component';

const PAGE_SIZE = 20;

/** Inbox of the signed-in person in the current organization, plus the notification settings tab. */
@Component({
  selector: 'eti-notifications-page',
  imports: [RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent,
    NotificationSettingsComponent, LucideBell, LucideCalendarClock, LucideCheckCheck, LucideFilePen, LucideSearch,
    LucideTarget],
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="eti-h1">{{ 'notifications.title' | transloco }}</h1>
        <p class="eti-subtitle mt-1.5">{{ 'notifications.subtitle' | transloco: { org: org.current()?.name ?? '' } }}</p>
      </div>
    </div>

    <div class="mt-5 flex gap-1 border-b border-border" role="tablist">
      @for (t of tabs; track t) {
        <button type="button" role="tab" [id]="'tab-' + t" [attr.aria-selected]="tab() === t" [attr.aria-controls]="'panel-' + t"
                class="-mb-px border-b-2 px-3 py-2 text-sm font-medium"
                [class.border-accent]="tab() === t" [class.text-accent]="tab() === t"
                [class.border-transparent]="tab() !== t" [class.text-ink-muted]="tab() !== t"
                (click)="selectTab(t)">
          {{ 'notifications.tabs.' + t | transloco }}
          @if (t === 'inbox' && unread.count() > 0) {
            <span class="eti-num ml-1 rounded-full bg-accent-soft px-1.5 text-xs text-accent">{{ unread.count() }}</span>
          }
        </button>
      }
    </div>

    @if (tab() === 'settings') {
      <div id="panel-settings" role="tabpanel" aria-labelledby="tab-settings" class="mt-5">
        <eti-notification-settings />
      </div>
    } @else {
      <div id="panel-inbox" role="tabpanel" aria-labelledby="tab-inbox" class="mt-4">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div class="flex gap-1" role="group" [attr.aria-label]="'notifications.filter.all' | transloco">
            <button type="button" class="eti-btn-secondary h-8 px-3 text-[13px]" [class.!bg-accent-soft]="!unreadOnly()"
                    [class.!text-accent]="!unreadOnly()" [attr.aria-pressed]="!unreadOnly()" (click)="setFilter(false)">
              {{ 'notifications.filter.all' | transloco }}
            </button>
            <button type="button" class="eti-btn-secondary h-8 px-3 text-[13px]" [class.!bg-accent-soft]="unreadOnly()"
                    [class.!text-accent]="unreadOnly()" [attr.aria-pressed]="unreadOnly()" (click)="setFilter(true)">
              {{ 'notifications.filter.unread' | transloco }}
            </button>
          </div>
          @if (unread.count() > 0) {
            <button type="button" class="eti-btn-ghost h-8 text-[13px]" (click)="markAllRead()">
              <svg lucideCheckCheck class="size-4" aria-hidden="true"></svg>{{ 'notifications.markAllRead' | transloco }}
            </button>
          }
        </div>

        <div class="mt-3" aria-live="polite" [attr.aria-busy]="loading()">
          <eti-api-error [error]="error()" />
          @if (loading() && !items().length) {
            <div class="eti-card divide-y divide-border">
              @for (i of [1, 2, 3]; track i) { <div class="p-4"><eti-skeleton size="h-4 w-2/3" /></div> }
            </div>
          } @else if (!items().length && !error()) {
            <div class="eti-card p-8 text-center">
              <svg lucideBell class="mx-auto size-8 text-ink-faint" aria-hidden="true"></svg>
              <p class="mt-3 font-medium">{{ (unreadOnly() ? 'notifications.emptyUnread' : 'notifications.emptyTitle') | transloco }}</p>
              @if (!unreadOnly()) {
                <p class="mx-auto mt-2 max-w-xl text-sm text-ink-muted">{{ 'notifications.emptyHint' | transloco }}</p>
                <div class="mt-4 flex flex-wrap justify-center gap-2">
                  <a routerLink="/company" class="eti-btn-secondary h-9">{{ 'notifications.emptyProfile' | transloco }}</a>
                  <a routerLink="/search" [queryParams]="{ status: 'OPEN' }" class="eti-btn-secondary h-9">{{ 'notifications.emptyWatch' | transloco }}</a>
                  <a routerLink="/saved-searches" class="eti-btn-secondary h-9">{{ 'notifications.emptySearches' | transloco }}</a>
                </div>
              }
            </div>
          } @else {
            <ul class="eti-card divide-y divide-border overflow-hidden">
              @for (n of items(); track n.id) {
                <li class="relative flex gap-3 p-4 hover:bg-canvas data-[unread=true]:bg-accent-soft/40" [attr.data-unread]="!n.readAt">
                  <span class="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-full bg-canvas text-ink-muted"
                        aria-hidden="true">
                    @switch (n.type) {
                      @case ('NEW_MATCH') { <svg lucideTarget class="size-4 text-accent"></svg> }
                      @case ('WATCHED_CHANGED') { <svg lucideFilePen class="size-4 text-warning"></svg> }
                      @case ('DEADLINE_APPROACHING') { <svg lucideCalendarClock class="size-4 text-danger"></svg> }
                      @case ('SAVED_SEARCH') { <svg lucideSearch class="size-4 text-accent"></svg> }
                    }
                  </span>
                  <div class="min-w-0 flex-1">
                    <p class="text-xs font-semibold tracking-[0.02em] text-ink-muted uppercase">
                      {{ 'notifications.type.' + n.type | transloco }}
                      <span class="font-normal normal-case"> · {{ n.createdAt | userDateTime: language.current() }}</span>
                    </p>
                    <button type="button" class="mt-0.5 block text-left font-medium text-ink after:absolute after:inset-0 hover:text-accent"
                            (click)="open(n)">
                      {{ headline(n) }}
                    </button>
                    @for (line of details(n); track $index) {
                      <p class="mt-0.5 text-sm text-ink-soft">{{ line }}</p>
                    }
                  </div>
                  @if (!n.readAt) {
                    <button type="button" class="relative z-10 mt-1 size-6 shrink-0 rounded-full hover:bg-surface"
                            [attr.aria-label]="'notifications.markRead' | transloco" [title]="'notifications.markRead' | transloco"
                            (click)="markRead(n)">
                      <span class="mx-auto block size-2.5 rounded-full bg-accent"></span>
                    </button>
                  }
                </li>
              }
            </ul>
            @if (items().length < total()) {
              <div class="mt-3 text-center">
                <button type="button" class="eti-btn-secondary" [disabled]="loading()" (click)="loadMore()">
                  {{ 'notifications.loadMore' | transloco }}
                </button>
              </div>
            }
          }
        </div>
      </div>
    }
  `,
})
export class NotificationsPage {
  private readonly api = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly transloco = inject(TranslocoService);
  private readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly unread = inject(UnreadNotificationsStore);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  protected readonly tabs = ['inbox', 'settings'] as const;
  protected readonly tab = toSignal(
    this.route.queryParamMap.pipe(map((p) => (p.get('tab') === 'settings' ? 'settings' : 'inbox'))),
    { initialValue: 'inbox' as const },
  );
  protected readonly unreadOnly = signal(false);
  protected readonly items = signal<AppNotification[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  private page = 0;

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      this.unreadOnly();
      if (orgId) {
        untracked(() => this.load(true));
      }
    });
  }

  protected selectTab(t: 'inbox' | 'settings'): void {
    void this.router.navigate([], { queryParams: { tab: t === 'settings' ? 'settings' : null }, replaceUrl: true });
  }

  protected setFilter(unread: boolean): void {
    this.unreadOnly.set(unread);
  }

  private load(reset: boolean): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    if (reset) {
      this.page = 0;
    }
    this.loading.set(true);
    this.error.set(null);
    this.api.list(orgId, this.page, PAGE_SIZE, this.unreadOnly()).subscribe({
      next: (res) => {
        this.items.update((list) => (reset ? res.items : [...list, ...res.items]));
        this.total.set(res.totalItems);
        this.unread.set(res.unread);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected loadMore(): void {
    this.page++;
    this.load(false);
  }

  protected markRead(n: AppNotification): void {
    const orgId = this.org.currentId();
    if (!orgId || n.readAt) {
      return;
    }
    this.api.markRead(orgId, n.id).subscribe({
      next: () => {
        const now = new Date().toISOString();
        this.items.update((list) => list.map((x) => (x.id === n.id ? { ...x, readAt: now } : x)));
        this.unread.set(this.unread.count() - 1);
      },
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }

  protected markAllRead(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.api.markAllRead(orgId).subscribe({
      next: () => {
        const now = new Date().toISOString();
        this.items.update((list) => list.map((x) => (x.readAt ? x : { ...x, readAt: now })));
        this.unread.set(0);
        if (this.unreadOnly()) {
          this.load(true);
        }
      },
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
  }

  protected open(n: AppNotification): void {
    this.markRead(n);
    if (n.type === 'SAVED_SEARCH' && n.savedSearch) {
      void this.router.navigate(['/search'], { queryParams: paramsFromApiFilters(n.savedSearch.filters) });
    } else if (n.tender) {
      void this.router.navigate(['/tenders', n.tender.id]);
    }
  }

  protected headline(n: AppNotification): string {
    this.translations();
    if (n.type === 'SAVED_SEARCH') {
      return this.t('notifications.item.search', { count: n.payload.count ?? 0, name: n.savedSearch?.name ?? n.payload.name ?? '' });
    }
    return n.tender?.title ?? '—';
  }

  /** Secondary lines: score, what changed, the deadline. */
  protected details(n: AppNotification): string[] {
    this.translations();
    const lang = this.language.current();
    const tz = this.auth.user()?.timezone;
    const fmt = (v: unknown): string => (typeof v === 'string' && v ? dateTime(v, lang, tz) : this.t('notifications.item.none'));
    switch (n.type) {
      case 'NEW_MATCH':
        return [
          this.t('notifications.item.match', { score: n.payload.score ?? 0 }),
          ...(n.tender?.submissionDeadline ? [this.t('notifications.item.deadline', { date: fmt(n.tender.submissionDeadline) })] : []),
        ];
      case 'DEADLINE_APPROACHING':
        return [this.t('notifications.item.deadline', { date: fmt(n.payload.deadline ?? n.tender?.submissionDeadline) })];
      case 'WATCHED_CHANGED': {
        const out: string[] = [];
        if (n.payload.changeKind === 'CANCELLATION') out.push(this.t('notifications.item.cancelled'));
        if (n.payload.changeKind === 'AWARD') out.push(this.t('notifications.item.awarded'));
        for (const c of n.payload.changes ?? []) {
          if (c.field === 'submissionDeadline') {
            out.push(this.t('notifications.item.submissionDeadline', { old: fmt(c.old), new: fmt(c.new) }));
          } else if (c.field === 'status' && !out.length) {
            out.push(this.t('notifications.item.status', {
              old: c.old ? this.t('tender.status.' + String(c.old)) : this.t('notifications.item.none'),
              new: c.new ? this.t('tender.status.' + String(c.new)) : this.t('notifications.item.none'),
            }));
          } else if (c.field === 'estimatedValue') {
            const num = (v: unknown): string => (v === null || v === undefined ? this.t('notifications.item.none') : money(Number(v), null, lang));
            out.push(this.t('notifications.item.estimatedValue', { old: num(c.old), new: num(c.new) }));
          } else if (c.field === 'documentsUrl') {
            out.push(this.t('notifications.item.documentsUrl'));
          }
        }
        return out.length ? out : [this.t('notifications.item.changed')];
      }
      default:
        return [];
    }
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.transloco.translate(key, params);
  }
}
