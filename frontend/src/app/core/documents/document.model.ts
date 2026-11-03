/** Keep in sync with spring.servlet.multipart.max-file-size in the backend. */
export const MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024;

export type DocumentStatus = 'PROCESSING' | 'READY' | 'FAILED';

export type DocumentFailureReason =
  | 'NOT_READABLE'
  | 'PROTECTED'
  | 'NO_EXTRACTABLE_TEXT'
  | 'TOO_MANY_PAGES'
  | 'PROCESSING_UNAVAILABLE'
  | 'EMBEDDING_UNAVAILABLE'
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

/** Mirrors DocumentPage in the backend: the text extracted from one page. */
export interface DocumentPage {
  readonly documentId: string;
  readonly filename: string;
  readonly pageNumber: number;
  readonly pageCount: number;
  readonly text: string;
}

export interface DocumentList {
  readonly items: readonly UserDocument[];
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
}
