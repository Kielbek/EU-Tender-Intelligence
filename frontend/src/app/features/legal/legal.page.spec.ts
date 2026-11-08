import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { translocoTesting } from '../../testing/transloco-testing';
import { LegalPage } from './legal.page';

describe('LegalPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LegalPage, translocoTesting()],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('warns when operator details are missing instead of inventing them', () => {
    const fixture = TestBed.createComponent(LegalPage);
    fixture.componentRef.setInput('doc', 'privacy');
    fixture.detectChanges();
    http.expectOne('/api/v1/system/legal').flush({
      configured: false,
      termsVersion: '2026-11-draft',
      processors: [{ code: 'HOSTING' }, { code: 'AI', name: 'Anthropic PBC' }],
    });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Polityka prywatności');
    expect(text).toContain('nie zostały jeszcze uzupełnione');
    expect(text).toContain('nie uzupełniono');
    expect(text).toContain('Model AI do analizy dokumentów: Anthropic PBC');
    expect(text).toContain('Pobierz moje dane');
  });

  it('shows configured operator details on the terms page', () => {
    const fixture = TestBed.createComponent(LegalPage);
    fixture.componentRef.setInput('doc', 'terms');
    fixture.detectChanges();
    http.expectOne('/api/v1/system/legal').flush({
      configured: true,
      operatorName: 'Operator Sp. z o.o.',
      operatorAddress: 'ul. Przykładowa 1, 00-001 Warszawa',
      operatorEmail: 'kontakt@example.test',
      termsVersion: '2026-11',
      processors: [],
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Regulamin serwisu');
    expect(el.textContent).toContain('Operator Sp. z o.o.');
    expect(el.textContent).not.toContain('nie zostały jeszcze uzupełnione');
    expect(el.querySelector('a[href="mailto:kontakt@example.test"]')).not.toBeNull();
  });
});
