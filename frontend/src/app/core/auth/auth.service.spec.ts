import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

const ACCOUNT = { id: '1', email: 'user@example.com' };

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('starts signed out', () => {
    expect(auth.user()).toBeNull();
    expect(auth.isSignedIn()).toBe(false);
  });

  it('learns who is signed in from the backend', () => {
    auth.refresh().subscribe();
    http.expectOne('/api/auth/me').flush(ACCOUNT);

    expect(auth.user()).toEqual(ACCOUNT);
    expect(auth.isSignedIn()).toBe(true);
  });

  it('treats a 401 as signed out without failing', () => {
    let failed = false;
    auth.refresh().subscribe({ error: () => (failed = true) });
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.user()).toBeNull();
    expect(failed).toBe(false);
  });

  it('treats an unavailable backend as signed out, so the application can still start', () => {
    let failed = false;
    auth.refresh().subscribe({ error: () => (failed = true) });
    http.expectOne('/api/auth/me').error(new ProgressEvent('error'));

    expect(auth.user()).toBeNull();
    expect(failed).toBe(false);
  });

  it('signs in with a form that encodes special characters', () => {
    auth.signIn('user+tag@example.com', 'p&ss=w+rd é').subscribe();

    const login = http.expectOne('/api/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.serializeBody()).toBe(
      'email=user%2Btag%40example.com&password=p%26ss%3Dw%2Brd+%C3%A9',
    );
    expect(login.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    login.flush(null, { status: 204, statusText: 'No Content' });

    http.expectOne('/api/auth/me').flush(ACCOUNT);
    expect(auth.user()).toEqual(ACCOUNT);
  });

  it('stays signed out and reports the failure when the login is refused', () => {
    let status = 0;
    auth.signIn('user@example.com', 'wrong').subscribe({ error: (error) => (status = error.status) });
    http.expectOne('/api/auth/login').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(status).toBe(401);
    expect(auth.user()).toBeNull();
  });

  it('signs in right after registering', () => {
    auth.register('user@example.com', 'a long enough password').subscribe();

    const register = http.expectOne('/api/auth/register');
    expect(register.request.body).toEqual({
      email: 'user@example.com',
      password: 'a long enough password',
    });
    register.flush(ACCOUNT, { status: 201, statusText: 'Created' });

    http.expectOne('/api/auth/login').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/me').flush(ACCOUNT);
    expect(auth.user()).toEqual(ACCOUNT);
  });

  it('does not try to sign in when registering fails', () => {
    let status = 0;
    auth.register('user@example.com', 'a long enough password').subscribe({
      error: (error) => (status = error.status),
    });
    http.expectOne('/api/auth/register').flush(null, { status: 409, statusText: 'Conflict' });

    expect(status).toBe(409);
    http.expectNone('/api/auth/login');
  });

  it('asks the backend again after signing out, which also renews the CSRF cookie', () => {
    auth.refresh().subscribe();
    http.expectOne('/api/auth/me').flush(ACCOUNT);

    auth.signOut().subscribe();
    http.expectOne('/api/auth/logout').flush(null, { status: 204, statusText: 'No Content' });
    http.expectOne('/api/auth/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.user()).toBeNull();
  });

  it('stays signed in when signing out fails', () => {
    auth.refresh().subscribe();
    http.expectOne('/api/auth/me').flush(ACCOUNT);

    let failed = false;
    auth.signOut().subscribe({ error: () => (failed = true) });
    http.expectOne('/api/auth/logout').flush(null, { status: 500, statusText: 'Server Error' });

    expect(failed).toBe(true);
    expect(auth.user()).toEqual(ACCOUNT);
  });
});
