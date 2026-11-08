import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { Membership, OrganizationService } from '../api/organization.service';

const STORAGE_KEY = 'eti.org';

/**
 * The organization the user currently works in (watch-list, saved searches and dashboard are organization-scoped).
 * Loaded after sign-in; the last choice is remembered per browser.
 */
@Injectable({ providedIn: 'root' })
export class OrgContextService {
  private readonly api = inject(OrganizationService);
  private readonly auth = inject(AuthService);

  readonly memberships = signal<Membership[]>([]);
  private readonly selectedId = signal<string | null>(readStored());
  readonly loaded = signal(false);

  readonly current = computed(() => {
    const list = this.memberships();
    return list.find((m) => m.id === this.selectedId()) ?? list[0] ?? null;
  });
  readonly currentId = computed(() => this.current()?.id ?? null);

  constructor() {
    effect(() => {
      if (this.auth.status() === 'authenticated') {
        this.reload();
      } else if (this.auth.status() === 'anonymous') {
        this.memberships.set([]);
        this.loaded.set(false);
      }
    });
  }

  reload(): void {
    this.api.mine().subscribe({
      next: (list) => {
        this.memberships.set(list);
        this.loaded.set(true);
      },
      error: () => this.loaded.set(true),
    });
  }

  select(id: string): void {
    this.selectedId.set(id);
    try {
      localStorage.setItem(STORAGE_KEY, id);
    } catch {
      // storage unavailable – choice lasts for this session
    }
  }
}

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}
