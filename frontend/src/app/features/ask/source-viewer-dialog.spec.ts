import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { vi } from 'vitest';
import { DocumentPage } from '../../core/documents/document.model';
import { SourceViewerData, SourceViewerDialog } from './source-viewer-dialog';

const DATA: SourceViewerData = {
  documentId: 'doc-1',
  filename: 'contract.pdf',
  pageNumber: 2,
  excerpt: 'Termination requires 60 days notice.',
};

function aPage(overrides: Partial<DocumentPage> = {}): DocumentPage {
  return {
    documentId: 'doc-1',
    filename: 'contract.pdf',
    pageNumber: 2,
    pageCount: 3,
    text: 'Clause 7. Termination requires 60 days notice. Clause 8.',
    ...overrides,
  };
}

describe('SourceViewerDialog', () => {
  let fixture: ComponentFixture<SourceViewerDialog>;
  let http: HttpTestingController;
  const scrollIntoView = vi.fn();

  const element = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const testId = <T extends HTMLElement>(id: string): T | null =>
    element().querySelector<T>(`[data-testid="${id}"]`);

  const respondWithPage = async (number: number, page: DocumentPage): Promise<void> => {
    http.expectOne(`/api/documents/doc-1/pages/${number}`).flush(page);
    await fixture.whenStable();
  };

  beforeEach(async () => {
    scrollIntoView.mockReset();
    Element.prototype.scrollIntoView = scrollIntoView;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: DATA },
        { provide: MatDialogRef, useValue: { close: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SourceViewerDialog);
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
  });

  it('shows the loading state until the page arrives', async () => {
    expect(testId('page-loading')).not.toBeNull();

    await respondWithPage(2, aPage());

    expect(testId('page-loading')).toBeNull();
    expect(testId('page-position')?.textContent).toContain('Page 2');
    expect(testId('page-position')?.textContent).toContain('of 3');
  });

  it('marks the cited passage inside the text of its page and scrolls to it', async () => {
    await respondWithPage(2, aPage());

    expect(testId('page-highlight')?.textContent).toBe(DATA.excerpt);
    expect(testId('page-text')?.textContent).toBe(aPage().text);
    expect(scrollIntoView).toHaveBeenCalled();
  });

  it('keeps the text intact when the passage cannot be found', async () => {
    await respondWithPage(2, aPage({ text: 'Different text.' }));

    expect(testId('page-highlight')).toBeNull();
    expect(testId('page-text')?.textContent).toBe('Different text.');
  });

  it('moves between pages and only marks the passage on the cited page', async () => {
    await respondWithPage(2, aPage());

    testId<HTMLButtonElement>('next-page')?.click();
    await fixture.whenStable();
    await respondWithPage(3, aPage({ pageNumber: 3, text: DATA.excerpt }));

    expect(testId('page-position')?.textContent).toContain('Page 3');
    expect(testId('page-highlight')).toBeNull();
    expect(testId('page-text')?.textContent).toBe(DATA.excerpt);
    expect(testId<HTMLAnchorElement>('open-original')?.getAttribute('href')).toBe(
      '/api/documents/doc-1/file#page=3',
    );

    testId<HTMLButtonElement>('previous-page')?.click();
    await fixture.whenStable();
    await respondWithPage(2, aPage());
    expect(testId('page-highlight')).not.toBeNull();
  });

  it('cannot move past the first or the last page', async () => {
    await respondWithPage(2, aPage({ pageNumber: 2, pageCount: 2 }));

    expect(testId<HTMLButtonElement>('next-page')?.disabled).toBe(true);
    expect(testId<HTMLButtonElement>('previous-page')?.disabled).toBe(false);

    testId<HTMLButtonElement>('previous-page')?.click();
    await fixture.whenStable();
    await respondWithPage(1, aPage({ pageNumber: 1, pageCount: 2 }));

    expect(testId<HTMLButtonElement>('previous-page')?.disabled).toBe(true);
    expect(testId<HTMLButtonElement>('next-page')?.disabled).toBe(false);
  });

  it('links to the original file at the cited page, in a new tab', async () => {
    await respondWithPage(2, aPage());

    const link = testId<HTMLAnchorElement>('open-original');
    expect(link?.getAttribute('href')).toBe('/api/documents/doc-1/file#page=2');
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener');
  });

  it('says so when a page has no text', async () => {
    await respondWithPage(2, aPage({ text: '' }));

    expect(testId('page-empty')).not.toBeNull();
    expect(testId('page-text')).toBeNull();
  });

  it('explains when the page cannot be loaded', async () => {
    http
      .expectOne('/api/documents/doc-1/pages/2')
      .flush(null, { status: 404, statusText: 'Not Found' });
    await fixture.whenStable();

    expect(testId('page-error')).not.toBeNull();
    expect(testId('page-text')).toBeNull();
  });

  it('renders text from a document as text, never as markup', async () => {
    await respondWithPage(2, aPage({ text: `<img src=x onerror=alert(1)> ${DATA.excerpt}` }));

    expect(element().querySelector('img')).toBeNull();
    expect(testId('page-text')?.textContent).toContain('<img src=x');
  });
});
