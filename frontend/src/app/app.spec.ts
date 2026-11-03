import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { App } from './app';
import { AuthService } from './core/auth/auth.service';

const ACCOUNT = { id: '1', email: 'user@example.com' };

describe('App', () => {
  let fixture: ComponentFixture<App>;
  let auth: AuthService;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const headerButton = (label: string): HTMLButtonElement | undefined =>
    Array.from(element().querySelectorAll('header button')).find(
      (button) => button.textContent?.trim() === label,
    ) as HTMLButtonElement | undefined;

  const signInAs = async (): Promise<void> => {
    auth.refresh().subscribe();
    TestBed.inject(HttpTestingController).expectOne('/api/auth/me').flush(ACCOUNT);
    await fixture.whenStable();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    fixture = TestBed.createComponent(App);
    await fixture.whenStable();
  });

  it('renders the product name and the router outlet', () => {
    expect(element().querySelector('header')?.textContent).toContain('DocChat AI');
    expect(element().querySelector('main router-outlet')).not.toBeNull();
  });

  it('offers signing in to anonymous visitors', () => {
    const links = Array.from(element().querySelectorAll('header a')).map((link) =>
      link.getAttribute('href'),
    );

    expect(links).toEqual(['/', '/login', '/register']);
    expect(element().querySelector('[data-testid="user-email"]')).toBeNull();
  });

  it('shows the user and signing out once signed in', async () => {
    await signInAs();

    expect(element().querySelector('[data-testid="user-email"]')?.textContent).toContain(
      ACCOUNT.email,
    );
    expect(headerButton('Sign out')).toBeDefined();
    expect(element().querySelector('header a[href="/login"]')).toBeNull();
  });

  it('leads a signed-in user to the questions and the documents, and shows who is signed in', async () => {
    await signInAs();

    const links = Array.from(element().querySelectorAll('header nav a')).map((link) =>
      link.getAttribute('href'),
    );
    expect(links).toEqual(['/ask', '/documents']);
    expect(element().querySelector('.avatar')?.textContent?.trim()).toBe('U');
  });

  it('tells the user when signing out fails', async () => {
    await signInAs();
    vi.spyOn(auth, 'signOut').mockReturnValue(throwError(() => new Error('offline')));

    headerButton('Sign out')?.click();
    await fixture.whenStable();

    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Could not sign out');
  });

  it('removes the failure message after signing out works', async () => {
    await signInAs();
    const signOut = vi.spyOn(auth, 'signOut');
    signOut.mockReturnValue(throwError(() => new Error('offline')));
    headerButton('Sign out')?.click();
    await fixture.whenStable();

    signOut.mockReturnValue(of(undefined));
    headerButton('Sign out')?.click();
    await fixture.whenStable();

    expect(element().querySelector('[role="alert"]')).toBeNull();
  });
});
