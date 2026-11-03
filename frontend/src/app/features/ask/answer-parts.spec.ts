import { TurnCitation } from '../../core/conversations/conversation.model';
import { answerParts } from './answer-parts';

const FIRST: TurnCitation = {
  number: 1,
  documentId: 'doc-1',
  filename: 'a.pdf',
  pageNumber: 1,
  excerpt: 'One',
};
const SECOND: TurnCitation = { ...FIRST, number: 2, pageNumber: 2 };

describe('answerParts', () => {
  it('splits the text at each citation', () => {
    expect(answerParts('Sixty days [1]. And more [2].', [FIRST, SECOND])).toEqual([
      { text: 'Sixty days ' },
      { citation: FIRST },
      { text: '. And more ' },
      { citation: SECOND },
      { text: '.' },
    ]);
  });

  it('handles markers next to each other and at the edges', () => {
    expect(answerParts('[1][2]', [FIRST, SECOND])).toEqual([
      { citation: FIRST },
      { citation: SECOND },
    ]);
  });

  it('keeps as text a number in brackets that is not a citation', () => {
    expect(answerParts('In [2020] it changed [1].', [FIRST])).toEqual([
      { text: 'In [2020] it changed ' },
      { citation: FIRST },
      { text: '.' },
    ]);
  });

  it('keeps the whole text when there are no citations', () => {
    expect(answerParts('Plain text [7]', [])).toEqual([{ text: 'Plain text [7]' }]);
  });
});
