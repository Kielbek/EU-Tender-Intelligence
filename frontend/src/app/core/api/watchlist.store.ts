import { Injectable, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { LanguageService } from '../i18n/language.service';
import { OrgContextService } from '../org/org-context.service';
import { SavedTender } from './tender.model';
import { WorkspaceService } from './tender.service';

/** Ids of tenders watched by the current organization – lets every list show the watch toggle state instantly. */
@Injectable({ providedIn: 'root' })
export class WatchlistStore {
  private readonly api = inject(WorkspaceService);
  private readonly org = inject(OrgContextService);
  private readonly language = inject(LanguageService);
  readonly ids = signal<ReadonlySet<string>>(new Set());

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      if (orgId) {
        this.api.savedTenders(orgId, this.language.current()).subscribe({
          next: (list) => this.ids.set(new Set(list.map((s) => s.tenderId))),
          error: () => this.ids.set(new Set()),
        });
      } else {
        this.ids.set(new Set());
      }
    });
  }

  has(tenderId: string): boolean {
    return this.ids().has(tenderId);
  }

  watch(tenderId: string): Observable<SavedTender> {
    return this.api.saveTender(this.orgId(), tenderId, {}, this.language.current()).pipe(
      tap(() => this.ids.update((s) => new Set([...s, tenderId]))),
    );
  }

  unwatch(tenderId: string): Observable<void> {
    return this.api.unsaveTender(this.orgId(), tenderId).pipe(
      tap(() => this.ids.update((s) => new Set([...s].filter((id) => id !== tenderId)))),
    );
  }

  private orgId(): string {
    const id = this.org.currentId();
    if (!id) {
      throw new Error('No organization selected');
    }
    return id;
  }
}
