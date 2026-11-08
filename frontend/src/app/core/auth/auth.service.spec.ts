import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthResponse } from './auth.model';
import { AuthService } from './auth.service';

export const SESSION: AuthResponse = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresAt: '2026-11-08T00:15:00Z',
  user: {
    id: 'u1',
    email: 'jan@example.com',
    fullName: 'Jan Kowalski',
    locale: 'pl',
    timezone: 'Europe/Warsaw',
    platformRole: 'USER',
    emailVerified: true,
    createdAt: '2026-11-01T00:00:00Z',
  },
};

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restores the session with a CSRF-protected silent refresh', () => {
    let result: boolean | undefined;
    auth.init().subscribe((ok) => (result = ok));
    const req = http.expectOne('/api/v1/auth/refresh');
    expect(req.request.headers.get('X-ETI-CSRF')).toBe('1');
    req.flush(SESSION);
    expect(result).toBe(true);
    expect(auth.status()).toBe('authenticated');
    expect(auth.token()).toBe('access-1');
    expect(auth.user()?.email).toBe('jan@example.com');
  });

  it('becomes anonymous when there is no valid refresh cookie', () => {
    auth.init().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush({ code: 'invalid_session' }, { status: 401, statusText: 'Unauthorized' });
    expect(auth.status()).toBe('anonymous');
    expect(auth.token()).toBeNull();
  });

  it('shares one refresh between concurrent callers', () => {
    auth.refresh().subscribe();
    auth.refresh().subscribe();
    http.expectOne('/api/v1/auth/refresh').flush(SESSION);
  });

  it('never stores the access token in web storage', () => {
    auth.login('jan@example.com', 'secret secret secret').subscribe();
    http.expectOne('/api/v1/auth/login').flush(SESSION);
    expect(JSON.stringify(localStorage)).not.toContain('access-1');
    expect(JSON.stringify(sessionStorage)).not.toContain('access-1');
  });

  it('clears the session on logout even if the request fails', () => {
    auth.login('jan@example.com', 'x').subscribe();
    http.expectOne('/api/v1/auth/login').flush(SESSION);
    auth.logout().subscribe();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 500, statusText: 'Error' });
    expect(auth.status()).toBe('anonymous');
    expect(auth.user()).toBeNull();
  });
});
