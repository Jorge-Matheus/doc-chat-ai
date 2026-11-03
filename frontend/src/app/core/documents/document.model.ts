/** Keep in sync with spring.servlet.multipart.max-file-size in the backend. */
export const MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024;

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

export type DocumentFailureReason =
  | 'NOT_READABLE'
  | 'PROTECTED'
  | 'NO_EXTRACTABLE_TEXT'
  | 'TOO_MANY_PAGES'
  | 'PROCESSING_UNAVAILABLE'
  | 'INTERNAL_ERROR';

/** Mirrors DocumentResponse in the backend. Named to avoid the browser's global Document type. */
export interface UserDocument {
  readonly id: string;
  readonly filename: string;
  readonly sizeBytes: number;
  readonly status: DocumentStatus;
  readonly failureReason: DocumentFailureReason | null;
  readonly pageCount: number | null;
  readonly createdAt: string;
}

export interface DocumentList {
  readonly items: readonly UserDocument[];
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
}
