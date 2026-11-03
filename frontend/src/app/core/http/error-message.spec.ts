import { HttpErrorResponse } from '@angular/common/http';
import { errorMessage } from './error-message';

const FALLBACK = 'Something went wrong.';

describe('errorMessage', () => {
  it('uses the detail of a problem response', () => {
    const error = new HttpErrorResponse({ status: 409, error: { detail: 'Already registered.' } });

    expect(errorMessage(error, FALLBACK)).toBe('Already registered.');
  });

  it('prefers the messages of invalid fields', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: {
        detail: 'Invalid request content.',
        errors: [
          { field: 'email', message: 'Bad email.' },
          { field: 'password', message: 'Bad password.' },
        ],
      },
    });

    expect(errorMessage(error, FALLBACK)).toBe('Bad email. Bad password.');
  });

  it('uses the fallback for the HTML page of a proxy', () => {
    const error = new HttpErrorResponse({ status: 413, error: '<html>Too large</html>' });

    expect(errorMessage(error, FALLBACK)).toBe(FALLBACK);
  });

  it('uses the fallback when the network fails', () => {
    const error = new HttpErrorResponse({ status: 0, error: new ProgressEvent('error') });

    expect(errorMessage(error, FALLBACK)).toBe(FALLBACK);
  });

  it('uses the fallback for errors that did not come from a request', () => {
    expect(errorMessage(new Error('internal details'), FALLBACK)).toBe(FALLBACK);
  });
});
