import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ConversationService } from './conversation.service';

describe('ConversationService', () => {
  let service: ConversationService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ConversationService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('asks for a page of conversations', () => {
    service.list(2, 20).subscribe();

    const request = http.expectOne('/api/conversations?page=2&size=20');
    expect(request.request.method).toBe('GET');
    request.flush({ items: [], page: 2, size: 20, totalItems: 0 });
  });

  it('starts a conversation with the question and the chosen documents', () => {
    service.start('The question?', ['doc-1']).subscribe();

    const request = http.expectOne('/api/conversations');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ question: 'The question?', documentIds: ['doc-1'] });
    request.flush({});
  });

  it('adds a question to a conversation', () => {
    service.addTurn('conv-1', 'And then?').subscribe();

    const request = http.expectOne('/api/conversations/conv-1/turns');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ question: 'And then?', documentIds: [] });
    request.flush({});
  });

  it('gets and deletes one conversation', () => {
    service.get('conv-1').subscribe();
    const read = http.expectOne('/api/conversations/conv-1');
    expect(read.request.method).toBe('GET');
    read.flush({});

    service.delete('conv-1').subscribe();
    const removal = http.expectOne('/api/conversations/conv-1');
    expect(removal.request.method).toBe('DELETE');
    removal.flush(null);
  });
});
