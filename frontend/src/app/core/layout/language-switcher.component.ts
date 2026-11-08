import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { AccountService } from '../api/account.service';
import { AuthService } from '../auth/auth.service';
import { LanguageService } from '../i18n/language.service';
import { AVAILABLE_LANGUAGES, isLanguage } from '../i18n/languages';

@Component({
  selector: 'eti-language-switcher',
  imports: [TranslocoPipe],
  template: `
    <label class="sr-only" for="eti-lang">{{ 'language.label' | transloco }}</label>
    <select
      id="eti-lang"
      class="h-[38px] rounded-[10px] border border-border-strong bg-surface px-2.5 text-sm text-ink-soft hover:bg-canvas"
      [value]="language.current()"
      (change)="onChange($event)"
    >
      @for (lang of languages; track lang) {
        <option [value]="lang" [selected]="lang === language.current()">
          {{ 'language.' + lang | transloco }}
        </option>
      }
    </select>
  `,
})
export class LanguageSwitcherComponent {
  protected readonly language = inject(LanguageService);
  protected readonly languages = AVAILABLE_LANGUAGES;
  private readonly auth = inject(AuthService);
  private readonly account = inject(AccountService);

  protected onChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (isLanguage(value)) {
      this.language.use(value);
      // Signed-in users: also store the choice in the profile (it drives the language of e-mails).
      if (this.auth.status() === 'authenticated') {
        this.account.update({ locale: value }).subscribe({
          next: (user) => this.auth.updateUser(user),
          error: () => undefined,
        });
      }
    }
  }
}
