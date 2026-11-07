import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { translocoTesting } from '../../testing/transloco-testing';
import { LanguageService } from './language.service';
import { LANGUAGE_STORAGE_KEY } from './languages';

describe('LanguageService', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ imports: [translocoTesting()] });
  });

  it('defaults to Polish', () => {
    const service = TestBed.inject(LanguageService);
    service.init();
    expect(service.current()).toBe('pl');
    expect(document.documentElement.lang).toBe('pl');
  });

  it('restores a stored language and ignores unsupported values', () => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'en');
    const service = TestBed.inject(LanguageService);
    service.init();
    expect(service.current()).toBe('en');

    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'xx');
    service.init();
    expect(service.current()).toBe('pl');
  });

  it('switches Transloco and persists the choice', () => {
    const service = TestBed.inject(LanguageService);
    service.use('en');
    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('en');
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en');
  });
});
