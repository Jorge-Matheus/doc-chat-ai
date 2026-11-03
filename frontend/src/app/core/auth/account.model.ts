/** Mirrors the response of GET /api/auth/me. */
export interface Account {
  readonly id: string;
  readonly email: string;
}
