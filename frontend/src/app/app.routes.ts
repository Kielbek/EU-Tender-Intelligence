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
      { path: '', pathMatch: 'full', redirectTo: 'status' },
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
