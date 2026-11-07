import { HttpErrorResponse } from '@angular/common/http';

/** Normalised error shown in the UI. Backend errors follow RFC 9457 and carry a correlationId. */
export interface ApiError {
  status: number;
  detail?: string;
  correlationId?: string;
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof HttpErrorResponse) {
    const body = typeof error.error === 'object' && error.error !== null ? error.error : {};
    return {
      status: error.status,
      detail: typeof body.detail === 'string' ? body.detail : undefined,
      correlationId:
        (typeof body.correlationId === 'string' ? body.correlationId : undefined) ??
        error.headers?.get('X-Request-Id') ??
        undefined,
    };
  }
  return { status: 0 };
}
