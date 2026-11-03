import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { UserDocument } from '../../core/documents/document.model';
import { Answer } from '../../core/questions/question.model';
import { Ask } from './ask';

const DOCUMENTS_URL = '/api/documents?page=0&size=100';

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

const AN_ANSWER: Answer = {
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
};

describe('Ask', () => {
  let fixture: ComponentFixture<Ask>;
  let http: HttpTestingController;

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const testId = (id: string): HTMLElement | null =>
    element().querySelector(`[data-testid="${id}"]`);

  const respondWithDocuments = async (items: readonly UserDocument[]): Promise<void> => {
    http.expectOne(DOCUMENTS_URL).flush({ items, page: 0, size: 100, totalItems: items.length });
    await fixture.whenStable();
  };

  const askQuestion = async (question: string): Promise<void> => {
    const input = testId('question') as HTMLTextAreaElement;
    input.value = question;
    input.dispatchEvent(new Event('input'));
    element().querySelector('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Ask);
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
  });

  it('asks the user to upload a document when none is ready', async () => {
    await respondWithDocuments([
      aDocument({ status: 'PROCESSING' }),
      aDocument({ id: 'doc-2', status: 'FAILED' }),
    ]);

    expect(testId('no-documents')?.querySelector('a')?.getAttribute('href')).toBe('/documents');
    expect(element().querySelector('form')).toBeNull();
  });

  it('reports when the documents cannot be loaded', async () => {
    http.expectOne(DOCUMENTS_URL).flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    expect(testId('documents-error')).not.toBeNull();
    expect(element().querySelector('form')).toBeNull();
  });

  it('does not send a blank question', async () => {
    await respondWithDocuments([aDocument()]);

    await askQuestion('   ');

    http.expectNone('/api/questions');
    expect(element().textContent).toContain('Write your question first.');
  });

  it('asks about all documents by default and shows the answer with its sources', async () => {
    await respondWithDocuments([aDocument()]);

    await askQuestion('  What is the notice?  ');

    const request = http.expectOne('/api/questions');
    expect(request.request.body).toEqual({ question: 'What is the notice?', documentIds: [] });
    expect(element().textContent).toContain('Reading your documents...');
    request.flush(AN_ANSWER);
    await fixture.whenStable();

    expect(testId('answer')?.textContent).toContain('The notice is 60 days [1].');
    const citation = testId('citation')?.textContent ?? '';
    expect(citation).toContain('[1]');
    expect(citation).toContain('contract.pdf');
    expect(citation).toContain('page 3');
    expect(citation).toContain('Termination requires 60 days notice.');
    expect(element().textContent).not.toContain('Reading your documents...');
  });

  it('says so when the documents do not contain the answer', async () => {
    await respondWithDocuments([aDocument()]);

    await askQuestion('Who is the president?');
    http.expectOne('/api/questions').flush({ answered: false, text: null, citations: [] });
    await fixture.whenStable();

    expect(testId('not-found')).not.toBeNull();
    expect(testId('answer')).toBeNull();
  });

  it('shows why a question could not be answered and lets the user try again', async () => {
    await respondWithDocuments([aDocument()]);

    await askQuestion('What is the notice?');
    http
      .expectOne('/api/questions')
      .flush(
        { detail: 'The AI service is not available right now.' },
        { status: 503, statusText: 'Service Unavailable' },
      );
    await fixture.whenStable();

    expect(testId('ask-error')?.textContent).toContain('The AI service is not available');
    expect(element().querySelector<HTMLButtonElement>('button[type="submit"]')?.disabled).toBe(
      false,
    );
  });

  it('removes the previous answer and error when asking again', async () => {
    await respondWithDocuments([aDocument()]);
    await askQuestion('First?');
    http.expectOne('/api/questions').flush(AN_ANSWER);
    await fixture.whenStable();

    await askQuestion('Second?');

    expect(testId('answer')).toBeNull();
    http.expectOne('/api/questions').flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(testId('answer')).toBeNull();
    expect(testId('ask-error')).not.toBeNull();
  });

  it('renders text from a document as text, never as markup', async () => {
    await respondWithDocuments([aDocument()]);

    await askQuestion('What is the notice?');
    http.expectOne('/api/questions').flush({
      ...AN_ANSWER,
      text: '<img src=x onerror=alert(1)> [1]',
      citations: [{ ...AN_ANSWER.citations[0], excerpt: '<script>alert(1)</script>' }],
    });
    await fixture.whenStable();

    expect(element().querySelector('img')).toBeNull();
    expect(element().querySelector('script')).toBeNull();
    expect(testId('answer')?.textContent).toContain('<img src=x');
  });
});
