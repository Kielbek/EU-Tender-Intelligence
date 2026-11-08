import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { DashboardSummary } from '../../core/api/tender.model';
import { translocoTesting } from '../../testing/transloco-testing';
import { OnboardingComponent } from './onboarding.component';

function summary(onboarding: DashboardSummary['onboarding']): DashboardSummary {
  return { onboarding } as DashboardSummary;
}

describe('OnboardingComponent', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [OnboardingComponent, translocoTesting()], providers: [provideRouter([])] });
  });

  it('shows progress from real data and can be hidden per organization', () => {
    const fixture = TestBed.createComponent(OnboardingComponent);
    fixture.componentRef.setInput('orgId', 'org-1');
    fixture.componentRef.setInput('data',
      summary({ profile: true, watched: false, savedSearch: true, aiAnalysis: false, teammate: false }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Pierwsze kroki');
    expect(el.textContent).toContain('2/5');
    expect(el.querySelector('[role=progressbar]')!.getAttribute('aria-valuenow')).toBe('2');
    el.querySelector<HTMLButtonElement>('button[aria-label="Ukryj przewodnik"]')!.click();
    fixture.detectChanges();
    expect(el.textContent).not.toContain('Pierwsze kroki');
    expect(localStorage.getItem('eti.onboarding.hidden.org-1')).toBe('1');
  });

  it('disappears when every step is done', () => {
    const fixture = TestBed.createComponent(OnboardingComponent);
    fixture.componentRef.setInput('orgId', 'org-2');
    fixture.componentRef.setInput('data',
      summary({ profile: true, watched: true, savedSearch: true, aiAnalysis: true, teammate: true }));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Pierwsze kroki');
  });
});
