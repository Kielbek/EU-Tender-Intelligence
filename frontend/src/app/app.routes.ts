import { Routes } from '@angular/router';
import { authGuard, guestGuard, setupGuard } from './core/auth/auth.guards';
import { ShellComponent } from './core/layout/shell.component';
import { AuthLayoutComponent } from './features/auth/auth-layout.component';

export const routes: Routes = [
  {
    path: 'auth',
    component: AuthLayoutComponent,
    children: [
      {
        path: 'login',
        canActivate: [guestGuard],
        loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
      },
      {
        path: 'register',
        canActivate: [guestGuard],
        loadComponent: () => import('./features/auth/register.page').then((m) => m.RegisterPage),
      },
      {
        path: 'verify-email',
        loadComponent: () => import('./features/auth/verify-email.page').then((m) => m.VerifyEmailPage),
      },
      {
        path: 'forgot-password',
        loadComponent: () => import('./features/auth/forgot-password.page').then((m) => m.ForgotPasswordPage),
      },
      {
        path: 'reset-password',
        loadComponent: () => import('./features/auth/reset-password.page').then((m) => m.ResetPasswordPage),
      },
      { path: '', pathMatch: 'full', redirectTo: 'login' },
    ],
  },
  {
    path: 'setup',
    canActivate: [authGuard],
    loadComponent: () => import('./features/setup/setup.page').then((m) => m.SetupPage),
  },
  {
    path: 'legal/terms',
    data: { doc: 'terms' },
    loadComponent: () => import('./features/legal/legal.page').then((m) => m.LegalPage),
  },
  {
    path: 'legal/privacy',
    data: { doc: 'privacy' },
    loadComponent: () => import('./features/legal/legal.page').then((m) => m.LegalPage),
  },
  {
    path: 'unsubscribe',
    loadComponent: () => import('./features/notifications/unsubscribe.page').then((m) => m.UnsubscribePage),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        canActivate: [setupGuard],
        data: { section: 'nav.workspace', crumb: 'nav.dashboard' },
        loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'search',
        data: { section: 'nav.workspace', crumb: 'nav.search' },
        loadComponent: () => import('./features/search/search.page').then((m) => m.SearchPage),
      },
      {
        path: 'tenders/:id',
        data: { section: 'nav.workspace', parent: { key: 'nav.search', link: '/search' }, crumb: 'tender.crumb' },
        loadComponent: () => import('./features/tender/tender-detail.page').then((m) => m.TenderDetailPage),
      },
      {
        path: 'watchlist',
        data: { section: 'nav.workspace', crumb: 'nav.watchlist' },
        loadComponent: () => import('./features/watchlist/watchlist.page').then((m) => m.WatchlistPage),
      },
      {
        path: 'matches',
        data: { section: 'nav.workspace', crumb: 'nav.matches' },
        loadComponent: () => import('./features/matching/matches.page').then((m) => m.MatchesPage),
      },
      {
        path: 'company',
        data: { section: 'nav.organizationSection', crumb: 'nav.company' },
        loadComponent: () => import('./features/company/company-profile.page').then((m) => m.CompanyProfilePage),
      },
      {
        path: 'saved-searches',
        data: { section: 'nav.workspace', crumb: 'nav.savedSearches' },
        loadComponent: () =>
          import('./features/saved-searches/saved-searches.page').then((m) => m.SavedSearchesPage),
      },
      {
        path: 'competitors',
        data: { section: 'nav.workspace', crumb: 'nav.competitors' },
        loadComponent: () => import('./features/market/competitors.page').then((m) => m.CompetitorsPage),
      },
      {
        path: 'authorities/:id',
        data: { section: 'nav.workspace', parent: { key: 'nav.competitors', link: '/competitors' }, crumb: 'market.authority.crumb' },
        loadComponent: () => import('./features/market/authority.page').then((m) => m.AuthorityPage),
      },
      {
        path: 'companies/:id',
        data: { section: 'nav.workspace', parent: { key: 'nav.competitors', link: '/competitors' }, crumb: 'market.company.crumb' },
        loadComponent: () => import('./features/market/company.page').then((m) => m.CompanyPage),
      },
      {
        path: 'notifications',
        data: { section: 'nav.workspace', crumb: 'nav.notifications' },
        loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
      },
      {
        path: 'billing',
        data: { section: 'nav.organizationSection', crumb: 'nav.billing' },
        loadComponent: () => import('./features/billing/billing.page').then((m) => m.BillingPage),
      },
      {
        path: 'status',
        data: { section: 'nav.accountSection', crumb: 'nav.systemStatus' },
        loadComponent: () =>
          import('./features/system-status/system-status.page').then((m) => m.SystemStatusPage),
      },
      {
        path: 'organization',
        data: { section: 'nav.organizationSection', crumb: 'nav.organization' },
        loadComponent: () => import('./features/organization/organization.page').then((m) => m.OrganizationPage),
      },
      {
        path: 'account',
        data: { section: 'nav.accountSection', crumb: 'nav.account' },
        loadComponent: () => import('./features/account/account.page').then((m) => m.AccountPage),
      },
      {
        path: 'invitations/accept',
        loadComponent: () =>
          import('./features/invitations/accept-invitation.page').then((m) => m.AcceptInvitationPage),
      },
      {
        path: '**',
        loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
      },
    ],
  },
];
