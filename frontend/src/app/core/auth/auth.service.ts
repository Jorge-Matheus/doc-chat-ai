import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';
import { Account } from './account.model';

/**
 * The session lives in an HttpOnly cookie that this code never sees. HttpClient sends the CSRF
 * token by itself, reading the XSRF-TOKEN cookie that the backend sets on any response.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly currentUser = signal<Account | null>(null);

  readonly user = this.currentUser.asReadonly();
  readonly isSignedIn = computed(() => this.currentUser() !== null);

  /** Finds out who is signed in. An unavailable backend counts as signed out, so it never fails. */
  refresh(): Observable<Account | null> {
    return this.http.get<Account>('/api/auth/me').pipe(
      catchError(() => of(null)),
      tap((account) => this.currentUser.set(account)),
    );
  }

  signIn(email: string, password: string): Observable<void> {
    // URLSearchParams encodes every special character, which matters for passwords. It is sent as
    // text with an explicit type, because HttpClient would label the object itself as JSON.
    const form = new URLSearchParams({ email, password }).toString();
    const headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    return this.http.post<void>('/api/auth/login', form, { headers }).pipe(
      switchMap(() => this.http.get<Account>('/api/auth/me')),
      tap((account) => this.currentUser.set(account)),
      map(() => undefined),
    );
  }

  register(email: string, password: string): Observable<void> {
    return this.http
      .post<Account>('/api/auth/register', { email, password })
      .pipe(switchMap(() => this.signIn(email, password)));
  }

  /** Refreshes afterwards because signing out removes the CSRF cookie the next request needs. */
  signOut(): Observable<void> {
    return this.http.post<void>('/api/auth/logout', null).pipe(
      switchMap(() => this.refresh()),
      map(() => undefined),
    );
  }

  /** For when the backend reports that the session has ended. */
  clearUser(): void {
    this.currentUser.set(null);
  }
}
