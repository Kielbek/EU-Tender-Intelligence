/** Mirrors eu.tenderintel.identity.AuthDtos. */
export interface Me {
  id: string;
  email: string;
  fullName: string;
  locale: string;
  timezone: string;
  platformRole: 'USER' | 'PLATFORM_ADMIN';
  emailVerified: boolean;
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresAt: string;
  user: Me;
}

export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
  organizationName?: string;
  locale: string;
  acceptTerms: boolean;
}

export const PASSWORD_MIN_LENGTH = 12;
