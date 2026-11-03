import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocumentList, UserDocument } from './document.model';

const DOCUMENTS_URL = '/api/documents';

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private readonly http = inject(HttpClient);

  list(page: number, size: number): Observable<DocumentList> {
    return this.http.get<DocumentList>(DOCUMENTS_URL, { params: { page, size } });
  }

  upload(file: File): Observable<UserDocument> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<UserDocument>(DOCUMENTS_URL, form);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${DOCUMENTS_URL}/${id}`);
  }
}
