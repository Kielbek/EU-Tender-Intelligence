import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const AUTH_ENDPOINTS = '/api/v1/auth/';

/**
 * Adds the Bearer token to API calls. On 401 it performs one silent refresh and retries the request once; if that
 * fails the user is sent to the login page.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || req.url.startsWith(AUTH_ENDPOINTS)) {
    return next(req);
  }
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(withToken(req, auth.token())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        switchMap((ok) => {
          if (!ok) {
            void router.navigate(['/auth/login'], { queryParams: { returnUrl: router.url } });
            return throwError(() => error);
          }
          return next(withToken(req, auth.token()));
        }),
      );
    }),
  );
};

function withToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}
