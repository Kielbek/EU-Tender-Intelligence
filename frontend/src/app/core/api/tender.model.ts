import { MatchView } from './matching.model';
/** Mirrors eu.tenderintel.tender.TenderDtos and workspace/dashboard DTOs. */
export type TenderStatus = 'PLANNED' | 'OPEN' | 'CLOSED' | 'AWARDED' | 'CANCELLED' | 'UNKNOWN';
export type ContractType = 'WORKS' | 'SUPPLIES' | 'SERVICES' | 'MIXED';
export type ChangeKind =
  | 'PRIOR_INFORMATION'
  | 'COMPETITION'
  | 'CHANGE'
  | 'CANCELLATION'
  | 'AWARD'
  | 'MODIFICATION'
  | 'OTHER';
export type WatchStatus = 'WATCHING' | 'PREPARING' | 'SUBMITTED' | 'WON' | 'LOST' | 'DROPPED';

export interface TenderSummary {
  id: string;
  title: string;
  countryCode: string;
  city?: string;
  authorityName?: string;
  mainCpv?: string;
  mainCpvLabel?: string;
  contractType?: ContractType;
  procedureType?: string;
  estimatedValue?: number;
  currency?: string;
  estimatedValueEur?: number;
  publishedAt: string;
  submissionDeadline?: string;
  status: TenderStatus;
  originalUrl?: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface TenderDetail {
  id: string;
  title: string;
  titleLang?: string;
  description?: string;
  countryCode: string;
  nutsCode?: string;
  city?: string;
  authority?: { id: string; name: string; nationalId?: string; countryCode?: string; city?: string; website?: string };
  referenceNumber?: string;
  contractType?: ContractType;
  procedureType?: string;
  mainCpv?: string;
  cpvCodes: string[];
  cpvLabels: Record<string, string>;
  estimatedValue?: number;
  currency?: string;
  estimatedValueEur?: number;
  publishedAt: string;
  submissionDeadline?: string;
  submissionDeadlineTz?: string;
  openingAt?: string;
  status: TenderStatus;
  noticeLanguage?: string;
  originalUrl?: string;
  documentsUrl?: string;
  currentVersionNo: number;
  lastChangedAt: string;
  sources: { dataSource: string; externalId: string; externalUrl?: string; noticeType?: string; firstSeenAt: string }[];
  lots: { lotNumber: string; title?: string; estimatedValue?: number; currency?: string; submissionDeadline?: string }[];
  results: {
    winnerName?: string;
    winnerCountry?: string;
    winnerSize?: string;
    awardedValue?: number;
    currency?: string;
    awardedValueEur?: number;
    tendersReceived?: number;
    awardDate?: string;
  }[];
}

export interface DiffEntry {
  field: string;
  old?: unknown;
  new?: unknown;
}

export interface TenderVersion {
  versionNo: number;
  changeKind: ChangeKind;
  noticeType?: string;
  publishedAt: string;
  diff: DiffEntry[];
}

export interface SavedTender {
  tenderId: string;
  status: WatchStatus;
  notes?: string;
  createdByName?: string;
  createdAt: string;
  updatedAt: string;
  tender?: TenderSummary;
}

export type SearchFilters = Partial<Record<FilterKey, string | string[] | number>>;
export type FilterKey =
  | 'q'
  | 'country'
  | 'cpv'
  | 'status'
  | 'contractType'
  | 'procedureType'
  | 'authority'
  | 'city'
  | 'nuts'
  | 'currency'
  | 'language'
  | 'valueMinEur'
  | 'valueMaxEur'
  | 'publishedFrom'
  | 'publishedTo'
  | 'deadlineFrom'
  | 'deadlineTo'
  | 'sort'
  | 'direction';

export interface SavedSearch {
  id: string;
  name: string;
  filters: SearchFilters;
  createdAt: string;
  updatedAt: string;
  lastRunAt?: string;
}

export interface Bucket {
  key: string;
  label?: string;
  count: number;
}

export interface DashboardSummary {
  hasProfile: boolean;
  goodMatches: number;
  topMatches: MatchView[];
  openTenders: number;
  openCountries: number;
  dueWithin7Days: number;
  newLast7Days: number;
  watchedActive: number;
  watchedValueEur: number;
  watchedWithoutValue: number;
  upcomingDeadlines: TenderSummary[];
  topCpvDivisions: Bucket[];
  byCountry: Bucket[];
  noticesPerDay: { day: string; count: number }[];
  recentUpdates: {
    tenderId: string;
    title: string;
    countryCode: string;
    changeKind: ChangeKind;
    publishedAt: string;
    changedFields: string[];
  }[];
  updatesScope: 'WATCHED' | 'ALL';
  lastIngestionAt?: string;
  byContractType: Bucket[];
  pipeline: { status: WatchStatus; count: number; valueEur: number }[];
  newPerDay: { day: string; count: number }[];
  onboarding: { profile: boolean; watched: boolean; savedSearch: boolean; aiAnalysis: boolean; teammate: boolean };
}

export interface CpvOption {
  code: string;
  parentCode?: string;
  level: number;
  label: string;
}
