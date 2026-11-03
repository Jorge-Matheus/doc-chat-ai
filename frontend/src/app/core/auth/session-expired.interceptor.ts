import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { tap } from 'rxjs';
import { AuthService } from './auth.service';

/** Sends the user to the login page when the session ends while the application is open. */
export const sessionExpiredInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(request).pipe(
    tap({
      error: (error: unknown) => {
        // Under /api/auth/, a 401 is an expected answer: "not signed in" or "wrong password".
        const sessionEnded =
          error instanceof HttpErrorResponse &&
          error.status === 401 &&
          !request.url.startsWith('/api/auth/');
        if (sessionEnded) {
          auth.clearUser();
          void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
        }
      },
    }),
  );
};
