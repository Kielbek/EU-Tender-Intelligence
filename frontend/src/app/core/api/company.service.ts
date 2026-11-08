import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MatchView } from './matching.model';
import { Page } from './tender.model';

export type ProfileContractType = 'WORKS' | 'SUPPLIES' | 'SERVICES';
export type CompanySize = 'MICRO' | 'SMALL' | 'MEDIUM' | 'LARGE';

export interface Certification {
  id: string;
  name: string;
  issuer?: string;
  validUntil?: string;
}

export interface CompanyReference {
  id: string;
  title: string;
  client?: string;
  countryCode?: string;
  value?: number;
  currency?: string;
  yearCompleted?: number;
  cpvCode?: string;
  description?: string;
}

export interface CompanyProfile {
  id: string;
  name: string;
  registrationCountry?: string;
  industry?: string;
  description?: string;
  website?: string;
  countriesOfInterest: string[];
  contractTypes: ProfileContractType[];
  companySize?: CompanySize;
  employees?: number;
  annualRevenue?: number;
  revenueCurrency?: string;
  yearsOfExperience?: number;
  minContractValueEur?: number;
  maxContractValueEur?: number;
  technicalResources?: string;
  staffResources?: string;
  financialCapacity?: string;
  cpvCodes: string[];
  cpvLabels: Record<string, string>;
  certifications: Certification[];
  references: CompanyReference[];
  updatedAt: string;
}

export type CompanyProfileRequest = Omit<CompanyProfile, 'id' | 'cpvLabels' | 'certifications' | 'references' | 'updatedAt'>;
export type CertificationRequest = Omit<Certification, 'id'>;
export type ReferenceRequest = Omit<CompanyReference, 'id'>;

@Injectable({ providedIn: 'root' })
export class CompanyService {
  private readonly http = inject(HttpClient);

  private base(orgId: string): string {
    return `/api/v1/organizations/${orgId}/company-profile`;
  }

  get(orgId: string, lang: string): Observable<CompanyProfile> {
    return this.http.get<CompanyProfile>(this.base(orgId), { params: { lang } });
  }

  save(orgId: string, body: CompanyProfileRequest, lang: string): Observable<CompanyProfile> {
    return this.http.put<CompanyProfile>(this.base(orgId), body, { params: { lang } });
  }

  addCertification(orgId: string, body: CertificationRequest): Observable<Certification> {
    return this.http.post<Certification>(`${this.base(orgId)}/certifications`, body);
  }

  deleteCertification(orgId: string, id: string): Observable<void> {
    return this.http.delete<void>(`${this.base(orgId)}/certifications/${id}`);
  }

  addReference(orgId: string, body: ReferenceRequest): Observable<CompanyReference> {
    return this.http.post<CompanyReference>(`${this.base(orgId)}/references`, body);
  }

  deleteReference(orgId: string, id: string): Observable<void> {
    return this.http.delete<void>(`${this.base(orgId)}/references/${id}`);
  }
}

@Injectable({ providedIn: 'root' })
export class MatchingService {
  private readonly http = inject(HttpClient);

  list(orgId: string, minScore: number, page: number, size: number, lang: string): Observable<Page<MatchView>> {
    return this.http.get<Page<MatchView>>(`/api/v1/organizations/${orgId}/matches`, {
      params: { minScore, page, size, lang },
    });
  }

  explain(orgId: string, tenderId: string, lang: string): Observable<MatchView> {
    return this.http.get<MatchView>(`/api/v1/organizations/${orgId}/matches/${tenderId}`, { params: { lang } });
  }
}
