import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SystemStatusService } from './system-status.service';

describe('SystemStatusService', () => {
  it('calls the backend status endpoint', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(SystemStatusService);
    const http = TestBed.inject(HttpTestingController);

    let version: string | undefined;
    service.getStatus().subscribe((s) => (version = s.application.version));
    const req = http.expectOne('/api/v1/system/status');
    expect(req.request.method).toBe('GET');
    req.flush({ application: { name: 'x', version: '1.2.3', environment: 'test', defaultLocale: 'pl' }, dependencies: [], integrations: [], checkedAt: '2026-01-01T00:00:00Z' });

    expect(version).toBe('1.2.3');
    http.verify();
  });
});
