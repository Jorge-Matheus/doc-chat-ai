import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { askUrl } from '../../app.routes';
import {
  Conversation,
  ConversationDetails,
  Turn,
} from '../../core/conversations/conversation.model';
import { UserDocument } from '../../core/documents/document.model';
import { Ask } from './ask';
import { SourceViewerDialog } from './source-viewer-dialog';

const DOCUMENTS_URL = '/api/documents?page=0&size=100';
const HISTORY_URL = '/api/conversations?page=0&size=20';
const CONVERSATIONS_URL = '/api/conversations';

function aDocument(overrides: Partial<UserDocument> = {}): UserDocument {
  return {
    id: 'doc-1',
    filename: 'contract.pdf',
    sizeBytes: 1024,
    status: 'READY',
    failureReason: null,
    pageCount: 3,
    createdAt: '2026-11-03T12:00:00Z',
    ...overrides,
  };
}

function aTurn(overrides: Partial<Turn> = {}): Turn {
  return {
    id: 1,
    question: 'What is the notice?',
    interpretedQuestion: null,
    answered: true,
    text: 'The notice is 60 days [1].',
    citations: [
      {
        number: 1,
        documentId: 'doc-1',
        filename: 'contract.pdf',
        pageNumber: 3,
        excerpt: 'Termination requires 60 days notice.',
      },
    ],
    createdAt: '2026-11-03T12:00:00Z',
    ...overrides,
  };
}

function aSummary(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1',
    title: 'What is the notice?',
    createdAt: '2026-11-03T12:00:00Z',
    updatedAt: '2026-11-03T12:00:00Z',
    ...overrides,
  };
}

function aConversation(overrides: Partial<ConversationDetails> = {}): ConversationDetails {
  return { ...aSummary(), turns: [aTurn()], ...overrides };
}

