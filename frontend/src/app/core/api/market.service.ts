import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TenderSummary } from './tender.model';

/** What the statistics cover; `dataSince` is the oldest award in the database (import coverage). */
export interface MarketScope {
  countryCode?: string;
  cpvPrefix?: string;
  cpvLabel?: string;
  months: number;
  since: string;
  dataSince?: string;
}

/** Null values = too few awards to say anything (fewer than 5). Ratios are awarded value / estimated value. */
export interface CompetitionStats {
  awardedTenders: number;
  medianOffers?: number | null;
  singleBidShare?: number | null;
  offersSample: number;
  priceRatioP25?: number | null;
  priceRatioMedian?: number | null;
  priceRatioP75?: number | null;
  priceRatioSample: number;
  medianAwardEur?: number | null;
}

export interface WinnerRow {
  id: string;
  name: string;
  countryCode?: string;
  wins: number;
  totalEur?: number | null;
  lastWin?: string;
}

export interface AwardRow {
  tenderId: string;
  title: string;
  countryCode: string;
  authorityId?: string;
  authorityName?: string;
  winnerId: string;
  winnerName: string;
  valueEur?: number | null;
  offers?: number | null;
  awardDate?: string;
}

export interface MarketBucket {
  key: string;
  label?: string;
  count: number;
}

export interface TenderMarket {
  scope: MarketScope;
  stats: CompetitionStats;
  topWinners: WinnerRow[];
  recentAwards: AwardRow[];
  authority?: { id: string; name: string; stats: CompetitionStats; topWinners: WinnerRow[] };
}

export interface AuthorityProfile {
  id: string;
  name: string;
  countryCode?: string;
  city?: string;
  nationalId?: string;
  website?: string;
  scope: MarketScope;
  tendersPublished: number;
  openNow: number;
  cancelled: number;
  stats: CompetitionStats;
  topCpv: MarketBucket[];
  topWinners: WinnerRow[];
  recentAwards: AwardRow[];
  openTenders: TenderSummary[];
}

export interface CompanyProfile {
  id: string;
  name: string;
  countryCode?: string;
  nationalId?: string;
  sme?: boolean | null;
  scope: MarketScope;
  wins: number;
  totalEur?: number | null;
  medianOffers?: number | null;
  topCpv: MarketBucket[];
  countries: MarketBucket[];
  topAuthorities: { id: string; name: string; countryCode?: string; count: number; totalEur?: number | null }[];
  recentWins: AwardRow[];
  watched: boolean;
}

export interface CompanyHit {
  id: string;
  name: string;
  countryCode?: string;
  nationalId?: string;
  wins: number;
}

export interface AuthorityHit {
  id: string;
  name: string;
  countryCode?: string;
  city?: string;
  tenders: number;
}

export interface Competitor {
  id: string;
  name: string;
  countryCode?: string;
  nationalId?: string;
  winsInWindow: number;
  totalEurInWindow?: number | null;
  lastWin?: string;
  note?: string;
  addedAt: string;
}

export interface CompetitorsView {
  items: Competitor[];
  limit: number;
  months: number;
}

@Injectable({ providedIn: 'root' })
export class MarketService {
  private readonly http = inject(HttpClient);

  tender(orgId: string, tenderId: string, lang: string): Observable<TenderMarket> {
    return this.http.get<TenderMarket>(`/api/v1/organizations/${orgId}/market/tenders/${tenderId}`, { params: { lang } });
  }

  authority(orgId: string, id: string, lang: string): Observable<AuthorityProfile> {
    return this.http.get<AuthorityProfile>(`/api/v1/organizations/${orgId}/market/authorities/${id}`, { params: { lang } });
  }

  company(orgId: string, id: string, lang: string): Observable<CompanyProfile> {
    return this.http.get<CompanyProfile>(`/api/v1/organizations/${orgId}/market/companies/${id}`, { params: { lang } });
  }

  searchCompanies(orgId: string, q: string): Observable<CompanyHit[]> {
    return this.http.get<CompanyHit[]>(`/api/v1/organizations/${orgId}/market/companies`, { params: { q } });
  }

  searchAuthorities(orgId: string, q: string): Observable<AuthorityHit[]> {
    return this.http.get<AuthorityHit[]>(`/api/v1/organizations/${orgId}/market/authorities`, { params: { q } });
  }

  competitors(orgId: string): Observable<CompetitorsView> {
    return this.http.get<CompetitorsView>(`/api/v1/organizations/${orgId}/competitors`);
  }

  watch(orgId: string, operatorId: string, note?: string): Observable<void> {
    return this.http.put<void>(`/api/v1/organizations/${orgId}/competitors/${operatorId}`, { note: note ?? null });
  }

  unwatch(orgId: string, operatorId: string): Observable<void> {
    return this.http.delete<void>(`/api/v1/organizations/${orgId}/competitors/${operatorId}`);
  }
}
