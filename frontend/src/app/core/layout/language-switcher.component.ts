import { Component, ElementRef, HostListener, inject, input, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { LucideGlobe } from '@lucide/angular';
import { OptionsPipe } from '../../shared/ui/options.pipe';
import { SelectComponent } from '../../shared/ui/select.component';
import { AccountService } from '../api/account.service';
import { AuthService } from '../auth/auth.service';
import { LanguageService } from '../i18n/language.service';
import { AVAILABLE_LANGUAGES, isLanguage } from '../i18n/languages';

/** Interface language. {@code compact}: a globe + language code button for the top bar. */
@Component({
  selector: 'eti-language-switcher',
  imports: [TranslocoPipe, SelectComponent, OptionsPipe, LucideGlobe],
  template: `
    @if (compact()) {
      <div class="relative">
        <button type="button" class="eti-btn-ghost h-9 gap-1.5 px-2.5 text-xs font-medium uppercase"
                aria-haspopup="menu" [attr.aria-expanded]="open()" [attr.aria-label]="'language.label' | transloco"
                (click)="open.set(!open())">
          <svg lucideGlobe class="size-4" aria-hidden="true"></svg>{{ language.current() }}
        </button>
        @if (open()) {
          <ul role="menu" class="absolute right-0 z-50 mt-1 min-w-36 rounded-[var(--radius-control)] border border-border bg-surface py-1 shadow-lg">
            @for (l of languages; track l) {
              <li role="none">
                <button type="button" role="menuitemradio" [attr.aria-checked]="l === language.current()"
                        class="flex w-full items-center justify-between gap-3 px-3 py-2 text-left text-[13px] hover:bg-canvas"
                        [class.text-accent]="l === language.current()" [class.font-semibold]="l === language.current()"
                        (click)="onChange(l); open.set(false)">
                  {{ 'language.' + l | transloco }}<span class="text-[11px] text-ink-faint uppercase">{{ l }}</span>
                </button>
              </li>
            }
          </ul>
        }
      </div>
    } @else {
      <eti-select class="w-[132px]" [spaced]="false" [ariaLabel]="'language.label' | transloco"
                  [options]="languages | etiOptions: 'language.'" [value]="language.current()"
                  (valueChange)="onChange($any($event))" />
    }
  `,
})
export class LanguageSwitcherComponent {
  readonly compact = input(false);
  protected readonly language = inject(LanguageService);
  protected readonly languages = AVAILABLE_LANGUAGES;
  protected readonly open = signal(false);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly auth = inject(AuthService);
  private readonly account = inject(AccountService);

  @HostListener('document:click', ['$event'])
  protected outside(e: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(e.target as Node)) {
      this.open.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected escape(): void {
    this.open.set(false);
  }

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
