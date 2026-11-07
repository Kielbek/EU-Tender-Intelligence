/**
 * Interface languages that have a complete translation file in public/i18n.
 * Further EU languages are added only together with their translations – never as empty options.
 */
export const AVAILABLE_LANGUAGES = ['pl', 'en'] as const;
export type Language = (typeof AVAILABLE_LANGUAGES)[number];
export const DEFAULT_LANGUAGE: Language = 'pl';
export const LANGUAGE_STORAGE_KEY = 'eti.lang';

export function isLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (AVAILABLE_LANGUAGES as readonly string[]).includes(value);
}
