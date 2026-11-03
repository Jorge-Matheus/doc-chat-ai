import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { UserDocument } from '../../core/documents/document.model';
import { DocumentService } from '../../core/documents/document.service';
import { errorMessage } from '../../core/http/error-message';
import { Answer } from '../../core/questions/question.model';
import { QuestionService } from '../../core/questions/question.service';

/** Keep in sync with QuestionRequest in the backend. */
const MAX_QUESTION_LENGTH = 2000;
/** The most the API returns at once. Questions over all documents are not affected by it. */
const MAX_LISTED_DOCUMENTS = 100;

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
  ],
  templateUrl: './ask.html',
  styleUrl: './ask.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Ask {
  private readonly questions = inject(QuestionService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly maxQuestionLength = MAX_QUESTION_LENGTH;
  protected readonly form = inject(NonNullableFormBuilder).group({
    documentId: [''],
    question: ['', [Validators.required, Validators.pattern(/\S/)]],
  });
  protected readonly readyDocuments = signal<readonly UserDocument[]>([]);
  protected readonly loadingDocuments = signal(true);
  protected readonly documentsLoadFailed = signal(false);
  protected readonly asking = signal(false);
  protected readonly answer = signal<Answer | null>(null);
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
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { question, documentId } = this.form.getRawValue();
    this.asking.set(true);
    this.answer.set(null);
    this.error.set(null);
    this.questions
      .ask(question.trim(), documentId ? [documentId] : [])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (answer) => {
          this.asking.set(false);
          this.answer.set(answer);
        },
        error: (error: unknown) => {
          this.asking.set(false);
          this.error.set(errorMessage(error, 'The question could not be answered. Try again.'));
        },
      });
  }
}
