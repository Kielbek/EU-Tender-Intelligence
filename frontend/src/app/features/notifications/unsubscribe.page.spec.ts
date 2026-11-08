import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { translocoTesting } from '../../testing/transloco-testing';
import { UnsubscribePage } from './unsubscribe.page';

function setup(token: string | null) {
  TestBed.configureTestingModule({
    imports: [UnsubscribePage, translocoTesting()],
    providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(token ? { token } : {}) } } }],
  });
  const fixture = TestBed.createComponent(UnsubscribePage);
  fixture.detectChanges();
  return { fixture, http: TestBed.inject(HttpTestingController), el: fixture.nativeElement as HTMLElement };
}

describe('UnsubscribePage', () => {
  it('unsubscribes only after the button is clicked (link scanners do not)', () => {
    const { fixture, http, el } = setup('tok.sig');
    http.expectNone('/api/v1/notifications/unsubscribe');
    el.querySelector<HTMLButtonElement>('button.eti-btn-primary')!.click();
    const req = http.expectOne('/api/v1/notifications/unsubscribe');
    expect(req.request.body).toEqual({ token: 'tok.sig' });
    req.flush({ organizationName: 'Acme' });
    fixture.detectChanges();
    expect(el.textContent).toContain('z organizacji Acme');
  });

  it('explains a broken link', () => {
    const { el } = setup(null);
    expect(el.querySelector('[role=alert]')?.textContent).toContain('niepełny');
  });
});
