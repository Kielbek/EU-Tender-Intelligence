import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
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
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
      },
      {
        path: 'search',
        loadComponent: () => import('./features/search/search.page').then((m) => m.SearchPage),
      },
      {
        path: 'tenders/:id',
        loadComponent: () => import('./features/tender/tender-detail.page').then((m) => m.TenderDetailPage),
      },
      {
        path: 'watchlist',
        loadComponent: () => import('./features/watchlist/watchlist.page').then((m) => m.WatchlistPage),
      },
      {
        path: 'matches',
        loadComponent: () => import('./features/matching/matches.page').then((m) => m.MatchesPage),
      },
      {
        path: 'company',
        loadComponent: () => import('./features/company/company-profile.page').then((m) => m.CompanyProfilePage),
      },
      {
        path: 'saved-searches',
        loadComponent: () =>
          import('./features/saved-searches/saved-searches.page').then((m) => m.SavedSearchesPage),
      },
      {
        path: 'status',
        loadComponent: () =>
          import('./features/system-status/system-status.page').then((m) => m.SystemStatusPage),
      },
      {
        path: 'organization',
        loadComponent: () => import('./features/organization/organization.page').then((m) => m.OrganizationPage),
      },
      {
        path: 'account',
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
