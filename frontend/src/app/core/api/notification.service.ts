import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AlertFrequency, SearchFilters } from './tender.model';

export type NotificationType = 'NEW_MATCH' | 'WATCHED_CHANGED' | 'DEADLINE_APPROACHING' | 'SAVED_SEARCH';
export type ConfigurableType = Exclude<NotificationType, 'SAVED_SEARCH'>;

export interface TenderChange {
  field: string;
  old?: unknown;
  new?: unknown;
}

export interface AppNotification {
  id: string;
  type: NotificationType;
  createdAt: string;
  readAt?: string;
  tender?: { id: string; title: string; countryCode: string; submissionDeadline?: string; status: string };
  savedSearch?: { id: string; name: string; filters: SearchFilters };
  payload: {
    score?: number;
    changeKind?: string;
    changes?: TenderChange[];
    deadline?: string;
    count?: number;
    name?: string;
  };
}

export interface NotificationPage {
  items: AppNotification[];
  totalItems: number;
  unread: number;
  page: number;
  size: number;
}

export interface NotificationPreference {
  type: ConfigurableType;
  inApp: boolean;
  emailFrequency: AlertFrequency;
  minScore?: number | null;
}

export interface NotificationPreferences {
  preferences: NotificationPreference[];
  instantAvailable: boolean;
  emailVerified: boolean;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly http = inject(HttpClient);

  list(orgId: string, page: number, size: number, unread = false): Observable<NotificationPage> {
    return this.http.get<NotificationPage>(`/api/v1/organizations/${orgId}/notifications`, {
      params: { page, size, unread },
    });
  }

  unreadCount(orgId: string): Observable<{ unread: number }> {
    return this.http.get<{ unread: number }>(`/api/v1/organizations/${orgId}/notifications/unread-count`);
  }

  markRead(orgId: string, id: string): Observable<void> {
    return this.http.post<void>(`/api/v1/organizations/${orgId}/notifications/${id}/read`, null);
  }

  markAllRead(orgId: string): Observable<void> {
    return this.http.post<void>(`/api/v1/organizations/${orgId}/notifications/read-all`, null);
  }

  preferences(orgId: string): Observable<NotificationPreferences> {
    return this.http.get<NotificationPreferences>(`/api/v1/organizations/${orgId}/notification-preferences`);
  }

  savePreferences(orgId: string, preferences: NotificationPreference[]): Observable<NotificationPreferences> {
    return this.http.put<NotificationPreferences>(`/api/v1/organizations/${orgId}/notification-preferences`, {
      preferences,
    });
  }

  unsubscribe(token: string): Observable<{ organizationName?: string }> {
    return this.http.post<{ organizationName?: string }>('/api/v1/notifications/unsubscribe', { token });
  }
}
