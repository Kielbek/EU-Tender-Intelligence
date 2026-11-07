import { Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { LucideActivity, LucideLandmark, LucideMenu, LucideX } from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';
import { LanguageSwitcherComponent } from './language-switcher.component';

/**
 * Application frame: sidebar + topbar. Only implemented screens are links; upcoming modules are listed as plain,
 * non-interactive text so there are no dead buttons.
 */
@Component({
  selector: 'eti-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TranslocoPipe,
    LanguageSwitcherComponent,
    LucideActivity,
    LucideLandmark,
    LucideMenu,
    LucideX,
  ],
  templateUrl: './shell.component.html',
})
export class ShellComponent {
  protected readonly menuOpen = signal(false);
  protected readonly roadmap = ['dashboard', 'search', 'company', 'ai', 'alerts'] as const;

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }
}
