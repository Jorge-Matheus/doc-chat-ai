import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { vi } from 'vitest';
import { AuthService } from '../../../core/auth/auth.service';
import { Register } from './register';

describe('Register', () => {
  let fixture: ComponentFixture<Register>;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;
  const register = vi.fn();

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;

  const submit = async (email: string, password: string): Promise<void> => {
    const emailInput = element().querySelector<HTMLInputElement>('input[type="email"]')!;
    const passwordInput = element().querySelector<HTMLInputElement>('input[type="password"]')!;
    emailInput.value = email;
    emailInput.dispatchEvent(new Event('input'));
    passwordInput.value = password;
    passwordInput.dispatchEvent(new Event('input'));
    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  };

  beforeEach(async () => {
    register.mockReset();
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: { register } }],
    });
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(Register);
    await fixture.whenStable();
  });

  it('requires the minimum password length before contacting the backend', async () => {
    await submit('user@example.com', 'eleven char');

    expect(register).not.toHaveBeenCalled();
    expect(element().textContent).toContain('Use at least 12 characters.');
  });

  it('creates the account and opens the documents page', async () => {
    register.mockReturnValue(of(undefined));

    await submit('user@example.com', 'twelve chars');

    expect(register).toHaveBeenCalledWith('user@example.com', 'twelve chars');
    expect(navigateByUrl).toHaveBeenCalledWith('/documents');
  });

  it('shows the reason when the email is already registered', async () => {
    register.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { detail: 'This email is already registered.' },
          }),
      ),
    );

    await submit('user@example.com', 'twelve chars');

    expect(element().querySelector('[data-testid="form-error"]')?.textContent).toContain(
      'This email is already registered.',
    );
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('shows the password rule the backend applies in bytes', async () => {
    register.mockReturnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: {
              errors: [{ field: 'password', message: 'Use no more than 72 bytes.' }],
            },
          }),
      ),
    );

    await submit('user@example.com', 'é'.repeat(37));

    expect(element().querySelector('[data-testid="form-error"]')?.textContent).toContain(
      'Use no more than 72 bytes.',
    );
  });
});
