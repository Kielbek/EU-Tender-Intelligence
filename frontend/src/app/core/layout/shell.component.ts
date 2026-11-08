import { Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideActivity,
  LucideBookmark,
  LucideBuilding2,
  LucideLayoutDashboard,
  LucideSearch,
  LucideStar,
  LucideLandmark,
  LucideLogOut,
  LucideMenu,
  LucideUserRound,
  LucideX,
} from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../auth/auth.service';
import { OrgContextService } from '../org/org-context.service';
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
    LucideBookmark,
    LucideBuilding2,
    LucideLayoutDashboard,
    LucideSearch,
    LucideStar,
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
  protected readonly org = inject(OrgContextService);
  private readonly router = inject(Router);
  protected readonly menuOpen = signal(false);
  protected readonly roadmap = ['company', 'ai', 'alerts'] as const;

  protected readonly workItems = [
    { path: '/', icon: 'dashboard', key: 'nav.dashboard', exact: true, query: null },
    { path: '/search', icon: 'search', key: 'nav.search', exact: false, query: { status: 'OPEN' } },
    { path: '/watchlist', icon: 'star', key: 'nav.watchlist', exact: false, query: null },
    { path: '/saved-searches', icon: 'bookmark', key: 'nav.savedSearches', exact: false, query: null },
  ] as const;

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
