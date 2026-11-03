import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { SystemStatus } from '../../core/system/system-status.model';
import { Home } from './home';

const STATUS_URL = '/api/system/status';

describe('Home', () => {
  let fixture: ComponentFixture<Home>;
  let http: HttpTestingController;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const testId = (id: string): Element | null => element().querySelector(`[data-testid="${id}"]`);
  const rows = (): string[] =>
    Array.from(element().querySelectorAll('[data-testid="status-row"]')).map((row) =>
      (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );

  const respondWith = async (status: SystemStatus): Promise<void> => {
    http.expectOne(STATUS_URL).flush(status);
    await fixture.whenStable();
  };

  const failWith = async (statusCode: number): Promise<void> => {
    http
      .expectOne(STATUS_URL)
      .flush('unavailable', { status: statusCode, statusText: 'Unavailable' });
    await fixture.whenStable();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Home);
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
  });

  it('offers to create an account or sign in when no one is signed in', async () => {
    await respondWith({ status: 'UP', database: 'UP', vectorExtension: 'UP', ai: 'UP' });

    const links = Array.from(element().querySelectorAll('.actions a')).map((link) =>
      link.getAttribute('href'),
    );
    expect(links).toEqual(['/register', '/login']);
  });

  it('leads a signed-in user to their questions and documents', async () => {
    const auth = TestBed.inject(AuthService);
    const refresh = firstValueFrom(auth.refresh());
    http.expectOne('/api/auth/me').flush({ id: 'user-1', email: 'user@example.com' });
    await refresh;
    await respondWith({ status: 'UP', database: 'UP', vectorExtension: 'UP', ai: 'UP' });

    const links = Array.from(element().querySelectorAll('.actions a')).map((link) =>
      link.getAttribute('href'),
    );
    expect(links).toEqual(['/ask', '/documents']);
  });

  it('shows the loading state until the backend answers', async () => {
    expect(testId('status-loading')).not.toBeNull();
    expect(rows()).toEqual([]);

    await respondWith({ status: 'UP', database: 'UP', vectorExtension: 'UP', ai: 'UP' });

    expect(testId('status-loading')).toBeNull();
  });

  it('shows every component as up when the system is healthy', async () => {
    await respondWith({ status: 'UP', database: 'UP', vectorExtension: 'UP', ai: 'UP' });

    expect(rows()).toEqual(['Backend Up', 'Database Up', 'pgvector extension Up', 'AI models Up']);
    expect(testId('status-degraded')).toBeNull();
    expect(testId('status-unreachable')).toBeNull();
  });

  it('reports the real state when the database is down', async () => {
    await respondWith({ status: 'DOWN', database: 'DOWN', vectorExtension: 'UNKNOWN', ai: 'UP' });

    expect(rows()).toEqual(['Backend Up', 'Database Down', 'pgvector extension Unknown', 'AI models Up']);
    expect(testId('status-degraded')).not.toBeNull();
  });

  it('does not show any status rows when the backend cannot be reached', async () => {
    await failWith(503);

    expect(testId('status-unreachable')).not.toBeNull();
    expect(rows()).toEqual([]);
  });

  it('recovers when the user tries again', async () => {
    await failWith(503);

    element().querySelector('button')?.click();
    await fixture.whenStable();
    expect(testId('status-loading')).not.toBeNull();

    await respondWith({ status: 'UP', database: 'UP', vectorExtension: 'UP', ai: 'UP' });

    expect(testId('status-unreachable')).toBeNull();
    expect(rows()).toHaveLength(4);
  });
});
