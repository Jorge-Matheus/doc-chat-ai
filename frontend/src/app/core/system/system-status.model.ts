/** Mirrors the response of GET /api/system/status. */
export type ComponentStatus = 'UP' | 'DOWN' | 'UNKNOWN';

export interface SystemStatus {
  readonly status: ComponentStatus;
  readonly database: ComponentStatus;
  readonly vectorExtension: ComponentStatus;
  readonly ai: ComponentStatus;
}
