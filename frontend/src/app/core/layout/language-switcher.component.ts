import { Component, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { OptionsPipe } from '../../shared/ui/options.pipe';
import { SelectComponent } from '../../shared/ui/select.component';
import { AccountService } from '../api/account.service';
import { AuthService } from '../auth/auth.service';
import { LanguageService } from '../i18n/language.service';
import { AVAILABLE_LANGUAGES, isLanguage } from '../i18n/languages';

@Component({
  selector: 'eti-language-switcher',
  imports: [TranslocoPipe, SelectComponent, OptionsPipe],
  template: `
    <eti-select class="w-[132px]" [spaced]="false" [ariaLabel]="'language.label' | transloco"
                [options]="languages | etiOptions: 'language.'" [value]="language.current()"
                (valueChange)="onChange($any($event))" />
  `,
})
export class LanguageSwitcherComponent {
  protected readonly language = inject(LanguageService);
  protected readonly languages = AVAILABLE_LANGUAGES;
  private readonly auth = inject(AuthService);
  private readonly account = inject(AccountService);

  protected onChange(value: string): void {
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
