import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConversationDetails, ConversationList, Turn } from './conversation.model';

const CONVERSATIONS_URL = '/api/conversations';

@Injectable({ providedIn: 'root' })
export class ConversationService {
  private readonly http = inject(HttpClient);

  list(page: number, size: number): Observable<ConversationList> {
    return this.http.get<ConversationList>(CONVERSATIONS_URL, { params: { page, size } });
  }

  get(id: string): Observable<ConversationDetails> {
    return this.http.get<ConversationDetails>(`${CONVERSATIONS_URL}/${id}`);
  }

  /** With no document ids, the question covers all of the user's documents. */
  start(question: string, documentIds: readonly string[] = []): Observable<ConversationDetails> {
    return this.http.post<ConversationDetails>(CONVERSATIONS_URL, { question, documentIds });
  }

  /** With no document ids, the question covers all of the user's documents. */
  addTurn(id: string, question: string, documentIds: readonly string[] = []): Observable<Turn> {
    return this.http.post<Turn>(`${CONVERSATIONS_URL}/${id}/turns`, { question, documentIds });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${CONVERSATIONS_URL}/${id}`);
  }
}
