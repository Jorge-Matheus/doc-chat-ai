import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { catchError, of, startWith, Subject, switchMap } from 'rxjs';
import { DocumentPage } from '../../core/documents/document.model';
import { DocumentService } from '../../core/documents/document.service';
import { Icon } from '../../core/ui/icon';

export interface SourceViewerData {
  readonly documentId: string;
  readonly filename: string;
  readonly pageNumber: number;
  readonly excerpt: string;
}

/**
 * Shows the text the answer came from, with the cited passage marked. A passage is an exact part of
 * its page, so it can be found without guessing. The original PDF is one click away, because
 * extracted text loses tables and layout.
 */
@Component({
  selector: 'app-source-viewer-dialog',
  imports: [MatButtonModule, MatDialogModule, Icon],
  templateUrl: './source-viewer-dialog.html',
  styleUrl: './source-viewer-dialog.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourceViewerDialog {
  private readonly documents = inject(DocumentService);
  private readonly injector = inject(Injector);
  private readonly highlight = viewChild<ElementRef<HTMLElement>>('highlight');
  private readonly pageRequests = new Subject<number>();

  protected readonly data = inject<SourceViewerData>(MAT_DIALOG_DATA);
  protected readonly pageNumber = signal(this.data.pageNumber);
  protected readonly page = signal<DocumentPage | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  protected readonly fileUrl = computed(() =>
    this.documents.fileUrl(this.data.documentId, this.pageNumber()),
  );

  /** The text around the passage. The passage is only marked on the page it was cited from. */
  protected readonly parts = computed(() => {
    const page = this.page();
    if (!page) {
      return null;
    }
    const start = page.pageNumber === this.data.pageNumber ? page.text.indexOf(this.data.excerpt) : -1;
    if (start < 0) {
      return { before: page.text, passage: null, after: '' };
    }
    const end = start + this.data.excerpt.length;
    return {
      before: page.text.slice(0, start),
      passage: this.data.excerpt,
      after: page.text.slice(end),
    };
  });

  constructor() {
    this.pageRequests
      .pipe(
        startWith(this.data.pageNumber),
        switchMap((number) =>
          this.documents.getPage(this.data.documentId, number).pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((page) => {
        this.loading.set(false);
        this.loadFailed.set(page === null);
        this.page.set(page);
        afterNextRender(() => this.highlight()?.nativeElement.scrollIntoView({ block: 'center' }), {
          injector: this.injector,
        });
      });
  }

  protected goTo(pageNumber: number): void {
    this.pageNumber.set(pageNumber);
    this.loading.set(true);
    this.pageRequests.next(pageNumber);
  }
}
