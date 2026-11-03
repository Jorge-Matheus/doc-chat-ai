import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Login } from './login';

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;
  const signIn = vi.fn();

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const formError = (): string | undefined =>
    element().querySelector('[data-testid="form-error"]')?.textContent?.trim();

  const type = (selector: string, value: string): void => {
    const input = element().querySelector<HTMLInputElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  };

  const fillAndSubmit = async (): Promise<void> => {
    type('input[type="email"]', 'user@example.com');
    type('input[type="password"]', 'the password');
    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  };

  const setUp = async (returnUrl: string | null = null): Promise<void> => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: { signIn } },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: { queryParamMap: convertToParamMap(returnUrl ? { returnUrl } : {}) },
          },
        },
      ],
    });
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Login);
    await fixture.whenStable();
  };

  beforeEach(() => {
    signIn.mockReset();
  });

  it('does not contact the backend while the form is invalid', async () => {
    await setUp();

    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(signIn).not.toHaveBeenCalled();
    expect(element().textContent).toContain('Enter a valid email.');
  });

  it('signs in and opens the documents page', async () => {
    await setUp();
    signIn.mockReturnValue(of(undefined));

    await fillAndSubmit();

    expect(signIn).toHaveBeenCalledWith('user@example.com', 'the password');
    expect(navigateByUrl).toHaveBeenCalledWith('/documents');
  });

  it('returns to the page the user was trying to open', async () => {
    await setUp('/documents?page=2');
    signIn.mockReturnValue(of(undefined));

    await fillAndSubmit();

    expect(navigateByUrl).toHaveBeenCalledWith('/documents?page=2');
  });

  it('does not follow a return address outside the application', async () => {
    await setUp('//evil.example');
    signIn.mockReturnValue(of(undefined));

    await fillAndSubmit();

    expect(navigateByUrl).toHaveBeenCalledWith('/documents');
  });

  it('shows why the login was refused and lets the user try again', async () => {
    await setUp();
    signIn.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 401,
            error: { detail: 'The email or password is incorrect.' },
          }),
      ),
    );

    await fillAndSubmit();

    expect(formError()).toBe('The email or password is incorrect.');
    expect(navigateByUrl).not.toHaveBeenCalled();
    expect(element().querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(
      false,
    );
  });

  it('disables the button while waiting, so the form cannot be sent twice', async () => {
    await setUp();
    signIn.mockReturnValue(new Subject<void>());

    await fillAndSubmit();

    const button = element().querySelector<HTMLButtonElement>('button[type="submit"]');
    expect(button?.disabled).toBe(true);
    expect(button?.textContent).toContain('Signing in...');
  });
});
