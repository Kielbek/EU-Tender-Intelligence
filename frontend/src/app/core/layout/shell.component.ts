import { Component, ElementRef, HostListener, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRouteSnapshot, NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  LucideActivity,
  LucideBell,
  LucideBookmark,
  LucideBriefcase,
  LucideBuilding2,
  LucideChevronDown,
  LucideChevronRight,
  LucideCreditCard,
  LucideHouse,
  LucideLandmark,
  LucideLayoutDashboard,
  LucideLogOut,
  LucideMenu,
  LucideSearch,
  LucideStar,
  LucideTarget,
  LucideUserRound,
  LucideX,
} from '@lucide/angular';
import { TranslocoPipe } from '@jsverse/transloco';
import { filter, map } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { OrgContextService } from '../org/org-context.service';
import { UnreadNotificationsStore } from '../notifications/unread.store';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { LanguageSwitcherComponent } from './language-switcher.component';

type Icon =
  | 'dashboard'
  | 'search'
  | 'target'
  | 'star'
  | 'bookmark'
  | 'bell'
  | 'briefcase'
  | 'building'
  | 'card'
  | 'user'
  | 'activity';

interface NavItem {
  path: string;
  icon: Icon;
  key: string;
  exact: boolean;
  query: Record<string, string> | null;
}

interface NavSection {
  id: string;
  key: string;
  items: NavItem[];
}

/** Breadcrumb trail declared on routes: `data: { section: 'nav.workspace', crumb: 'nav.search' }`. */
export interface Crumb {
  key: string;
  link?: string;
}

const COLLAPSE_KEY = 'eti.sidebar.collapsed';

/**
 * Application frame: sidebar + top bar + breadcrumbs + footer. Only implemented screens are links; upcoming modules
 * are listed as plain, non-interactive text so there are no dead buttons.
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
    SelectComponent,
    LucideActivity,
    LucideBell,
    LucideBookmark,
    LucideBriefcase,
    LucideBuilding2,
    LucideChevronDown,
    LucideChevronRight,
    LucideCreditCard,
    LucideHouse,
    LucideLandmark,
    LucideLayoutDashboard,
    LucideLogOut,
    LucideMenu,
    LucideSearch,
    LucideStar,
    LucideTarget,
    LucideUserRound,
    LucideX,
  ],
  templateUrl: './shell.component.html',
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly unread = inject(UnreadNotificationsStore);
  private readonly router = inject(Router);
  /** Mobile drawer. */
  protected readonly menuOpen = signal(false);
  /** Desktop: sidebar hidden to give the content the full width. */
  protected readonly sidebarHidden = signal(readFlag(COLLAPSE_KEY));
  /** Sidebar sections folded by the user. */
  protected readonly folded = signal<Set<string>>(new Set());
  protected globalQuery = '';
  private readonly globalSearch = viewChild<ElementRef<HTMLInputElement>>('globalSearch');
  protected readonly shortcutLabel = /Mac|iPhone|iPad/.test(navigator.platform ?? '') ? '⌘ K' : 'Ctrl K';

  /** Ctrl+K / ⌘K focuses the top-bar search (as the hint in the field says). */
  @HostListener('document:keydown', ['$event'])
  protected onKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
      const input = this.globalSearch()?.nativeElement;
      if (input && input.offsetParent !== null) {
        e.preventDefault();
        input.focus();
        input.select();
      }
    }
  }
  protected readonly year = new Date().getFullYear();

  protected readonly initials = computed(() =>
    (this.auth.user()?.fullName ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0]!.toUpperCase())
      .join(''),
  );
  protected readonly roadmap = ['assistant'] as const;
  protected readonly orgOptions = computed<SelectOption[]>(() =>
    this.org.memberships().map((m) => ({ value: m.id, label: m.name })),
  );

  protected readonly sections: NavSection[] = [
    {
      id: 'work',
      key: 'nav.workspace',
      items: [
        { path: '/', icon: 'dashboard', key: 'nav.dashboard', exact: true, query: null },
        { path: '/search', icon: 'search', key: 'nav.search', exact: false, query: { status: 'OPEN' } },
        { path: '/matches', icon: 'target', key: 'nav.matches', exact: false, query: null },
        { path: '/watchlist', icon: 'star', key: 'nav.watchlist', exact: false, query: null },
        { path: '/saved-searches', icon: 'bookmark', key: 'nav.savedSearches', exact: false, query: null },
        { path: '/notifications', icon: 'bell', key: 'nav.notifications', exact: false, query: null },
      ],
    },
    {
      id: 'org',
      key: 'nav.organizationSection',
      items: [
        { path: '/company', icon: 'briefcase', key: 'nav.company', exact: false, query: null },
        { path: '/organization', icon: 'building', key: 'nav.organization', exact: false, query: null },
        { path: '/billing', icon: 'card', key: 'nav.billing', exact: false, query: null },
      ],
    },
    {
      id: 'account',
      key: 'nav.accountSection',
      items: [
        { path: '/account', icon: 'user', key: 'nav.account', exact: false, query: null },
        { path: '/status', icon: 'activity', key: 'nav.systemStatus', exact: false, query: null },
      ],
    },
  ];

  /** Breadcrumbs of the deepest active route. */
  protected readonly crumbs = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => crumbsOf(this.router.routerState.snapshot.root)),
    ),
    { initialValue: crumbsOf(this.router.routerState.snapshot.root) },
  );

  protected toggleMenu(): void {
    this.menuOpen.update((open) => !open);
  }

  protected closeMenu(): void {
    this.menuOpen.set(false);
  }

  /** One button: opens the drawer on small screens, hides/shows the sidebar on desktop. */
  protected toggleSidebar(): void {
    if (window.matchMedia('(min-width: 1024px)').matches) {
      this.sidebarHidden.update((v) => !v);
      writeFlag(COLLAPSE_KEY, this.sidebarHidden());
    } else {
      this.toggleMenu();
    }
  }

  protected toggleSection(id: string): void {
    this.folded.update((set) => {
      const next = new Set(set);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
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

function crumbsOf(root: ActivatedRouteSnapshot): Crumb[] {
  let r: ActivatedRouteSnapshot = root;
  while (r.firstChild) {
    r = r.firstChild;
  }
  const out: Crumb[] = [];
  const section = r.data['section'] as string | undefined;
  const parent = r.data['parent'] as Crumb | undefined;
  const crumb = r.data['crumb'] as string | undefined;
  if (section) {
    out.push({ key: section });
  }
  if (parent) {
    out.push(parent);
  }
  if (crumb) {
    out.push({ key: crumb });
  }
  return out;
}

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? '1' : '0');
  } catch {
    // storage unavailable – the choice lasts for this session
  }
}
