import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Me } from '../auth/auth.model';

export interface UpdateProfile {
  fullName?: string;
  locale?: string;
  timezone?: string;
}

@Injectable({ providedIn: 'root' })
export class AccountService {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/v1/me');
  }

  update(changes: UpdateProfile): Observable<Me> {
    return this.http.patch<Me>('/api/v1/me', changes);
  }

  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/v1/me/password', { currentPassword, newPassword });
  }

  revokeAllSessions(): Observable<void> {
    return this.http.post<void>('/api/v1/me/sessions/revoke-all', null);
  }
}
