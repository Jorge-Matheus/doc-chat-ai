import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { authGuard, guestGuard, safeReturnUrl } from './auth.guards';
import { AuthService } from './auth.service';

describe('route guards', () => {
  const run = (guard: CanActivateFn, url = '/documents') =>
    TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot),
    );

  const signIn = (): void => {
    TestBed.inject(AuthService).refresh().subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne('/api/auth/me')
      .flush({ id: '1', email: 'user@example.com' });
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  it('sends anonymous visitors to the login page, remembering where they were going', () => {
    const result = run(authGuard, '/documents?page=2') as UrlTree;

    expect(result.toString()).toBe('/login?returnUrl=%2Fdocuments%3Fpage%3D2');
  });

  it('lets signed-in users through', () => {
    signIn();

    expect(run(authGuard)).toBe(true);
  });

  it('shows the login page to anonymous visitors', () => {
    expect(run(guestGuard)).toBe(true);
  });

  it('sends signed-in users away from the login page', () => {
    signIn();

    expect((run(guestGuard) as UrlTree).toString()).toBe('/documents');
  });
});

describe('safeReturnUrl', () => {
  it('keeps paths inside the application', () => {
    expect(safeReturnUrl('/documents?page=2')).toBe('/documents?page=2');
  });

  it.each([
    ['nothing', null],
    ['an absolute address', 'https://evil.example'],
    ['an address without a scheme', '//evil.example'],
    ['a backslash trick', '/\\evil.example'],
    ['a relative path', 'documents'],
  ])('replaces %s with the default page', (_description, value) => {
    expect(safeReturnUrl(value)).toBe('/documents');
  });
});
