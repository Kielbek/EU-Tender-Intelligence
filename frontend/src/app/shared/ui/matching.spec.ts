import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import en from '../../../../public/i18n/en.json';
import pl from '../../../../public/i18n/pl.json';
import { MatchBreakdown } from '../../core/api/matching.model';
import { translocoTesting } from '../../testing/transloco-testing';
import { MatchExplanationComponent } from './match-explanation.component';
import { reasonText } from './match-reasons';
import { MatchScoreComponent } from './match-score.component';

/** Every reason code the backend engine can emit (MatchingEngine.java). */
const CODES = [
  'CPV_MATCH', 'CPV_RELATED', 'CPV_OTHER_SECTOR', 'PROFILE_NO_CPV', 'TENDER_NO_CPV', 'COUNTRY_OF_INTEREST',
  'COUNTRY_OUTSIDE', 'HOME_COUNTRY', 'PROFILE_NO_COUNTRIES', 'VALUE_IN_RANGE', 'VALUE_ABOVE_MAX', 'VALUE_BELOW_MIN',
  'TENDER_VALUE_UNKNOWN', 'PROFILE_NO_VALUE_RANGE', 'CONTRACT_TYPE', 'CONTRACT_TYPE_OTHER', 'TENDER_TYPE_UNKNOWN',
  'PROFILE_NO_CONTRACT_TYPES', 'REFERENCE_SAME_SECTOR', 'NO_RELATED_REFERENCE', 'PROFILE_NO_REFERENCES',
];

const BREAKDOWN: MatchBreakdown = {
  score: 75,
  components: [
    { key: 'cpv', points: 45, max: 45 },
    { key: 'location', points: 20, max: 20 },
    { key: 'value', points: 0, max: 15 },
    { key: 'contractType', points: 10, max: 10 },
    { key: 'experience', points: 0, max: 10 },
  ],
  met: [{ code: 'COUNTRY_OF_INTEREST', params: { country: 'DE' } }],
  unmet: [{ code: 'VALUE_ABOVE_MAX', params: { value: 9000000, max: 3000000 } }],
  missing: [{ code: 'PROFILE_NO_REFERENCES', params: {} }],
};

describe('matching UI', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it('has a translation for every reason code in both languages', () => {
    for (const lang of [pl, en] as Record<string, any>[]) {
      for (const code of CODES) {
        expect(lang['match']['reason'][code], code).toBeTruthy();
      }
    }
  });

  it('formats reason parameters for the UI language', () => {
    const t = TestBed.inject(TranslocoService);
    expect(reasonText({ code: 'COUNTRY_OUTSIDE', params: { country: 'DE' } }, t, 'pl')).toContain('Niemcy');
    const value = reasonText({ code: 'VALUE_ABOVE_MAX', params: { value: 9000000, max: 3000000 } }, t, 'pl');
    expect(value).toMatch(/9\s000\s000/);
    expect(value).toContain('€');
    expect(reasonText({ code: 'CONTRACT_TYPE', params: { type: 'WORKS' } }, t, 'pl')).toContain('roboty budowlane');
  });

  it('explains components and reasons', () => {
    const fixture = TestBed.createComponent(MatchExplanationComponent);
    fixture.componentRef.setInput('breakdown', BREAKDOWN);
    fixture.componentRef.setInput('lang', 'pl');
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Branża (CPV)');
    expect(text).toContain('Spełnione');
    expect(text).toContain('Niespełnione');
    expect(text).toContain('Brakujące informacje');
    expect(text).toContain('Profil nie zawiera referencji');
  });

  it('score ring announces the score', () => {
    const fixture = TestBed.createComponent(MatchScoreComponent);
    fixture.componentRef.setInput('score', 82);
    fixture.detectChanges();
    const el = (fixture.nativeElement as HTMLElement).querySelector('[role="img"]')!;
    expect(el.getAttribute('aria-label')).toBe('Dopasowanie 82 na 100');
    expect(el.textContent!.trim()).toBe('82');
  });
});
