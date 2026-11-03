import { HttpErrorResponse } from '@angular/common/http';

/** The parts of the backend's RFC 9457 error body that are written for users. */
interface Problem {
  readonly detail?: string;
  readonly errors?: readonly { readonly message: string }[];
}

/**
 * Picks the message to show for a failed request. Responses that did not come from the backend,
 * such as a proxy's HTML error page, have no problem body and get the fallback.
 */
export function errorMessage(error: unknown, fallback: string): string {
  if (!(error instanceof HttpErrorResponse) || typeof error.error !== 'object' || !error.error) {
    return fallback;
  }
  const problem = error.error as Problem;
  if (problem.errors?.length) {
    return problem.errors.map((fieldError) => fieldError.message).join(' ');
  }
  return problem.detail ?? fallback;
}
