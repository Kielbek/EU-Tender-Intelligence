import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SESSION } from '../../core/auth/auth.service.spec';
import { translocoTesting } from '../../testing/transloco-testing';
import { LoginPage, safeReturnUrl } from './login.page';

describe('safeReturnUrl', () => {
  it('only allows in-app relative paths', () => {
    expect(safeReturnUrl('/organization?org=1')).toBe('/organization?org=1');
    expect(safeReturnUrl('//evil.example')).toBe('/');
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('/auth/login')).toBe('/');
    expect(safeReturnUrl(undefined)).toBe('/');
  });
});

describe('LoginPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LoginPage, translocoTesting()],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function fill(el: HTMLElement, email: string, password: string) {
    const emailInput = el.querySelector<HTMLInputElement>('#email')!;
    const passwordInput = el.querySelector<HTMLInputElement>('#password')!;
    emailInput.value = email;
    emailInput.dispatchEvent(new Event('input'));
    passwordInput.value = password;
    passwordInput.dispatchEvent(new Event('input'));
  }

  it('does not submit an invalid form and marks fields', async () => {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('#email')!.getAttribute('aria-invalid')).toBe('true');
    http.expectNone('/api/v1/auth/login');
  });

  it('shows a translated error for invalid credentials', async () => {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'jan@example.com', 'wrong password');
    el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'invalid_credentials' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('[role=alert]')!.textContent).toContain('Nieprawidłowy e-mail lub hasło.');
  });

  it('offers to resend the activation link for unverified accounts', async () => {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    fill(el, 'new@example.com', 'some long password');
    el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'email_not_verified' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    await fixture.whenStable();
    const resend = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Wyślij ponownie'));
    resend!.click();
    expect(http.expectOne('/api/v1/auth/verify-email/resend').request.body).toEqual({ email: 'new@example.com' });
  });

  it('navigates to the return URL after login', async () => {
    const fixture = TestBed.createComponent(LoginPage);
    fixture.componentRef.setInput('returnUrl', '/organization');
    fixture.detectChanges();
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fill(fixture.nativeElement, 'jan@example.com', 'correct horse battery staple');
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    http.expectOne('/api/v1/auth/login').flush(SESSION);
    expect(navigate).toHaveBeenCalledWith('/organization');
  });
});
