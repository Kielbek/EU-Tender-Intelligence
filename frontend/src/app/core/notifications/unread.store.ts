import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { NotificationService } from '../api/notification.service';
import { AuthService } from '../auth/auth.service';
import { OrgContextService } from '../org/org-context.service';

/** Polled while the tab is visible: notifications are created by a background job every few minutes. */
const POLL_MS = 60_000;

/** Unread notification count of the current organization (badge on the bell). */
@Injectable({ providedIn: 'root' })
export class UnreadNotificationsStore {
  private readonly api = inject(NotificationService);
  private readonly org = inject(OrgContextService);
  private readonly auth = inject(AuthService);
  readonly count = signal(0);

  constructor() {
    effect(() => {
      this.org.currentId();
      this.auth.user();
      this.refresh();
    });
    const timer = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') {
        this.refresh();
      }
    }, POLL_MS);
    const onVisible = (): void => {
      if (document.visibilityState === 'visible') {
        this.refresh();
      }
    };
    document.addEventListener('visibilitychange', onVisible);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    });
  }

  refresh(): void {
    const orgId = this.org.currentId();
    if (!orgId || !this.auth.user()) {
      this.count.set(0);
      return;
    }
    this.api.unreadCount(orgId).subscribe({
      next: (r) => this.count.set(r.unread),
      error: () => undefined, // keep the last known value; the next poll retries
    });
  }

  set(n: number): void {
    this.count.set(Math.max(0, n));
  }
}
