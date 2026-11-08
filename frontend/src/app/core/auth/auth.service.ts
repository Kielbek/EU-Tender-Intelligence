import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, map, of, shareReplay, tap } from 'rxjs';
import { AuthResponse, Me, RegisterRequest } from './auth.model';

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous';

/** Header required by the backend on cookie-authenticated endpoints (refresh/logout) – CSRF protection. */
const CSRF_HEADERS = new HttpHeaders({ 'X-ETI-CSRF': '1' });

/**
 * Session state. The access token lives only in memory (never in localStorage); the refresh token is an HttpOnly
 * cookie the frontend cannot read. On page load the session is restored with a silent refresh.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private accessToken: string | null = null;
  private refreshInFlight: Observable<boolean> | null = null;

  readonly status = signal<AuthStatus>('unknown');
  readonly user = signal<Me | null>(null);
  readonly isAdmin = computed(() => this.user()?.platformRole === 'PLATFORM_ADMIN');

  token(): string | null {
    return this.accessToken;
  }

  /** Restores the session from the refresh cookie, if any. Never throws. */
  init(): Observable<boolean> {
    return this.refresh();
  }

  login(email: string, password: string): Observable<Me> {
    return this.http
      .post<AuthResponse>('/api/v1/auth/login', { email, password })
      .pipe(map((res) => this.applySession(res)));
  }

  register(req: RegisterRequest): Observable<void> {
    return this.http.post<void>('/api/v1/auth/register', req);
  }

  verifyEmail(token: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/verify-email', { token });
  }

  resendVerification(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/verify-email/resend', { email });
  }

  forgotPassword(email: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/password/forgot', { email });
  }

  resetPassword(token: string, newPassword: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/password/reset', { token, newPassword });
  }

  /** Single shared refresh for concurrent callers (e.g. several requests failing with 401 at once). */
  refresh(): Observable<boolean> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.http
        .post<AuthResponse>('/api/v1/auth/refresh', null, { headers: CSRF_HEADERS })
        .pipe(
          map((res) => {
            this.applySession(res);
            return true;
          }),
          catchError(() => {
            this.clear();
            return of(false);
          }),
          finalize(() => (this.refreshInFlight = null)),
          shareReplay(1),
        );
    }
    return this.refreshInFlight;
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', null, { headers: CSRF_HEADERS }).pipe(
      catchError(() => of(undefined)),
      tap(() => this.clear()),
      map(() => undefined),
    );
  }

  updateUser(user: Me): void {
    this.user.set(user);
  }

  /** Called when the server says the session is gone (e.g. password changed elsewhere). */
  clear(): void {
    this.accessToken = null;
    this.user.set(null);
    this.status.set('anonymous');
  }

  private applySession(res: AuthResponse): Me {
    this.accessToken = res.accessToken;
    this.user.set(res.user);
    this.status.set('authenticated');
    return res.user;
  }
}
