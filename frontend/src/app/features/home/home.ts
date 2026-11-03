import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ComponentStatus, SystemStatus } from '../../core/system/system-status.model';
import { SystemStatusService } from '../../core/system/system-status.service';

type StatusView =
  | { readonly state: 'loading' }
  | { readonly state: 'unreachable' }
  | { readonly state: 'reachable'; readonly rows: readonly StatusRow[]; readonly healthy: boolean };

interface StatusRow {
  readonly label: string;
  readonly status: ComponentStatus;
}

@Component({
  selector: 'app-home',
  imports: [MatButtonModule, RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Home {
  private readonly systemStatus = inject(SystemStatusService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly auth = inject(AuthService);
  protected readonly view = signal<StatusView>({ state: 'loading' });
  protected readonly statusLabels: Record<ComponentStatus, string> = {
    UP: 'Up',
    DOWN: 'Down',
    UNKNOWN: 'Unknown',
  };

  constructor() {
    this.refresh();
  }

  protected refresh(): void {
    this.view.set({ state: 'loading' });
    this.systemStatus
      .getStatus()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (status) => this.view.set(toReachableView(status)),
        error: () => this.view.set({ state: 'unreachable' }),
      });
  }
}

function toReachableView(status: SystemStatus): StatusView {
  return {
    state: 'reachable',
    healthy: status.status === 'UP',
    rows: [
      // A response proves the backend is running, whatever the state of its dependencies.
      { label: 'Backend', status: 'UP' },
      { label: 'Database', status: status.database },
      { label: 'pgvector extension', status: status.vectorExtension },
      { label: 'AI models', status: status.ai },
    ],
  };
}
