/** Mirrors the response of POST /api/questions. */
export interface Citation {
  readonly number: number;
  readonly documentId: string;
  readonly filename: string;
  readonly pageNumber: number;
  readonly excerpt: string;
}

export interface Answer {
  /** False when the documents do not contain the answer. */
  readonly answered: boolean;
  readonly text: string | null;
  readonly citations: readonly Citation[];
}
