/** Mirrors TurnCitation in the backend. The id and the excerpt are null once the document is deleted. */
export interface TurnCitation {
  readonly number: number;
  readonly documentId: string | null;
  readonly filename: string;
  readonly pageNumber: number;
  readonly excerpt: string | null;
}

/** Mirrors Turn in the backend: a question and its answer. */
export interface Turn {
  readonly id: number;
  readonly question: string;
  /** What was searched for, when it differs from the question as it was asked. */
  readonly interpretedQuestion: string | null;
  /** False when the documents do not contain the answer. */
  readonly answered: boolean;
  readonly text: string | null;
  readonly citations: readonly TurnCitation[];
  readonly createdAt: string;
}

/** Mirrors Conversation in the backend. */
export interface Conversation {
  readonly id: string;
  readonly title: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface ConversationDetails extends Conversation {
  readonly turns: readonly Turn[];
}

export interface ConversationList {
  readonly items: readonly Conversation[];
  readonly page: number;
  readonly size: number;
  readonly totalItems: number;
}
