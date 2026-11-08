import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type DocumentScope = 'PUBLIC' | 'ORGANIZATION';

export interface TenderDocument {
  id: string;
  fileName?: string;
  mimeType?: string;
  sizeBytes?: number;
  source: 'PORTAL' | 'UPLOAD';
  scope: DocumentScope;
  fetchStatus: 'PENDING' | 'FETCHED' | 'FAILED' | 'NOT_ACCESSIBLE' | 'REQUIRES_LOGIN';
  extractionStatus: 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED' | 'NO_TEXT_LAYER' | 'UNSUPPORTED';
  pageCount?: number;
  textChars?: number;
  error?: string;
  createdAt: string;
}

export interface FetchResult {
  status: 'FETCHED' | 'REQUIRES_LOGIN' | 'NOT_ACCESSIBLE';
  detail?: string;
  documents: TenderDocument[];
}

export interface AnalysisSource {
  documentIndex: number;
  documentId?: string;
  documentTitle?: string;
  /** Exact text extracted by the API from the document (never generated). */
  quote: string;
  pageFrom?: number;
  pageTo?: number;
}

export interface AnalysisFinding {
  text: string;
  cited: boolean;
  sources: AnalysisSource[];
}

export type AnalysisSectionKey =
  | 'SUMMARY'
  | 'SCOPE'
  | 'DEADLINES'
  | 'CONDITIONS'
  | 'DOCUMENTS'
  | 'CRITERIA'
  | 'SECURITY'
  | 'CONTRACT'
  | 'RISKS';

export interface AnalysisSection {
  key: AnalysisSectionKey;
  notFound: boolean;
  findings: AnalysisFinding[];
}

export interface AnalysisResult {
  sections: AnalysisSection[];
  documents: { id?: string; title: string; kind: 'NOTICE' | 'PUBLIC' | 'ORGANIZATION' }[];
  skippedDocuments: { id?: string; title: string; kind: string }[];
  droppedUncited: number;
  truncated: boolean;
}

export interface Analysis {
  id: string;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';
  language: string;
  scope: DocumentScope;
  model?: string;
  createdAt: string;
  completedAt?: string;
  errorCode?: string;
  stale: boolean;
  result?: AnalysisResult;
}

export interface AiQuota {
  configured: boolean;
  planCode: string;
  limit: number;
  used: number;
  remaining: number;
}

@Injectable({ providedIn: 'root' })
export class AiService {
  private readonly http = inject(HttpClient);

  private tender(orgId: string, tenderId: string): string {
    return `/api/v1/organizations/${orgId}/tenders/${tenderId}`;
  }

  quota(orgId: string): Observable<AiQuota> {
    return this.http.get<AiQuota>(`/api/v1/organizations/${orgId}/ai/quota`);
  }

  documents(orgId: string, tenderId: string): Observable<TenderDocument[]> {
    return this.http.get<TenderDocument[]>(`${this.tender(orgId, tenderId)}/documents`);
  }

  upload(orgId: string, tenderId: string, file: File): Observable<TenderDocument[]> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<TenderDocument[]>(`${this.tender(orgId, tenderId)}/documents`, body);
  }

  deleteDocument(orgId: string, tenderId: string, documentId: string): Observable<void> {
    return this.http.delete<void>(`${this.tender(orgId, tenderId)}/documents/${documentId}`);
  }

  fetchPublic(orgId: string, tenderId: string): Observable<FetchResult> {
    return this.http.post<FetchResult>(`${this.tender(orgId, tenderId)}/documents/fetch-public`, {});
  }

  latest(orgId: string, tenderId: string, lang: string): Observable<Analysis> {
    return this.http.get<Analysis>(`${this.tender(orgId, tenderId)}/analyses/latest`, { params: { lang } });
  }

  analyze(orgId: string, tenderId: string, lang: string): Observable<Analysis> {
    return this.http.post<Analysis>(`${this.tender(orgId, tenderId)}/analyses`, {}, { params: { lang } });
  }
}
