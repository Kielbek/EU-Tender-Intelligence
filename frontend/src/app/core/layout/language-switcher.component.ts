import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LanguageService } from '../i18n/language.service';
import { AVAILABLE_LANGUAGES, isLanguage } from '../i18n/languages';

@Component({
  selector: 'eti-language-switcher',
  imports: [TranslocoPipe],
  template: `
    <label class="sr-only" for="eti-lang">{{ 'language.label' | transloco }}</label>
    <select
      id="eti-lang"
      class="h-9 rounded-md border border-border bg-surface px-2 text-sm text-ink hover:border-ink-muted/50"
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

  protected onChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (isLanguage(value)) {
      this.language.use(value);
    }
  }
}
