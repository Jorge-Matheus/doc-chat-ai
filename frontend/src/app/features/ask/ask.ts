import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormGroupDirective,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, distinctUntilChanged, filter, map, of, Subject, switchMap } from 'rxjs';
import {
  Conversation,
  ConversationDetails,
  Turn,
  TurnCitation,
} from '../../core/conversations/conversation.model';
import { ConversationService } from '../../core/conversations/conversation.service';
import { UserDocument } from '../../core/documents/document.model';
import { DocumentService } from '../../core/documents/document.service';
import { errorMessage } from '../../core/http/error-message';
import { ConfirmDialog, ConfirmDialogData } from '../../core/ui/confirm-dialog';
import { SourceViewerData, SourceViewerDialog } from './source-viewer-dialog';
import { TurnView } from './turn-view';

/** Keep in sync with QuestionRequest in the backend. */
const MAX_QUESTION_LENGTH = 2000;
/** The most the API returns at once. Questions over all documents are not affected by it. */
const MAX_LISTED_DOCUMENTS = 100;
const HISTORY_PAGE_SIZE = 20;

/** The question being answered, and the conversation it belongs to, or null for a new one. */
interface PendingQuestion {
  readonly conversationId: string | null;
  readonly question: string;
}

@Component({
  selector: 'app-ask',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    TurnView,
  ],
  templateUrl: './ask.html',
  styleUrl: './ask.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Ask {
  private readonly conversations = inject(ConversationService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private readonly formDirective = viewChild(FormGroupDirective);
  private readonly turnElements = viewChildren<TurnView, ElementRef<HTMLElement>>(TurnView, {
    read: ElementRef,
  });
  private readonly pendingElement = viewChild<ElementRef<HTMLElement>>('pendingQuestion');
  private readonly conversationRequests = new Subject<string>();
  private historyPagesLoaded = 0;

  protected readonly maxQuestionLength = MAX_QUESTION_LENGTH;
  protected readonly form = inject(NonNullableFormBuilder).group({
    documentId: [''],
    question: ['', [Validators.required, Validators.pattern(/\S/)]],
  });

  protected readonly readyDocuments = signal<readonly UserDocument[]>([]);
  protected readonly loadingDocuments = signal(true);
  protected readonly documentsLoadFailed = signal(false);

  protected readonly history = signal<readonly Conversation[]>([]);
  protected readonly historyTotal = signal(0);
  protected readonly historyLoading = signal(true);
  protected readonly historyLoadFailed = signal(false);

  protected readonly currentId = signal<string | null>(null);
  protected readonly currentTitle = signal<string | null>(null);
  protected readonly turns = signal<readonly Turn[]>([]);
  protected readonly loadingConversation = signal(false);
  protected readonly conversationLoadError = signal<string | null>(null);

  protected readonly pending = signal<PendingQuestion | null>(null);
  protected readonly error = signal<string | null>(null);

  constructor() {
    inject(DocumentService)
      .list(0, MAX_LISTED_DOCUMENTS)
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (list) => {
          this.readyDocuments.set(list.items.filter((document) => document.status === 'READY'));
          this.loadingDocuments.set(false);
        },
        error: () => {
          this.documentsLoadFailed.set(true);
          this.loadingDocuments.set(false);
        },
      });

    // Each request replaces the previous one, so a slow response cannot show the wrong conversation.
    this.conversationRequests
      .pipe(
        switchMap((id) =>
          this.conversations.get(id).pipe(catchError((error: HttpErrorResponse) => of(error))),
        ),
        takeUntilDestroyed(),
      )
      .subscribe((result) => this.applyConversation(result));

    inject(ActivatedRoute)
      .paramMap.pipe(
        map((params) => params.get('id')),
        distinctUntilChanged(),
        // The conversation just created by a question is already on the screen.
        filter((id) => id === null || id !== this.currentId()),
        takeUntilDestroyed(),
      )
      .subscribe((id) => this.show(id));

    this.loadOlderConversations();
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.pending()) {
      return;
    }
    const { question: rawQuestion, documentId } = this.form.getRawValue();
    const question = rawQuestion.trim();
    const documentIds = documentId ? [documentId] : [];
    const conversationId = this.currentId();

    this.pending.set({ conversationId, question });
    this.error.set(null);
    this.resetQuestion('');
    this.scrollToLatest();

    if (conversationId === null) {
      this.conversations
        .start(question, documentIds)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (conversation) => this.onConversationStarted(conversation),
          error: (error: unknown) => this.onQuestionFailed(error, question, null),
        });
    } else {
      this.conversations
        .addTurn(conversationId, question, documentIds)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (turn) => this.onTurnAdded(conversationId, turn),
          error: (error: unknown) => this.onQuestionFailed(error, question, conversationId),
        });
    }
  }

  /** Enter sends the question, as in other chats, and Shift+Enter starts a new line. */
  protected onQuestionKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      this.formDirective()?.onSubmit(event);
    }
  }

  protected openSource(citation: TurnCitation): void {
    if (citation.documentId === null) {
      return;
    }
    this.dialog.open<SourceViewerDialog, SourceViewerData>(SourceViewerDialog, {
      width: '48rem',
      maxWidth: '95vw',
      data: {
        documentId: citation.documentId,
        filename: citation.filename,
        pageNumber: citation.pageNumber,
        excerpt: citation.excerpt ?? '',
      },
    });
  }

  protected confirmDeleteConversation(): void {
    const id = this.currentId();
    if (id === null) {
      return;
    }
    this.error.set(null);
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: 'Delete this conversation?',
          subject: this.currentTitle() ?? '',
          message: 'Its questions and answers will be permanently deleted. Your documents are not affected.',
        },
      })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => this.conversations.delete(id)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.history.update((items) => items.filter((item) => item.id !== id));
          this.historyTotal.update((total) => total - 1);
          if (this.currentId() === id) {
            void this.router.navigate(['/ask']);
          }
        },
        error: (error: unknown) => {
          this.error.set(errorMessage(error, 'The conversation could not be deleted. Try again.'));
        },
      });
  }

  protected loadOlderConversations(): void {
    this.historyLoading.set(true);
    this.conversations
      .list(this.historyPagesLoaded, HISTORY_PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.historyPagesLoaded++;
          this.historyTotal.set(list.totalItems);
          // New activity moves conversations to the top, so a later page can repeat some of them.
          this.history.update((items) => {
            const known = new Set(items.map((item) => item.id));
            return [...items, ...list.items.filter((item) => !known.has(item.id))];
          });
          this.historyLoadFailed.set(false);
          this.historyLoading.set(false);
        },
        error: () => {
          this.historyLoadFailed.set(true);
          this.historyLoading.set(false);
        },
      });
  }

  protected retryLoadingConversation(): void {
    const id = this.currentId();
    if (id !== null) {
      this.show(id);
    }
  }

  private show(id: string | null): void {
    this.currentId.set(id);
    this.currentTitle.set(null);
    this.turns.set([]);
    this.error.set(null);
    this.conversationLoadError.set(null);
    this.loadingConversation.set(id !== null);
    if (id !== null) {
      this.conversationRequests.next(id);
    }
  }

  private applyConversation(result: ConversationDetails | HttpErrorResponse): void {
    this.loadingConversation.set(false);
    if (result instanceof HttpErrorResponse) {
      const missing = result.status === 404 || result.status === 400;
      this.conversationLoadError.set(
        missing
          ? 'This conversation was not found. It may have been deleted.'
          : 'This conversation could not be loaded.',
      );
      return;
    }
    if (result.id === this.currentId()) {
      this.currentTitle.set(result.title);
      this.turns.set(result.turns);
      this.scrollToLatest();
    }
  }

  private onConversationStarted(conversation: ConversationDetails): void {
    this.pending.set(null);
    this.refreshNewestConversations();
    // The user may have opened another conversation while this one was being answered.
    if (this.currentId() === null) {
      this.currentId.set(conversation.id);
      this.currentTitle.set(conversation.title);
      this.turns.set(conversation.turns);
      this.scrollToLatest();
      void this.router.navigate(['/ask', conversation.id], { replaceUrl: true });
    }
  }

  private onTurnAdded(conversationId: string, turn: Turn): void {
    this.pending.set(null);
    this.refreshNewestConversations();
    if (this.currentId() === conversationId) {
      this.turns.update((turns) => [...turns, turn]);
      this.scrollToLatest();
    }
  }

  private onQuestionFailed(error: unknown, question: string, conversationId: string | null): void {
    this.pending.set(null);
    if (this.currentId() !== conversationId) {
      return;
    }
    this.error.set(errorMessage(error, 'The question could not be answered. Try again.'));
    // Nothing was stored, so the question is given back for another try.
    if (this.form.controls.question.value === '') {
      this.resetQuestion(question);
    }
  }

  /** Puts the conversation that just had activity first, keeping those already loaded. */
  private refreshNewestConversations(): void {
    this.conversations
      .list(0, HISTORY_PAGE_SIZE)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (list) => {
          this.historyTotal.set(list.totalItems);
          this.history.update((items) => {
            const newest = new Set(list.items.map((item) => item.id));
            return [...list.items, ...items.filter((item) => !newest.has(item.id))];
          });
          this.historyPagesLoaded = Math.max(this.historyPagesLoaded, 1);
          this.historyLoadFailed.set(false);
        },
        // The answer is on the screen, and the list catches up with the next question.
        error: () => undefined,
      });
  }

  /** Also clears the "submitted" state, which would otherwise show an error on the empty field. */
  private resetQuestion(question: string): void {
    this.formDirective()?.resetForm({
      documentId: this.form.controls.documentId.value,
      question,
    });
  }

  private scrollToLatest(): void {
    afterNextRender(
      () => {
        const target = this.pendingElement() ?? this.turnElements().at(-1);
        target?.nativeElement.scrollIntoView({ block: 'start' });
      },
      { injector: this.injector },
    );
  }
}
