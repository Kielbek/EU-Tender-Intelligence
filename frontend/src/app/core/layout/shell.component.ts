import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideActivity,
  LucideBookmark,
  LucideBriefcase,
  LucideBuilding2,
  LucideLayoutDashboard,
  LucideSearch,
  LucideStar,
  LucideTarget,
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
    FormsModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    TranslocoPipe,
    LanguageSwitcherComponent,
    LucideActivity,
    LucideBookmark,
    LucideBriefcase,
    LucideBuilding2,
    LucideLayoutDashboard,
    LucideSearch,
    LucideStar,
    LucideTarget,
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
  protected globalQuery = '';
  protected readonly initials = computed(() =>
    (this.auth.user()?.fullName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0]!.toUpperCase())
      .join(''),
  );
  protected readonly roadmap = ['assistant', 'alerts'] as const;

  protected readonly workItems = [
    { path: '/', icon: 'dashboard', key: 'nav.dashboard', exact: true, query: null },
    { path: '/search', icon: 'search', key: 'nav.search', exact: false, query: { status: 'OPEN' } },
    { path: '/matches', icon: 'target', key: 'nav.matches', exact: false, query: null },
    { path: '/watchlist', icon: 'star', key: 'nav.watchlist', exact: false, query: null },
    { path: '/saved-searches', icon: 'bookmark', key: 'nav.savedSearches', exact: false, query: null },
  ] as const;

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  /** Top-bar search over open tenders: full text, or a CPV code when only digits are typed. */
  protected search(): void {
    const q = this.globalQuery.trim();
    // Digits only = a CPV code or prefix (e.g. "45" = construction); anything else = full-text search.
    const params: Record<string, string> = { status: 'OPEN' };
    if (/^\d{2,8}$/.test(q)) {
      params['cpv'] = q.padEnd(8, '0');
    } else if (q) {
      params['q'] = q;
    }
    void this.router.navigate(['/search'], { queryParams: params });
    this.globalQuery = '';
  }

  protected logout(): void {
    this.auth.logout().subscribe(() => void this.router.navigate(['/auth/login']));
  }
}
