import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { MAX_FILE_SIZE_BYTES, UserDocument } from '../../core/documents/document.model';
import { Documents, POLL_INTERVAL_MS } from './documents';

const FIRST_PAGE = '/api/documents?page=0&size=10';

function aDocument(overrides: Partial<UserDocument> = {}): UserDocument {
  return {
    id: 'doc-1',
    filename: 'contract.pdf',
    sizeBytes: 2 * 1024 * 1024,
    status: 'READY',
    failureReason: null,
    pageCount: 12,
    createdAt: '2026-11-03T12:00:00Z',
    ...overrides,
  };
}

describe('Documents', () => {
  let fixture: ComponentFixture<Documents>;
  let http: HttpTestingController;
  const openDialog = vi.fn();

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const testId = (id: string): HTMLElement | null =>
    element().querySelector(`[data-testid="${id}"]`);
  const documentRows = (): string[] =>
    Array.from(element().querySelectorAll('[data-testid="document"]')).map((row) =>
      (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );

  const respondWithList = async (
    items: readonly UserDocument[],
    totalItems = items.length,
    url = FIRST_PAGE,
  ): Promise<void> => {
    http.expectOne(url).flush({ items, page: 0, size: 10, totalItems });
    await fixture.whenStable();
  };

  const chooseFile = async (file: File): Promise<void> => {
    const input = testId('file-input') as HTMLInputElement;
    Object.defineProperty(input, 'files', { configurable: true, value: [file] });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  };

  const aPdf = (): File => new File(['%PDF-1.7'], 'new.pdf', { type: 'application/pdf' });

  const clickDelete = (): void => {
    element().querySelector<HTMLButtonElement>('button[aria-label^="Delete"]')?.click();
  };

  beforeEach(async () => {
    openDialog.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MatDialog, useValue: { open: openDialog } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Documents);
    await fixture.whenStable();
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  it('shows the loading state, then the empty state', async () => {
    expect(testId('loading')).not.toBeNull();

    await respondWithList([]);

    expect(testId('loading')).toBeNull();
    expect(testId('empty')).not.toBeNull();
  });

  it('shows the facts about each document', async () => {
    await respondWithList([
      aDocument(),
      aDocument({ id: 'doc-2', filename: 'one.pdf', sizeBytes: 2048, pageCount: 1 }),
    ]);

    expect(documentRows()[0]).toContain('contract.pdf');
    expect(documentRows()[0]).toContain('2 MB');
    expect(documentRows()[0]).toContain('12 pages');
    expect(documentRows()[0]).toContain('Ready');
    expect(documentRows()[1]).toContain('2 KB');
    expect(documentRows()[1]).toContain('1 page');
  });

  it('explains why a document failed', async () => {
    await respondWithList([
      aDocument({ status: 'FAILED', failureReason: 'NO_EXTRACTABLE_TEXT', pageCount: null }),
    ]);

    expect(testId('document-failure')?.textContent).toContain('no selectable text');
    expect(documentRows()[0]).toContain('Failed');
  });

  it('checks again while a document is processing, and stops when it is finished', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    http.expectOne(FIRST_PAGE).flush({
      items: [aDocument({ status: 'PROCESSING', pageCount: null })],
      page: 0,
      size: 10,
      totalItems: 1,
    });

    vi.advanceTimersByTime(POLL_INTERVAL_MS);
    http.expectOne(FIRST_PAGE).flush({ items: [aDocument()], page: 0, size: 10, totalItems: 1 });

    vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);
    http.expectNone(FIRST_PAGE);
  });

  it('stops checking when the user leaves the page', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    http.expectOne(FIRST_PAGE).flush({
      items: [aDocument({ status: 'PROCESSING', pageCount: null })],
      page: 0,
      size: 10,
      totalItems: 1,
    });

    fixture.destroy();
    vi.advanceTimersByTime(POLL_INTERVAL_MS * 3);

    http.expectNone(FIRST_PAGE);
  });

  it('uploads the chosen file and shows it', async () => {
    await respondWithList([]);

    await chooseFile(aPdf());

    const upload = http.expectOne('/api/documents');
    expect(upload.request.method).toBe('POST');
    expect((upload.request.body as FormData).get('file')).toBeInstanceOf(File);
    expect(element().textContent).toContain('Uploading...');
    upload.flush(aDocument(), { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();

    await respondWithList([aDocument()]);
    expect(documentRows()).toHaveLength(1);
    expect(element().textContent).not.toContain('Uploading...');
  });

  it('refuses a file over the limit without sending it', async () => {
    await respondWithList([]);
    const file = aPdf();
    Object.defineProperty(file, 'size', { value: MAX_FILE_SIZE_BYTES + 1 });

    await chooseFile(file);

    http.expectNone('/api/documents');
    expect(testId('action-error')?.textContent).toContain('25 MB');
  });

  it('accepts a file exactly at the limit', async () => {
    await respondWithList([]);
    const file = aPdf();
    Object.defineProperty(file, 'size', { value: MAX_FILE_SIZE_BYTES });

    await chooseFile(file);

    http.expectOne('/api/documents').flush(aDocument(), { status: 202, statusText: 'Accepted' });
    await fixture.whenStable();
    await respondWithList([aDocument()]);
    expect(testId('action-error')).toBeNull();
  });

  it('shows why an upload was refused', async () => {
    await respondWithList([]);

    await chooseFile(aPdf());
    http
      .expectOne('/api/documents')
      .flush(
        { detail: 'Only PDF files are accepted.' },
        { status: 415, statusText: 'Unsupported Media Type' },
      );
    await fixture.whenStable();

    expect(testId('action-error')?.textContent).toContain('Only PDF files are accepted.');
    expect(element().textContent).toContain('Upload PDF');
  });

  it('deletes a document after the user confirms', async () => {
    await respondWithList([aDocument()]);
    openDialog.mockReturnValue({ afterClosed: () => of(true) });

    clickDelete();
    await fixture.whenStable();

    const dialogData = openDialog.mock.calls[0][1].data;
    expect(dialogData.subject).toBe('contract.pdf');
    expect(dialogData.message).toContain('Conversations that used it stay in your history');
    http.expectOne('/api/documents/doc-1').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    await respondWithList([]);
    expect(testId('empty')).not.toBeNull();
  });

  it('links to the original file only for documents that are ready', async () => {
    await respondWithList([
      aDocument(),
      aDocument({ id: 'doc-2', status: 'PROCESSING', pageCount: null }),
      aDocument({ id: 'doc-3', status: 'FAILED', failureReason: 'NOT_READABLE', pageCount: null }),
    ]);

    const links = element().querySelectorAll('[data-testid="open-file"]');
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe('/api/documents/doc-1/file');
    expect(links[0].getAttribute('target')).toBe('_blank');
    expect(links[0].getAttribute('rel')).toBe('noopener');

    // A document still processing makes the page check again.
    await vi.waitFor(() => http.expectOne(FIRST_PAGE).flush({ items: [], page: 0, size: 10, totalItems: 0 }), {
      timeout: POLL_INTERVAL_MS + 1000,
    });
    await fixture.whenStable();
  });

  it('keeps the document when the user cancels', async () => {
    await respondWithList([aDocument()]);
    openDialog.mockReturnValue({ afterClosed: () => of(false) });

    clickDelete();
    await fixture.whenStable();

    http.expectNone('/api/documents/doc-1');
    expect(documentRows()).toHaveLength(1);
  });

  it('shows the error and refreshes the list when a delete fails', async () => {
    await respondWithList([aDocument()]);
    openDialog.mockReturnValue({ afterClosed: () => of(true) });

    clickDelete();
    await fixture.whenStable();
    http
      .expectOne('/api/documents/doc-1')
      .flush({ detail: 'The document was not found.' }, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(testId('action-error')?.textContent).toContain('The document was not found.');
    await respondWithList([]);
  });

  it('offers to try again when the list cannot be loaded', async () => {
    http.expectOne(FIRST_PAGE).flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(testId('load-error')).not.toBeNull();
    expect(testId('empty')).toBeNull();

    testId('load-error')?.parentElement?.querySelector('button')?.click();
    await respondWithList([aDocument()]);

    expect(testId('load-error')).toBeNull();
    expect(documentRows()).toHaveLength(1);
  });

  it('moves between pages when there are more documents than fit in one', async () => {
    await respondWithList([aDocument()], 11);

    element().querySelector<HTMLButtonElement>('.mat-mdc-paginator-navigation-next')?.click();
    await fixture.whenStable();

    await respondWithList([aDocument({ id: 'doc-11' })], 11, '/api/documents?page=1&size=10');
    expect(documentRows()).toHaveLength(1);
  });

  it('hides the paginator when everything fits in one page', async () => {
    await respondWithList([aDocument()]);

    expect(element().querySelector('mat-paginator')).toBeNull();
  });

  it('goes back a page after the last document of the last page is deleted', async () => {
    await respondWithList([aDocument()], 11);
    element().querySelector<HTMLButtonElement>('.mat-mdc-paginator-navigation-next')?.click();
    await fixture.whenStable();
    await respondWithList([aDocument({ id: 'doc-11' })], 11, '/api/documents?page=1&size=10');
    openDialog.mockReturnValue({ afterClosed: () => of(true) });

    clickDelete();
    await fixture.whenStable();
    http.expectOne('/api/documents/doc-11').flush(null, { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    await respondWithList([], 10, '/api/documents?page=1&size=10');
    await respondWithList([aDocument()], 10);
    expect(documentRows()).toHaveLength(1);
  });
});
