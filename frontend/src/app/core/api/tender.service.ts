import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AlertFrequency,
  CpvOption,
  DashboardSummary,
  Page,
  SavedSearch,
  SavedTender,
  SearchFilters,
  TenderDetail,
  TenderSummary,
  TenderVersion,
  WatchStatus,
} from './tender.model';

/** Converts filters to query params; arrays become repeated parameters (country=DE&country=PL). */
export function toParams(filters: SearchFilters, extra: Record<string, string | number> = {}): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries({ ...filters, ...extra })) {
    if (value === undefined || value === null || value === '') {
      continue;
    }
    if (Array.isArray(value)) {
      value.filter((v) => v !== '').forEach((v) => (params = params.append(key, v)));
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}

@Injectable({ providedIn: 'root' })
export class TenderService {
  private readonly http = inject(HttpClient);

  search(filters: SearchFilters, page: number, size: number, lang: string): Observable<Page<TenderSummary>> {
    return this.http.get<Page<TenderSummary>>('/api/v1/tenders', { params: toParams(filters, { page, size, lang }) });
  }

  get(id: string, lang: string): Observable<TenderDetail> {
    return this.http.get<TenderDetail>(`/api/v1/tenders/${id}`, { params: { lang } });
  }

  versions(id: string): Observable<TenderVersion[]> {
    return this.http.get<TenderVersion[]>(`/api/v1/tenders/${id}/versions`);
  }

  /** Counts against the organization's monthly export limit. */
  export(orgId: string, filters: SearchFilters, format: 'csv' | 'xlsx', lang: string): Observable<HttpResponse<Blob>> {
    return this.http.get('/api/v1/tenders/export', {
      params: toParams(filters, { format, lang, orgId }),
      responseType: 'blob',
      observe: 'response',
    });
  }

  cpv(query: string, lang: string): Observable<CpvOption[]> {
    return this.http.get<CpvOption[]>('/api/v1/cpv', { params: { query, lang, limit: 15 } });
  }

  /** Children of a CPV code, or the 45 divisions when {@code parent} is null (for browsing the tree). */
  cpvChildren(parent: string | null, lang: string): Observable<CpvOption[]> {
    return this.http.get<CpvOption[]>('/api/v1/cpv', { params: parent ? { parent, lang, limit: 100 } : { lang, limit: 100 } });
  }

  dashboard(orgId: string, lang: string): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>(`/api/v1/organizations/${orgId}/dashboard`, { params: { lang } });
  }
}

@Injectable({ providedIn: 'root' })
export class WorkspaceService {
  private readonly http = inject(HttpClient);

  savedTenders(orgId: string, lang: string, status?: WatchStatus): Observable<SavedTender[]> {
    const params: Record<string, string> = { lang };
    if (status) {
      params['status'] = status;
    }
    return this.http.get<SavedTender[]>(`/api/v1/organizations/${orgId}/saved-tenders`, { params });
  }

  savedTender(orgId: string, tenderId: string, lang: string): Observable<SavedTender> {
    return this.http.get<SavedTender>(`/api/v1/organizations/${orgId}/saved-tenders/${tenderId}`, {
      params: { lang },
    });
  }

  saveTender(
    orgId: string,
    tenderId: string,
    changes: { status?: WatchStatus; notes?: string },
    lang: string,
  ): Observable<SavedTender> {
    return this.http.put<SavedTender>(`/api/v1/organizations/${orgId}/saved-tenders/${tenderId}`, changes, {
      params: { lang },
    });
  }

  unsaveTender(orgId: string, tenderId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/organizations/${orgId}/saved-tenders/${tenderId}`);
  }

  savedSearches(orgId: string): Observable<SavedSearch[]> {
    return this.http.get<SavedSearch[]>(`/api/v1/organizations/${orgId}/saved-searches`);
  }

  createSearch(orgId: string, name: string, filters: SearchFilters): Observable<SavedSearch> {
    return this.http.post<SavedSearch>(`/api/v1/organizations/${orgId}/saved-searches`, { name, filters });
  }

  renameSearch(orgId: string, id: string, name: string): Observable<SavedSearch> {
    return this.http.patch<SavedSearch>(`/api/v1/organizations/${orgId}/saved-searches/${id}`, { name });
  }

  markSearchRun(orgId: string, id: string): Observable<SavedSearch> {
    return this.http.post<SavedSearch>(`/api/v1/organizations/${orgId}/saved-searches/${id}/run`, null);
  }

  setSearchAlert(orgId: string, id: string, enabled: boolean, frequency: AlertFrequency): Observable<SavedSearch> {
    return this.http.put<SavedSearch>(`/api/v1/organizations/${orgId}/saved-searches/${id}/alert`, { enabled, frequency });
  }

  deleteSearch(orgId: string, id: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/organizations/${orgId}/saved-searches/${id}`);
  }
}
