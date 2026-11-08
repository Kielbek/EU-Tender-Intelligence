import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideActivity,
  LucideBuilding2,
  LucideLandmark,
  LucideLogOut,
  LucideMenu,
  LucideUserRound,
  LucideX,
} from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../auth/auth.service';
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
    LucideBuilding2,
    LucideLandmark,
    LucideLogOut,
    LucideMenu,
    LucideUserRound,
    LucideX,
  ],
  templateUrl: './shell.component.html',
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly menuOpen = signal(false);
  protected readonly roadmap = ['dashboard', 'search', 'company', 'ai', 'alerts'] as const;

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  protected logout(): void {
    this.auth.logout().subscribe(() => void this.router.navigate(['/auth/login']));
  }
}
