import './locale-data';
import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { DEFAULT_LANGUAGE, LANGUAGE_STORAGE_KEY, Language, isLanguage } from './languages';

/**
 * Active UI language. Before login the choice is remembered in localStorage; from Stage 2 it is also stored in the
 * user profile on the backend.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);
  private readonly document = inject(DOCUMENT);
  readonly current = signal<Language>(DEFAULT_LANGUAGE);

  init(): void {
    this.use(this.readStored() ?? DEFAULT_LANGUAGE, false);
  }

  use(lang: Language, persist = true): void {
    this.current.set(lang);
    this.transloco.setActiveLang(lang);
    this.document.documentElement.lang = lang;
    if (persist) {
      try {
        localStorage.setItem(LANGUAGE_STORAGE_KEY, lang);
      } catch {
        // Storage unavailable (private mode, blocked) – the choice lasts for this session only.
      }
    }
  }

  private readStored(): Language | null {
    try {
      const stored = localStorage.getItem(LANGUAGE_STORAGE_KEY);
      return isLanguage(stored) ? stored : null;
    } catch {
      return null;
    }
  }
}
