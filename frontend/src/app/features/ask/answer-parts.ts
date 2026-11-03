import { TurnCitation } from '../../core/conversations/conversation.model';

export type AnswerPart =
  | { readonly text: string; readonly citation?: undefined }
  | { readonly citation: TurnCitation; readonly text?: undefined };

/**
 * Splits an answer at its citation markers, so each can be displayed as a link to its source. A
 * number in brackets that is not one of the citations, such as a year, stays as text.
 */
export function answerParts(text: string, citations: readonly TurnCitation[]): readonly AnswerPart[] {
  const parts: AnswerPart[] = [];
  let position = 0;
  for (const marker of text.matchAll(/\[(\d+)\]/g)) {
    const citation = citations.find((candidate) => candidate.number === Number(marker[1]));
    if (citation) {
      if (marker.index > position) {
        parts.push({ text: text.slice(position, marker.index) });
      }
      parts.push({ citation });
      position = marker.index + marker[0].length;
    }
  }
  if (position < text.length) {
    parts.push({ text: text.slice(position) });
  }
  return parts;
}
