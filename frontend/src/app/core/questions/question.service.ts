import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Answer } from './question.model';

@Injectable({ providedIn: 'root' })
export class QuestionService {
  private readonly http = inject(HttpClient);

  /** With no document ids, the question covers all of the user's documents. */
  ask(question: string, documentIds: readonly string[] = []): Observable<Answer> {
    return this.http.post<Answer>('/api/questions', { question, documentIds });
  }
}
