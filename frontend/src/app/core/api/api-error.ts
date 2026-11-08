import { HttpErrorResponse } from '@angular/common/http';

/** Normalised error shown in the UI. Backend errors follow RFC 9457 and carry a correlationId. */
export interface ApiError {
  status: number;
  code?: string;
  detail?: string;
  correlationId?: string;
  /** Plan limits (402 responses): the limit, current usage and plan code. */
  limit?: number;
  used?: number;
  plan?: string;
}

export function toApiError(error: unknown): ApiError {
  if (error instanceof HttpErrorResponse) {
    const body = typeof error.error === 'object' && error.error !== null ? error.error : {};
    return {
      status: error.status,
      code: typeof body.code === 'string' ? body.code : undefined,
      detail: typeof body.detail === 'string' ? body.detail : undefined,
      correlationId:
        (typeof body.correlationId === 'string' ? body.correlationId : undefined) ??
        error.headers?.get('X-Request-Id') ??
        undefined,
      limit: typeof body.limit === 'number' ? body.limit : undefined,
      used: typeof body.used === 'number' ? body.used : undefined,
      plan: typeof body.plan === 'string' ? body.plan : undefined,
    };
  }
  return { status: 0 };
}

/** For requests with {@code responseType: 'blob'} (downloads) the problem body arrives as a Blob. */
export async function toApiErrorFromBlob(error: unknown): Promise<ApiError> {
  if (error instanceof HttpErrorResponse && error.error instanceof Blob) {
    try {
      const body: unknown = JSON.parse(await error.error.text());
      return toApiError(new HttpErrorResponse({ error: body, status: error.status, headers: error.headers }));
    } catch {
      return toApiError(new HttpErrorResponse({ status: error.status, headers: error.headers }));
    }
  }
  return toApiError(error);
}
