import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subject, catchError, filter, of, switchMap } from 'rxjs';
import {
  DocumentFailureReason,
  DocumentList,
  DocumentStatus,
  MAX_FILE_SIZE_BYTES,
  UserDocument,
} from '../../core/documents/document.model';
import { DocumentService } from '../../core/documents/document.service';
import { errorMessage } from '../../core/http/error-message';
import { ConfirmDeleteData, ConfirmDeleteDialog } from './confirm-delete-dialog';

const PAGE_SIZE = 10;
export const POLL_INTERVAL_MS = 2000;

const FAILURE_MESSAGES: Record<DocumentFailureReason, string> = {
  NOT_READABLE: 'This file is damaged or is not a valid PDF.',
  PROTECTED: 'This PDF is password protected or does not allow copying its text.',
  NO_EXTRACTABLE_TEXT: 'This PDF has no selectable text. Scanned documents are not supported.',
  TOO_MANY_PAGES: 'This PDF has more pages than the supported limit.',
  PROCESSING_UNAVAILABLE: 'The system was busy. Delete this document and upload it again.',
  EMBEDDING_UNAVAILABLE: 'The AI service was not available. Delete this document and upload it again.',
  INTERNAL_ERROR: 'Something went wrong while reading this file. Delete it and try again.',
};

@Component({
  selector: 'app-documents',
  imports: [DatePipe, MatButtonModule, MatPaginatorModule, MatProgressBarModule],
  templateUrl: './documents.html',
  styleUrl: './documents.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Documents {
  private readonly documentService = inject(DocumentService);
  private readonly dialog = inject(MatDialog);
  private readonly destroyRef = inject(DestroyRef);

  /** Each request replaces the previous one, so an old response cannot overwrite a newer one. */
  private readonly reloads = new Subject<void>();
  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly pageSize = PAGE_SIZE;
  protected readonly maxFileSizeLabel = formatSize(MAX_FILE_SIZE_BYTES);
  protected readonly statusLabels: Record<DocumentStatus, string> = {
    PROCESSING: 'Processing',
    READY: 'Ready',
    FAILED: 'Failed',
  };
  protected readonly formatSize = formatSize;

  protected readonly documents = signal<readonly UserDocument[]>([]);
  protected readonly totalItems = signal(0);
  protected readonly pageIndex = signal(0);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly uploading = signal(false);
  protected readonly actionError = signal<string | null>(null);

  constructor() {
    this.reloads
      .pipe(
        switchMap(() =>
          this.documentService.list(this.pageIndex(), PAGE_SIZE).pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((list) => this.applyList(list));
    this.destroyRef.onDestroy(() => clearTimeout(this.pollTimer));
    this.load();
  }

  protected load(): void {
    clearTimeout(this.pollTimer);
    this.reloads.next();
  }

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    // Without this, choosing the same file again would not fire another change event.
    input.value = '';
    if (!file) {
      return;
    }
    this.actionError.set(null);
    if (file.size > MAX_FILE_SIZE_BYTES) {
      this.actionError.set(`This file is larger than the ${this.maxFileSizeLabel} limit.`);
      return;
    }
    this.uploading.set(true);
    this.documentService
      .upload(file)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.uploading.set(false);
          this.pageIndex.set(0);
          this.load();
        },
        error: (error: unknown) => {
          this.uploading.set(false);
          this.actionError.set(errorMessage(error, 'The file could not be uploaded. Try again.'));
        },
      });
  }

  protected confirmDelete(document: UserDocument): void {
    this.actionError.set(null);
    this.dialog
      .open<ConfirmDeleteDialog, ConfirmDeleteData, boolean>(ConfirmDeleteDialog, {
        data: { filename: document.filename },
      })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => this.documentService.delete(document.id)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => this.load(),
        error: (error: unknown) => {
          this.actionError.set(errorMessage(error, 'The document could not be deleted. Try again.'));
          this.load();
        },
      });
  }

  protected onPage(event: PageEvent): void {
    this.pageIndex.set(event.pageIndex);
    this.load();
  }

  protected failureMessage(document: UserDocument): string | null {
    return document.failureReason && FAILURE_MESSAGES[document.failureReason];
  }

  private applyList(list: DocumentList | null): void {
    this.loading.set(false);
    this.loadFailed.set(list === null);
    if (list === null) {
      return;
    }
    // Deleting the only document of the last page leaves the user on a page that no longer exists.
    if (list.items.length === 0 && this.pageIndex() > 0) {
      this.pageIndex.update((index) => index - 1);
      this.load();
      return;
    }
    this.documents.set(list.items);
    this.totalItems.set(list.totalItems);
    if (list.items.some((document) => document.status === 'PROCESSING')) {
      this.pollTimer = setTimeout(() => this.load(), POLL_INTERVAL_MS);
    }
  }
}

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${Math.round(bytes / 1024)} KB`;
  }
  const megabytes = bytes / (1024 * 1024);
  return `${Number.isInteger(megabytes) ? megabytes : megabytes.toFixed(1)} MB`;
}
