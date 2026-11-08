import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Analysis } from '../../core/api/ai.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { translocoTesting } from '../../testing/transloco-testing';
import { TenderAnalysisComponent } from './tender-analysis.component';

const BASE = '/api/v1/organizations/org-1/tenders/t-1';

const DONE: Analysis = {
  id: 'a-1',
  status: 'DONE',
  language: 'pl',
  scope: 'PUBLIC',
  createdAt: '2026-11-08T10:00:00Z',
  completedAt: '2026-11-08T10:02:00Z',
  stale: false,
  result: {
    documents: [{ title: 'Ogłoszenie', kind: 'NOTICE' }, { id: 'd-1', title: 'SWZ.pdf', kind: 'PUBLIC' }],
    skippedDocuments: [],
    droppedUncited: 0,
    truncated: false,
    sections: [
      {
        key: 'SECURITY',
        notFound: false,
        findings: [
          {
            text: 'Wadium 50 000 PLN',
            cited: true,
            sources: [{ documentIndex: 1, documentTitle: 'SWZ.pdf', quote: 'Wadium w wysokości 50 000 PLN.', pageFrom: 3, pageTo: 3 }],
          },
        ],
      },
      { key: 'CRITERIA', notFound: true, findings: [] },
    ],
  },
};

describe('TenderAnalysisComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TenderAnalysisComponent, translocoTesting()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: OrgContextService, useValue: { currentId: signal('org-1'), current: signal(null) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create() {
    const fixture = TestBed.createComponent(TenderAnalysisComponent);
    fixture.componentRef.setInput('tenderId', 't-1');
    fixture.detectChanges();
    return fixture;
  }

  it('shows findings with their quoted sources and missing sections', () => {
    const fixture = create();
    http.expectOne('/api/v1/organizations/org-1/ai/quota')
      .flush({ configured: true, planCode: 'FREE', limit: 2, used: 1, remaining: 1 });
    http.expectOne(`${BASE}/documents`).flush([]);
    http.expectOne((r) => r.url === `${BASE}/analyses/latest`).flush(DONE);
    http.expectOne('/api/v1/organizations/org-1/ai/quota')
      .flush({ configured: true, planCode: 'FREE', limit: 2, used: 1, remaining: 1 });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Wadium i zabezpieczenie');
    expect(el.textContent).toContain('Wadium 50 000 PLN');
    expect(el.textContent).toContain('Nie znaleziono informacji w dostępnej dokumentacji.');
    expect(el.textContent).not.toContain('Wadium w wysokości 50 000 PLN.');
    const toggle = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Źródła (1)'))!;
    toggle.click();
    fixture.detectChanges();
    expect(el.querySelector('blockquote')!.textContent).toContain('Wadium w wysokości 50 000 PLN.');
    expect(el.querySelector('figcaption')!.textContent).toContain('SWZ.pdf, s. 3');
    // A finished, current analysis offers no re-run button.
    expect(el.textContent).not.toContain('Analizuj ponownie');
  });

  it('starts an analysis and reports a missing configuration', () => {
    const fixture = create();
    http.expectOne('/api/v1/organizations/org-1/ai/quota')
      .flush({ configured: false, planCode: 'FREE', limit: 2, used: 0, remaining: 2 });
    http.expectOne(`${BASE}/documents`).flush([]);
    http.expectOne((r) => r.url === `${BASE}/analyses/latest`)
      .flush({ code: 'analysis_missing' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('brak klucza API Anthropic');
    expect(el.textContent).not.toContain('Analizuj dokumentację');
  });

  it('runs an analysis when configured', () => {
    const fixture = create();
    http.expectOne('/api/v1/organizations/org-1/ai/quota')
      .flush({ configured: true, planCode: 'FREE', limit: 2, used: 0, remaining: 2 });
    http.expectOne(`${BASE}/documents`).flush([]);
    http.expectOne((r) => r.url === `${BASE}/analyses/latest`)
      .flush({ code: 'analysis_missing' }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const run = Array.from(el.querySelectorAll('button')).find((b) => b.textContent!.includes('Analizuj dokumentację'))!;
    run.click();
    const req = http.expectOne((r) => r.url === `${BASE}/analyses` && r.method === 'POST');
    expect(req.request.params.get('lang')).toBe('pl');
    req.flush({ ...DONE, status: 'RUNNING', result: undefined });
    fixture.detectChanges();
    expect(el.textContent).toContain('Analiza w toku');
    fixture.destroy(); // stops polling
  });
});
