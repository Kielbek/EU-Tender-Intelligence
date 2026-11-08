import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { SESSION } from '../../testing/session.fixture';

describe('authInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    auth.login('jan@example.com', 'x').subscribe();
    http.expectOne('/api/v1/auth/login').flush(SESSION);
  });

  afterEach(() => http.verify());

  it('adds the bearer token to API calls but not to auth endpoints or other hosts', () => {
    client.get('/api/v1/me').subscribe();
    expect(http.expectOne('/api/v1/me').request.headers.get('Authorization')).toBe('Bearer access-1');
    client.get('https://example.org/x').subscribe();
    expect(http.expectOne('https://example.org/x').request.headers.has('Authorization')).toBe(false);
  });

  it('refreshes once on 401 and retries with the new token', () => {
    let body: unknown;
    client.get('/api/v1/me').subscribe((b) => (body = b));
    http.expectOne('/api/v1/me').flush({ code: 'unauthenticated' }, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({ ...SESSION, accessToken: 'access-2' });
    const retry = http.expectOne('/api/v1/me');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ ok: true });
    expect(body).toEqual({ ok: true });
  });

  it('gives up when the refresh fails', () => {
    let failed = false;
    client.get('/api/v1/me').subscribe({ error: () => (failed = true) });
    http.expectOne('/api/v1/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toBe(true);
    expect(auth.status()).toBe('anonymous');
  });
});
