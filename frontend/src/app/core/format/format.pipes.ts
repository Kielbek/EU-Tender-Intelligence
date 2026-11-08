import { Pipe, PipeTransform, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { countryName, date, dateTime, languageName, money } from './format';

/** Pure pipes take the locale as an argument so they re-render when the language changes. */
@Pipe({ name: 'country' })
export class CountryPipe implements PipeTransform {
  transform(code: string | undefined | null, locale: string): string {
    return countryName(code, locale);
  }
}

@Pipe({ name: 'langName' })
export class LanguageNamePipe implements PipeTransform {
  transform(code: string | undefined | null, locale: string): string {
    return languageName(code, locale);
  }
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: number | undefined | null, currency: string | undefined | null, locale: string): string {
    return money(value, currency, locale);
  }
}

/** Date/time in the signed-in user's time zone (profile setting). */
@Pipe({ name: 'userDateTime' })
export class UserDateTimePipe implements PipeTransform {
  private readonly auth = inject(AuthService);
  transform(iso: string | undefined | null, locale: string): string {
    return dateTime(iso, locale, this.auth.user()?.timezone);
  }
}

@Pipe({ name: 'userDate' })
export class UserDatePipe implements PipeTransform {
  private readonly auth = inject(AuthService);
  transform(iso: string | undefined | null, locale: string): string {
    return date(iso, locale, this.auth.user()?.timezone);
  }
}

export const FORMAT_PIPES = [CountryPipe, LanguageNamePipe, MoneyPipe, UserDateTimePipe, UserDatePipe] as const;
