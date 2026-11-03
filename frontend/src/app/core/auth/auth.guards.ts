import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

const DEFAULT_SIGNED_IN_URL = '/documents';

/** The app initializer has already asked the backend who is signed in when these run. */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(AuthService).isSignedIn()) {
    return true;
  }
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export const guestGuard: CanActivateFn = () => {
  if (!inject(AuthService).isSignedIn()) {
    return true;
  }
  return inject(Router).createUrlTree([DEFAULT_SIGNED_IN_URL]);
};

/** Only follows paths inside the application, so a crafted link cannot redirect elsewhere. */
export function safeReturnUrl(returnUrl: string | null): string {
  const isInternal = returnUrl?.startsWith('/') && !/^\/[/\\]/.test(returnUrl);
  return isInternal ? returnUrl! : DEFAULT_SIGNED_IN_URL;
}
