import { Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideFileText, LucideLoaderCircle, LucideSparkles, LucideTrash2, LucideUpload } from '@lucide/angular';
import { Observable } from 'rxjs';
import { AiQuota, AiService, Analysis, AnalysisSource, TenderDocument } from '../../core/api/ai.service';
import { ApiError, toApiError } from '../../core/api/api-error';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

const POLL_MS = 3000;

/**
 * Tender documents and the AI analysis built from them. Every finding shows its sources: the exact quote the API
 * extracted from the document and the page. Nothing is shown as a quote unless it came from a citation.
 */
@Component({
  selector: 'eti-tender-analysis',
  imports: [TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, LucideFileText, LucideLoaderCircle, LucideSparkles,
    LucideTrash2, LucideUpload],
  templateUrl: './tender-analysis.component.html',
})
export class TenderAnalysisComponent {
  private readonly api = inject(AiService);
  private readonly transloco = inject(TranslocoService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);

  readonly tenderId = input.required<string>();
  readonly documentsUrl = input<string | undefined>();

  protected readonly documents = signal<TenderDocument[]>([]);
  protected readonly analysis = signal<Analysis | null>(null);
  protected readonly quota = signal<AiQuota | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly busy = signal(false);
  protected readonly uploading = signal(false);
  protected readonly fetchInfo = signal<{ status: string; detail?: string } | null>(null);
  protected readonly openSources = signal<Set<string>>(new Set());
  private timer: ReturnType<typeof setTimeout> | null = null;

  protected readonly running = computed(() => {
    const s = this.analysis()?.status;
    return s === 'PENDING' || s === 'RUNNING';
  });
  protected readonly usableDocs = computed(() => this.documents().filter((d) => d.extractionStatus === 'DONE'));
  protected readonly failedFetch = computed(() =>
    this.documents().filter((d) => d.source === 'PORTAL' && d.fetchStatus !== 'FETCHED'),
  );
  protected readonly files = computed(() => this.documents().filter((d) => d.fetchStatus === 'FETCHED'));
  protected readonly canAnalyze = computed(() => {
    const q = this.quota();
    return !!q?.configured && !this.running() && !this.busy();
  });
  /** A new run is needed when nothing is done yet, the last run failed, or documents changed since. */
  protected readonly needsRun = computed(() => {
    const a = this.analysis();
    return !a || a.status === 'FAILED' || (a.status === 'DONE' && a.stale);
  });

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const tenderId = this.tenderId();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.load(orgId, tenderId, lang));
      }
    });
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  private load(orgId: string, tenderId: string, lang: string): void {
    this.stopPolling();
    this.api.quota(orgId).subscribe({ next: (q) => this.quota.set(q) });
    this.api.documents(orgId, tenderId).subscribe({ next: (d) => this.documents.set(d) });
    this.loadAnalysis(orgId, tenderId, lang);
  }

  private loadAnalysis(orgId: string, tenderId: string, lang: string): void {
    this.api.latest(orgId, tenderId, lang).subscribe({
      next: (a) => this.setAnalysis(a),
      error: () => this.analysis.set(null), // 404 analysis_missing
    });
  }

  private setAnalysis(a: Analysis): void {
    this.analysis.set(a);
    this.stopPolling();
    if (a.status === 'PENDING' || a.status === 'RUNNING') {
      this.timer = setTimeout(() => {
        const orgId = this.org.currentId();
        if (orgId) {
          this.loadAnalysis(orgId, this.tenderId(), this.language.current());
        }
      }, POLL_MS);
    } else if (a.status === 'DONE' || a.status === 'FAILED') {
      const orgId = this.org.currentId();
      if (orgId) {
        this.api.quota(orgId).subscribe({ next: (q) => this.quota.set(q) });
      }
    }
  }

  private stopPolling(): void {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  protected analyze(): void {
    const orgId = this.org.currentId();
    if (!orgId || !this.canAnalyze()) {
      return;
    }
    this.run(this.api.analyze(orgId, this.tenderId(), this.language.current()), (a) => this.setAnalysis(a));
  }

  protected onFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    const orgId = this.org.currentId();
    if (!orgId || !files.length) {
      return;
    }
    this.uploading.set(true);
    this.error.set(null);
    const next = (i: number): void => {
      if (i >= files.length) {
        this.uploading.set(false);
        this.refreshAfterDocumentsChange(orgId);
        return;
      }
      this.api.upload(orgId, this.tenderId(), files[i]).subscribe({
        next: () => next(i + 1),
        error: (err: unknown) => {
          this.uploading.set(false);
          this.error.set(toApiError(err));
          this.refreshAfterDocumentsChange(orgId);
        },
      });
    };
    next(0);
  }

  protected remove(doc: TenderDocument): void {
    const orgId = this.org.currentId();
    if (!orgId || !confirm(this.transloco.translate('analysis.removeConfirm', { name: doc.fileName }))) {
      return;
    }
    this.run(this.api.deleteDocument(orgId, this.tenderId(), doc.id), () => this.refreshAfterDocumentsChange(orgId));
  }

  protected fetchPublic(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.run(this.api.fetchPublic(orgId, this.tenderId()), (r) => {
      this.fetchInfo.set({ status: r.status, detail: r.detail });
      this.documents.set(r.documents);
      this.loadAnalysis(orgId, this.tenderId(), this.language.current());
    });
  }

  private refreshAfterDocumentsChange(orgId: string): void {
    this.api.documents(orgId, this.tenderId()).subscribe({ next: (d) => this.documents.set(d) });
    this.loadAnalysis(orgId, this.tenderId(), this.language.current());
  }

  protected toggleSources(key: string): void {
    this.openSources.update((set) => {
      const next = new Set(set);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  protected sourceLabel(s: AnalysisSource): string {
    const title = s.documentTitle ?? '';
    if (s.pageFrom == null) {
      return title;
    }
    const pages = s.pageTo != null && s.pageTo !== s.pageFrom ? `${s.pageFrom}–${s.pageTo}` : `${s.pageFrom}`;
    return `${title}, ${this.transloco.translate('analysis.page', { pages })}`;
  }

  protected size(bytes?: number): string {
    if (bytes == null) {
      return '';
    }
    const lang = this.language.current();
    const mb = bytes / (1024 * 1024);
    return mb >= 1
      ? `${mb.toLocaleString(lang, { maximumFractionDigits: 1 })} MB`
      : `${Math.max(1, Math.round(bytes / 1024)).toLocaleString(lang)} kB`;
  }

  private run<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.busy.set(true);
    this.error.set(null);
    request.subscribe({
      next: (value) => {
        this.busy.set(false);
        onSuccess(value);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(toApiError(err));
      },
    });
  }
}
