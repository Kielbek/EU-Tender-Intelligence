import { HttpClient, HttpResponse } from '@angular/common/http';
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

  /** GDPR export: every piece of personal data as a JSON file. */
  exportData(): Observable<HttpResponse<Blob>> {
    return this.http.get('/api/v1/me/export', { responseType: 'blob', observe: 'response' });
  }

  deleteAccount(password: string): Observable<void> {
    return this.http.delete<void>('/api/v1/me', { body: { password } });
  }

  completeSetup(): Observable<Me> {
    return this.http.post<Me>('/api/v1/me/setup-complete', null);
  }

  revokeAllSessions(): Observable<void> {
    return this.http.post<void>('/api/v1/me/sessions/revoke-all', null);
  }
}
