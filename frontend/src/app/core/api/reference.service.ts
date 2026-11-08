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

@Injectable({ providedIn: 'root' })
export class ReferenceService {
  private readonly http = inject(HttpClient);
  private readonly countries$ = this.http.get<Country[]>('/api/v1/countries').pipe(shareReplay(1));

  countries(): Observable<Country[]> {
    return this.countries$;
  }
}
