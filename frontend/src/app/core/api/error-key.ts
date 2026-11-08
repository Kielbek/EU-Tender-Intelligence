import { ApiError } from './api-error';

/** Backend problem codes that have a dedicated, user-friendly translation under "errors.*". */
const KNOWN_CODES = new Set([
  'invalid_credentials',
  'too_many_attempts',
  'rate_limited',
  'email_not_verified',
  'account_disabled',
  'invalid_token',
  'weak_password',
  'invalid_current_password',
  'validation_failed',
  'last_admin',
  'already_member',
  'invitation_email_mismatch',
  'forbidden',
  'not_found',
  'unsupported_locale',
  'invalid_timezone',
  'invalid_filters',
  'duplicate_name',
]);

export function errorKey(error: ApiError): string {
  if (error.status === 0) {
    return 'errors.network';
  }
  if (error.code && KNOWN_CODES.has(error.code)) {
    return `errors.${error.code}`;
  }
  return 'errors.generic';
}
