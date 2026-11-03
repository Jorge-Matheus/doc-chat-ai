import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { vi } from 'vitest';
import { AuthService } from './auth.service';
import { sessionExpiredInterceptor } from './session-expired.interceptor';

describe('sessionExpiredInterceptor', () => {
  let client: HttpClient;
  let http: HttpTestingController;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  const request = (url: string, status: number): void => {
    client.get(url).subscribe({ error: () => undefined });
    http.expectOne(url).flush(null, { status, statusText: 'Error' });
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionExpiredInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    auth.refresh().subscribe();
    http.expectOne('/api/auth/me').flush({ id: '1', email: 'user@example.com' });
    navigate.mockClear();
  });

  it('signs the user out and opens the login page when a session ends', () => {
    request('/api/documents', 401);

    expect(auth.user()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/' } });
  });

  it('leaves the 401 answers of the authentication endpoints to their callers', () => {
    request('/api/auth/login', 401);

    expect(auth.user()).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignores other errors', () => {
    request('/api/documents', 500);

    expect(auth.user()).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });
});
