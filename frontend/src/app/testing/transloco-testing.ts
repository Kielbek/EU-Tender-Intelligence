import { TranslocoTestingModule, TranslocoTestingOptions } from '@jsverse/transloco';
import en from '../../../public/i18n/en.json';
import pl from '../../../public/i18n/pl.json';

/** Real translation files, so tests also catch missing keys. */
export function translocoTesting(options: TranslocoTestingOptions = {}) {
  return TranslocoTestingModule.forRoot({
    langs: { pl, en },
    translocoConfig: { availableLangs: ['pl', 'en'], defaultLang: 'pl' },
    preloadLangs: true,
    ...options,
  });
}
