import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Me } from '../../core/auth/auth.model';
import { AuthService } from '../../core/auth/auth.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { SESSION } from '../../testing/session.fixture';
import { translocoTesting } from '../../testing/transloco-testing';
import { SetupPage } from './setup.page';

type Role = 'COMPANY_ADMIN' | 'TEAM_MEMBER';

function setup(role: Role) {
  const user = signal<Me | null>({ ...SESSION.user, setupCompleted: false });
  const current = signal({ id: 'org-1', name: 'Acme', role, planCode: 'FREE' });
  TestBed.configureTestingModule({
    imports: [SetupPage, translocoTesting()],
    providers: [
      provideRouter([]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: AuthService, useValue: { user, updateUser: (me: Me) => user.set(me) } },
      {
        provide: OrgContextService,
        useValue: { loaded: signal(true), current, currentId: computed(() => current().id), reload: () => undefined },
      },
    ],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  const fixture = TestBed.createComponent(SetupPage);
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  http.match((r) => r.url === '/api/v1/countries').forEach((r) => r.flush([]));
  return { fixture, http, navigate, user, el: fixture.nativeElement as HTMLElement };
}

describe('SetupPage', () => {
  it('sends team members straight to the dashboard and marks setup as done', () => {
    const { http, navigate, user } = setup('TEAM_MEMBER');
    http.expectOne({ method: 'POST', url: '/api/v1/me/setup-complete' }).flush({ ...SESSION.user, setupCompleted: true });
    expect(user()!.setupCompleted).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('requires an industry before going on, and skip ends the wizard', async () => {
    const { fixture, http, navigate, el } = setup('COMPANY_ADMIN');
    http.expectOne((r) => r.url === '/api/v1/organizations/org-1/company-profile').flush(null, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.textContent).toContain('Krok 1 z 3');
    expect(el.querySelector<HTMLInputElement>('#setup-name')!.value).toBe('Acme');
    el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Krok 2 z 3');

    el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Krok 2 z 3');
    expect(el.textContent).toContain('Wybierz co najmniej jedną branżę');

    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === 'Pomiń')!.click();
    http.expectOne('/api/v1/me/setup-complete').flush({ ...SESSION.user, setupCompleted: true });
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('does not trap the user when the server cannot record the skip', () => {
    const { http, navigate, user } = setup('TEAM_MEMBER');
    http.expectOne('/api/v1/me/setup-complete').flush(null, { status: 503, statusText: 'Unavailable' });
    expect(user()!.setupCompleted).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/');
  });
});
