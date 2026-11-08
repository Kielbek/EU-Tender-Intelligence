import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';

export interface Country {
  code: string;
  code3: string;
  nameEn: string;
  isEu: boolean;
  defaultCurrency: string;
  defaultLanguage: string;
}

export interface Currency {
  code: string;
  nameEn: string;
  minorUnits: number;
}

/** Official EU languages (ISO 639-1) – used for the notice-language filter. */
export const EU_LANGUAGES = ['bg', 'cs', 'da', 'de', 'el', 'en', 'es', 'et', 'fi', 'fr', 'ga', 'hr', 'hu', 'it', 'lt',
  'lv', 'mt', 'nl', 'pl', 'pt', 'ro', 'sk', 'sl', 'sv'] as const;

@Injectable({ providedIn: 'root' })
export class ReferenceService {
  private readonly http = inject(HttpClient);
  private readonly countries$ = this.http.get<Country[]>('/api/v1/countries').pipe(shareReplay(1));
  private readonly currencies$ = this.http.get<Currency[]>('/api/v1/currencies').pipe(shareReplay(1));

  countries(): Observable<Country[]> {
    return this.countries$;
  }

  currencies(): Observable<Currency[]> {
    return this.currencies$;
  }
}
