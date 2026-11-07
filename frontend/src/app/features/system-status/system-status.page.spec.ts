import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SystemStatus } from '../../core/api/system-status.model';
import { translocoTesting } from '../../testing/transloco-testing';
import { SystemStatusPage } from './system-status.page';

const STATUS: SystemStatus = {
  application: { name: 'EU Tender Intelligence', version: '0.1.0', environment: 'local', defaultLocale: 'pl' },
  dependencies: [
    { name: 'postgresql', status: 'UP', detail: 'pgvector 0.8.0, EU countries: 27' },
    { name: 'redis', status: 'DOWN', detail: 'unreachable' },
  ],
  integrations: [
    { code: 'TED_SEARCH_API', status: 'NOT_IMPLEMENTED', configured: true, plannedStage: 3 },
    { code: 'STRIPE', status: 'PENDING_CONFIGURATION', configured: false, plannedStage: 6 },
  ],
  checkedAt: '2026-11-07T10:00:00Z',
};

describe('SystemStatusPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SystemStatusPage, translocoTesting()],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('shows a skeleton, then live data from the API', async () => {
    const fixture = TestBed.createComponent(SystemStatusPage);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="status-skeleton"]')).not.toBeNull();

    http.expectOne('/api/v1/system/status').flush(STATUS);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(el.querySelector('[data-testid="status-skeleton"]')).toBeNull();
    expect(el.textContent).toContain('0.1.0');
    expect(el.textContent).toContain('EU countries: 27');
    expect(el.textContent).toContain('Działa');
    expect(el.textContent).toContain('Niedostępna');
    expect(el.textContent).toContain('Niezaimplementowana');
    expect(el.textContent).toContain('Wymaga konfiguracji');
  });

  it('shows an error with the correlation id and retries on demand', async () => {
    const fixture = TestBed.createComponent(SystemStatusPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/system/status')
      .flush({ detail: 'x', correlationId: 'corr-12345678' }, { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    const alert = el.querySelector('[data-testid="status-error"]');
    expect(alert?.getAttribute('role')).toBe('alert');
    expect(alert?.textContent).toContain('corr-12345678');

    (alert?.querySelector('button') as HTMLButtonElement).click();
    fixture.detectChanges();
    http.expectOne('/api/v1/system/status').flush(STATUS);
  });
});
