import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocumentList, DocumentPage, UserDocument } from './document.model';

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

  retry(id: string): Observable<UserDocument> {
    return this.http.post<UserDocument>(`${DOCUMENTS_URL}/${id}/retry`, null);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${DOCUMENTS_URL}/${id}`);
  }

  getPage(id: string, pageNumber: number): Observable<DocumentPage> {
    return this.http.get<DocumentPage>(`${DOCUMENTS_URL}/${id}/pages/${pageNumber}`);
  }

  /** For a link. The browser's PDF reader uses the fragment to open at that page, if it supports it. */
  fileUrl(id: string, pageNumber?: number): string {
    const url = `${DOCUMENTS_URL}/${id}/file`;
    return pageNumber === undefined ? url : `${url}#page=${pageNumber}`;
  }
}
