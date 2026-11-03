import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SystemStatus } from './system-status.model';

@Injectable({ providedIn: 'root' })
export class SystemStatusService {
  private readonly http = inject(HttpClient);

  getStatus(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>('/api/system/status');
  }
}
