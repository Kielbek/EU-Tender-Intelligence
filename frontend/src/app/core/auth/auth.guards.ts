import { inject } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CanActivateFn, Router } from '@angular/router';
import { filter, map, take } from 'rxjs';
import { AuthService } from './auth.service';

/** Waits until the initial silent refresh has settled. */
function whenKnown() {
  const auth = inject(AuthService);
  return toObservable(auth.status).pipe(
    filter((s) => s !== 'unknown'),
    take(1),
  );
}

export const authGuard: CanActivateFn = (_route, state) => {
  const router = inject(Router);
  return whenKnown().pipe(
    map((s) =>
      s === 'authenticated' ? true : router.createUrlTree(['/auth/login'], { queryParams: { returnUrl: state.url } }),
    ),
  );
};

export const guestGuard: CanActivateFn = () => {
  const router = inject(Router);
  return whenKnown().pipe(map((s) => (s === 'anonymous' ? true : router.createUrlTree(['/']))));
};

export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return whenKnown().pipe(map(() => (auth.isAdmin() ? true : router.createUrlTree(['/']))));
};