describe('Ask', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  const openDialog = vi.fn();

  const element = (): HTMLElement => harness.routeNativeElement as HTMLElement;
  const testId = (id: string): HTMLElement | null =>
    element().querySelector(`[data-testid="${id}"]`);
  const testIds = (id: string): HTMLElement[] =>
    Array.from(element().querySelectorAll<HTMLElement>(`[data-testid="${id}"]`));
  const stable = (): Promise<void> => harness.fixture.whenStable();

  const respondWithDocuments = async (items: readonly UserDocument[]): Promise<void> => {
    http.expectOne(DOCUMENTS_URL).flush({ items, page: 0, size: 100, totalItems: items.length });
    await stable();
  };

  const respondWithHistory = async (
    items: readonly Conversation[] = [],
    totalItems = items.length,
  ): Promise<void> => {
    http.expectOne(HISTORY_URL).flush({ items, page: 0, size: 20, totalItems });
    await stable();
  };

  const typeQuestion = (question: string): void => {
    const input = testId('question') as HTMLTextAreaElement;
    input.value = question;
    input.dispatchEvent(new Event('input'));
  };

  const askQuestion = async (question: string): Promise<void> => {
    typeQuestion(question);
    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await stable();
  };

  /** Opens a new conversation with one ready document. */
  const openNewConversation = async (): Promise<void> => {
    harness = await RouterTestingHarness.create('/ask');
    await respondWithDocuments([aDocument()]);
    await respondWithHistory();
  };

  /** Opens a stored conversation with one ready document. */
  const openConversation = async (conversation = aConversation()): Promise<void> => {
    harness = await RouterTestingHarness.create(`/ask/${conversation.id}`);
    await respondWithDocuments([aDocument()]);
    await respondWithHistory([aSummary({ id: conversation.id, title: conversation.title })]);
    http.expectOne(`${CONVERSATIONS_URL}/${conversation.id}`).flush(conversation);
    await stable();
  };

  beforeEach(() => {
    openDialog.mockReset();
    Element.prototype.scrollIntoView = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { matcher: askUrl, component: Ask },
          { path: 'documents', children: [] },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: openDialog } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  describe('a new conversation', () => {
    it('asks the user to upload a document when none is ready', async () => {
      harness = await RouterTestingHarness.create('/ask');
      await respondWithDocuments([
        aDocument({ status: 'PROCESSING' }),
        aDocument({ id: 'doc-2', status: 'FAILED' }),
      ]);
      await respondWithHistory();

      expect(testId('no-documents')?.querySelector('a')?.getAttribute('href')).toBe('/documents');
      expect(element().querySelector('form')).toBeNull();
    });

    it('reports when the documents cannot be loaded', async () => {
      harness = await RouterTestingHarness.create('/ask');
      http.expectOne(DOCUMENTS_URL).flush(null, { status: 500, statusText: 'Server Error' });
      await respondWithHistory();

      expect(testId('documents-error')).not.toBeNull();
      expect(element().querySelector('form')).toBeNull();
    });

    it('does not send a blank question', async () => {
      await openNewConversation();

      await askQuestion('   ');

      http.expectNone(CONVERSATIONS_URL);
      expect(element().textContent).toContain('Write your question first.');
    });

    it('removes the message about a blank question as soon as the user types', async () => {
      await openNewConversation();
      await askQuestion('   ');
      expect(element().textContent).toContain('Write your question first.');

      typeQuestion('What is');
      await stable();

      expect(element().textContent).not.toContain('Write your question first.');
    });

    it('shows the tips only until the conversation has something in it', async () => {
      await openNewConversation();
      expect(testId('ask-intro')).not.toBeNull();

      await askQuestion('What is the notice?');
      expect(testId('ask-intro')).toBeNull();

      http.expectOne(CONVERSATIONS_URL).flush(aConversation());
      await respondWithHistory([aSummary()]);
      expect(testId('ask-intro')).toBeNull();
    });

    it('asks about all documents by default and shows the question while waiting', async () => {
      await openNewConversation();

      await askQuestion('  What is the notice?  ');

      const request = http.expectOne(CONVERSATIONS_URL);
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({ question: 'What is the notice?', documentIds: [] });
      expect(testId('pending')?.textContent).toContain('What is the notice?');
      expect(element().textContent).toContain('Reading your documents...');
      expect((testId('question') as HTMLTextAreaElement).value).toBe('');
      expect(element().textContent).not.toContain('Write your question first.');

      request.flush(aConversation());
      await respondWithHistory([aSummary()]);

      expect(testId('pending')).toBeNull();
      expect(testId('turn-question')?.textContent).toContain('What is the notice?');
      expect(testId('answer')?.textContent).toContain('The notice is 60 days [1].');
    });

    it('moves to the address of the new conversation without loading it again', async () => {
      await openNewConversation();
      const page = harness.routeDebugElement?.componentInstance as Ask;
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');

      await askQuestion('What is the notice?');
      http.expectOne(CONVERSATIONS_URL).flush(aConversation());
      await respondWithHistory([aSummary()]);
      await stable();

      expect(navigate).toHaveBeenCalledWith(['/ask', 'conv-1'], { replaceUrl: true });
      expect(TestBed.inject(Router).url).toBe('/ask/conv-1');
      expect(harness.routeDebugElement?.componentInstance).toBe(page);
      http.expectNone(`${CONVERSATIONS_URL}/conv-1`);
      expect(testId('answer')).not.toBeNull();
      expect(element().querySelector('h1')?.textContent).toContain('What is the notice?');
    });

    it('gives the question back when it could not be answered', async () => {
      await openNewConversation();

      await askQuestion('What is the notice?');
      http
        .expectOne(CONVERSATIONS_URL)
        .flush(
          { detail: 'The AI service is not available right now.' },
          { status: 503, statusText: 'Service Unavailable' },
        );
      await stable();

      expect(testId('ask-error')?.textContent).toContain('The AI service is not available');
      expect((testId('question') as HTMLTextAreaElement).value).toBe('What is the notice?');
      expect(testId('pending')).toBeNull();
      expect(element().querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(
        false,
      );
      expect(TestBed.inject(Router).url).toBe('/ask');
    });

    it('sends only one question at a time', async () => {
      await openNewConversation();
      await askQuestion('First?');
      const first = http.expectOne(CONVERSATIONS_URL);

      await askQuestion('Second?');

      http.expectNone(CONVERSATIONS_URL);
      first.flush(aConversation());
      await respondWithHistory([aSummary()]);
    });

    it('sends the question with Enter and starts a new line with Shift+Enter', async () => {
      await openNewConversation();
      typeQuestion('What is the notice?');
      const input = testId('question') as HTMLTextAreaElement;

      const newLine = new KeyboardEvent('keydown', {
        key: 'Enter',
        shiftKey: true,
        cancelable: true,
      });
      input.dispatchEvent(newLine);
      await stable();
      expect(newLine.defaultPrevented).toBe(false);
      http.expectNone(CONVERSATIONS_URL);

      const send = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
      input.dispatchEvent(send);
      await stable();
      expect(send.defaultPrevented).toBe(true);
      http.expectOne(CONVERSATIONS_URL).flush(aConversation());
      await respondWithHistory([aSummary()]);
    });
  });

  describe('a stored conversation', () => {
    it('shows its questions and answers in order', async () => {
      await openConversation(
        aConversation({
          turns: [
            aTurn(),
            aTurn({
              id: 2,
              question: 'And the penalty?',
              interpretedQuestion: 'What is the penalty for termination?',
              text: 'It is 20% [1].',
            }),
          ],
        }),
      );

      expect(testIds('turn-question').map((node) => node.textContent?.trim())).toEqual([
        'What is the notice?',
        'And the penalty?',
      ]);
      expect(testIds('answer')[1].textContent).toContain('It is 20% [1].');
      expect(testId('interpreted')?.textContent).toContain('What is the penalty for termination?');
      expect(testIds('interpreted')).toHaveLength(1);
      expect(testId('conversation')?.getAttribute('aria-current')).toBe('page');
    });

    it('adds a follow-up to the same conversation and keeps the chosen documents', async () => {
      await openConversation();

      await askQuestion('And the penalty?');
      const request = http.expectOne(`${CONVERSATIONS_URL}/conv-1/turns`);
      expect(request.request.body).toEqual({ question: 'And the penalty?', documentIds: [] });
      request.flush(aTurn({ id: 2, question: 'And the penalty?', text: 'It is 20% [1].' }));
      await respondWithHistory([aSummary()]);

      expect(testIds('turn')).toHaveLength(2);
      expect(testIds('answer')[1].textContent).toContain('It is 20% [1].');
    });

    it('keeps the earlier answers and the question when a follow-up fails', async () => {
      await openConversation();

      await askQuestion('And the penalty?');
      http
        .expectOne(`${CONVERSATIONS_URL}/conv-1/turns`)
        .flush(null, { status: 500, statusText: 'Server Error' });
      await stable();

      expect(testIds('turn')).toHaveLength(1);
      expect(testId('ask-error')).not.toBeNull();
      expect((testId('question') as HTMLTextAreaElement).value).toBe('And the penalty?');
    });

    it('says so when the documents do not contain the answer', async () => {
      await openConversation(
        aConversation({ turns: [aTurn({ answered: false, text: null, citations: [] })] }),
      );

      expect(testId('not-found')).not.toBeNull();
      expect(testId('answer')).toBeNull();
    });

    it('reports a conversation that does not exist and lets the user start another', async () => {
      harness = await RouterTestingHarness.create('/ask/missing');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory();
      http
        .expectOne(`${CONVERSATIONS_URL}/missing`)
        .flush(null, { status: 404, statusText: 'Not Found' });
      await stable();

      expect(testId('conversation-error')?.textContent).toContain('not found');
      expect(element().querySelector('form')).toBeNull();
      expect(testId('new-conversation')?.getAttribute('href')).toBe('/ask');
    });

    it('can try again when a conversation could not be loaded', async () => {
      harness = await RouterTestingHarness.create('/ask/conv-1');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory([aSummary()]);
      http
        .expectOne(`${CONVERSATIONS_URL}/conv-1`)
        .flush(null, { status: 500, statusText: 'Server Error' });
      await stable();
      expect(testId('conversation-error')?.textContent).toContain('could not be loaded');

      testId('conversation-error')?.parentElement?.querySelector<HTMLButtonElement>('div button')?.click();
      await stable();
      http.expectOne(`${CONVERSATIONS_URL}/conv-1`).flush(aConversation());
      await stable();

      expect(testId('conversation-error')).toBeNull();
      expect(testId('answer')).not.toBeNull();
    });

    it('renders text from a document as text, never as markup', async () => {
      await openConversation(
        aConversation({
          turns: [
            aTurn({
              question: '<b>question</b>',
              text: '<img src=x onerror=alert(1)> [1]',
              citations: [{ ...aTurn().citations[0], excerpt: '<script>alert(1)</script>' }],
            }),
          ],
        }),
      );

      expect(element().querySelector('img')).toBeNull();
      expect(element().querySelector('script')).toBeNull();
      expect(element().querySelector('b')).toBeNull();
      expect(testId('answer')?.textContent).toContain('<img src=x');
    });
  });

  describe('sources', () => {
    it('opens the cited page from the citation and from the marker in the answer', async () => {
      await openConversation();

      testId('view-page')?.click();
      testId('marker')?.click();

      expect(openDialog).toHaveBeenCalledTimes(2);
      const [component, config] = openDialog.mock.calls[0];
      expect(component).toBe(SourceViewerDialog);
      expect(config.data).toEqual({
        documentId: 'doc-1',
        filename: 'contract.pdf',
        pageNumber: 3,
        excerpt: 'Termination requires 60 days notice.',
      });
    });

    it('shows a deleted document as deleted, without a way to open it', async () => {
      await openConversation(
        aConversation({
          turns: [
            aTurn({
              citations: [{ ...aTurn().citations[0], documentId: null, excerpt: null }],
            }),
          ],
        }),
      );

      expect(testId('answer')?.textContent).toContain('The notice is 60 days [1].');
      expect(testId('citation')?.textContent).toContain('contract.pdf');
      expect(testId('citation')?.textContent).toContain('page 3');
      expect(testId('source-deleted')).not.toBeNull();
      expect(testId('view-page')).toBeNull();
      expect(testId('marker')).toBeNull();
      expect(element().querySelector('blockquote')).toBeNull();
    });
  });

  describe('the history', () => {
    it('lists the conversations with links to them', async () => {
      harness = await RouterTestingHarness.create('/ask');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory([
        aSummary({ id: 'conv-2', title: 'Second' }),
        aSummary({ id: 'conv-1', title: 'First' }),
      ]);

      const links = testIds('conversation');
      expect(links.map((link) => link.textContent?.trim())).toEqual(['Second', 'First']);
      expect(links[0].getAttribute('href')).toBe('/ask/conv-2');
      expect(links[0].getAttribute('aria-current')).toBeNull();
    });

    it('says so when there are no conversations yet', async () => {
      await openNewConversation();

      expect(testId('history-empty')).not.toBeNull();
    });

    it('still shows the page when the history cannot be loaded, and can try again', async () => {
      harness = await RouterTestingHarness.create('/ask');
      await respondWithDocuments([aDocument()]);
      http.expectOne(HISTORY_URL).flush(null, { status: 500, statusText: 'Server Error' });
      await stable();

      expect(testId('history-error')).not.toBeNull();
      expect(element().querySelector('form')).not.toBeNull();

      testId('history-error')?.parentElement?.querySelector<HTMLButtonElement>('button')?.click();
      await respondWithHistory([aSummary()]);
      expect(testId('history-error')).toBeNull();
      expect(testIds('conversation')).toHaveLength(1);
    });

    it('moves between conversations without recreating the page', async () => {
      await openConversation();
      const page = harness.routeDebugElement?.componentInstance as Ask;

      await harness.navigateByUrl('/ask/conv-2');
      expect(testId('conversation-loading')).not.toBeNull();
      expect(testIds('turn')).toHaveLength(0);
      http.expectOne(`${CONVERSATIONS_URL}/conv-2`).flush(
        aConversation({
          id: 'conv-2',
          title: 'Other',
          turns: [aTurn({ id: 9, question: 'Other question?' })],
        }),
      );
      await stable();

      expect(harness.routeDebugElement?.componentInstance).toBe(page);
      expect(element().querySelector('h1')?.textContent).toContain('Other');
      expect(testId('turn-question')?.textContent).toContain('Other question?');
    });

    it('ignores a slow response for a conversation the user already left', async () => {
      harness = await RouterTestingHarness.create('/ask/conv-1');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory([aSummary()]);
      const slow = http.expectOne(`${CONVERSATIONS_URL}/conv-1`);

      await harness.navigateByUrl('/ask/conv-2');
      expect(slow.cancelled).toBe(true);
      http
        .expectOne(`${CONVERSATIONS_URL}/conv-2`)
        .flush(aConversation({ id: 'conv-2', title: 'Other' }));
      await stable();

      expect(element().querySelector('h1')?.textContent).toContain('Other');
    });

    it('shows the answer in its own conversation if the user moved to another', async () => {
      await openConversation();
      await askQuestion('And the penalty?');
      const request = http.expectOne(`${CONVERSATIONS_URL}/conv-1/turns`);

      await harness.navigateByUrl('/ask/conv-2');
      http
        .expectOne(`${CONVERSATIONS_URL}/conv-2`)
        .flush(aConversation({ id: 'conv-2', title: 'Other', turns: [aTurn({ id: 9 })] }));
      await stable();
      expect(testId('pending')).toBeNull();

      request.flush(aTurn({ id: 2, question: 'And the penalty?' }));
      await respondWithHistory([aSummary()]);

      expect(testIds('turn')).toHaveLength(1);
      expect(testId('turn-question')?.textContent).not.toContain('penalty');
      expect(TestBed.inject(Router).url).toBe('/ask/conv-2');
    });

    it('starts over when moving to a new conversation', async () => {
      await openConversation();

      await harness.navigateByUrl('/ask');
      await stable();

      expect(testIds('turn')).toHaveLength(0);
      expect(element().querySelector('h1')?.textContent).toContain('Ask your documents');
      expect(testId('delete-conversation')).toBeNull();
    });

    it('shows older conversations on request, without repeating any', async () => {
      harness = await RouterTestingHarness.create('/ask');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory([aSummary({ id: 'a', title: 'A' }), aSummary({ id: 'b', title: 'B' })], 3);

      testId('older-conversations')?.click();
      await stable();
      http.expectOne('/api/conversations?page=1&size=20').flush({
        items: [aSummary({ id: 'b', title: 'B' }), aSummary({ id: 'c', title: 'C' })],
        page: 1,
        size: 20,
        totalItems: 3,
      });
      await stable();

      expect(testIds('conversation').map((link) => link.textContent?.trim())).toEqual([
        'A',
        'B',
        'C',
      ]);
      expect(testId('older-conversations')).toBeNull();
    });

    it('moves a conversation to the top after a new question', async () => {
      harness = await RouterTestingHarness.create('/ask/conv-1');
      await respondWithDocuments([aDocument()]);
      await respondWithHistory([
        aSummary({ id: 'conv-2', title: 'Other' }),
        aSummary({ id: 'conv-1', title: 'This one' }),
      ]);
      http.expectOne(`${CONVERSATIONS_URL}/conv-1`).flush(aConversation({ title: 'This one' }));
      await stable();

      await askQuestion('And then?');
      http.expectOne(`${CONVERSATIONS_URL}/conv-1/turns`).flush(aTurn({ id: 2 }));
      await respondWithHistory([
        aSummary({ id: 'conv-1', title: 'This one' }),
        aSummary({ id: 'conv-2', title: 'Other' }),
      ]);

      expect(testIds('conversation').map((link) => link.textContent?.trim())).toEqual([
        'This one',
        'Other',
      ]);
    });

    it('deletes the conversation after the user confirms and starts a new one', async () => {
      await openConversation();
      openDialog.mockReturnValue({ afterClosed: () => of(true) });

      testId('delete-conversation')?.click();
      await stable();

      expect(openDialog.mock.calls[0][1].data.subject).toBe('What is the notice?');
      const request = http.expectOne(`${CONVERSATIONS_URL}/conv-1`);
      expect(request.request.method).toBe('DELETE');
      request.flush(null, { status: 204, statusText: 'No Content' });
      await stable();

      expect(TestBed.inject(Router).url).toBe('/ask');
      expect(testIds('conversation')).toHaveLength(0);
      expect(testIds('turn')).toHaveLength(0);
    });

    it('keeps the conversation when the user cancels the delete', async () => {
      await openConversation();
      openDialog.mockReturnValue({ afterClosed: () => of(false) });

      testId('delete-conversation')?.click();
      await stable();

      http.expectNone(`${CONVERSATIONS_URL}/conv-1`);
      expect(testIds('turn')).toHaveLength(1);
    });

    it('reports a delete that failed and keeps the conversation', async () => {
      await openConversation();
      openDialog.mockReturnValue({ afterClosed: () => of(true) });

      testId('delete-conversation')?.click();
      await stable();
      http
        .expectOne(`${CONVERSATIONS_URL}/conv-1`)
        .flush(null, { status: 500, statusText: 'Server Error' });
      await stable();

      expect(testId('ask-error')?.textContent).toContain('could not be deleted');
      expect(testIds('turn')).toHaveLength(1);
      expect(TestBed.inject(Router).url).toBe('/ask/conv-1');
    });
  });

  it('limits a question to the chosen document', async () => {
    harness = await RouterTestingHarness.create('/ask');
    await respondWithDocuments([aDocument()]);
    await respondWithHistory();
    const component = harness.routeDebugElement?.componentInstance as unknown as {
      form: { controls: { documentId: { setValue(value: string): void } } };
    };
    component.form.controls.documentId.setValue('doc-1');

    await askQuestion('What is the notice?');

    const request = http.expectOne(CONVERSATIONS_URL);
    expect(request.request.body).toEqual({
      question: 'What is the notice?',
      documentIds: ['doc-1'],
    });
    request.flush(aConversation());
    await respondWithHistory([aSummary()]);
  });
});
