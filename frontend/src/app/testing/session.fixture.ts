import { AuthResponse } from '../core/auth/auth.model';

/** A signed-in session as returned by POST /api/v1/auth/login (test data). */
export const SESSION: AuthResponse = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresAt: '2026-11-08T00:15:00Z',
  user: {
    id: 'u1',
    email: 'jan@example.com',
    fullName: 'Jan Kowalski',
    locale: 'pl',
    timezone: 'Europe/Warsaw',
    platformRole: 'USER',
    emailVerified: true,
    createdAt: '2026-11-01T00:00:00Z',
  },
};
