import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { toApiError } from './api-error';

describe('toApiError', () => {
  it('reads RFC 9457 problem details', () => {
    const err = new HttpErrorResponse({ status: 500, error: { detail: 'boom', correlationId: 'abc-123456' } });
    expect(toApiError(err)).toEqual({ status: 500, detail: 'boom', correlationId: 'abc-123456' });
  });

  it('falls back to the X-Request-Id header', () => {
    const err = new HttpErrorResponse({ status: 502, error: 'Bad Gateway', headers: new HttpHeaders({ 'X-Request-Id': 'hdr-123456' }) });
    expect(toApiError(err)).toEqual({ status: 502, detail: undefined, correlationId: 'hdr-123456' });
  });

  it('handles non-HTTP errors', () => {
    expect(toApiError(new Error('x'))).toEqual({ status: 0 });
  });
});
