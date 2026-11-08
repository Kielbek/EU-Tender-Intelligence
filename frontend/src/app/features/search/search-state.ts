import { Params } from '@angular/router';
import { SearchFilters } from '../../core/api/tender.model';
import { dateInputToIso, isoToDateInput } from '../../core/format/format';

/** UI state of the search form. Dates are date-input strings (YYYY-MM-DD, user's local calendar). */
export interface SearchForm {
  q: string;
  country: string[];
  cpv: string[];
  status: string[];
  contractType: string[];
  procedureType: string[];
  authority: string;
  city: string;
  nuts: string;
  currency: string;
  language: string;
  valueMinEur: string;
  valueMaxEur: string;
  publishedFrom: string;
  publishedTo: string;
  deadlineFrom: string;
  deadlineTo: string;
  sort: string;
}

export const LIST_KEYS = ['country', 'cpv', 'status', 'contractType', 'procedureType'] as const;
export const TEXT_KEYS = ['q', 'authority', 'city', 'nuts', 'currency', 'language', 'valueMinEur', 'valueMaxEur',
  'publishedFrom', 'publishedTo', 'deadlineFrom', 'deadlineTo', 'sort'] as const;

export function emptyForm(): SearchForm {
  return {
    q: '', country: [], cpv: [], status: [], contractType: [], procedureType: [], authority: '', city: '', nuts: '',
    currency: '', language: '', valueMinEur: '', valueMaxEur: '', publishedFrom: '', publishedTo: '',
    deadlineFrom: '', deadlineTo: '', sort: '',
  };
}

/** Inputs bound with ngModel may hold numbers (type="number") or null – always work on trimmed text. */
function text(v: unknown): string {
  return v === null || v === undefined ? '' : String(v).trim();
}

function asList(v: unknown): string[] {
  if (Array.isArray(v)) {
    return v.map(String).filter(Boolean);
  }
  return typeof v === 'string' && v ? [v] : [];
}

/** URL query parameters → form. Unknown parameters are ignored. */
export function formFromParams(params: Params): SearchForm {
  const form = emptyForm();
  for (const k of LIST_KEYS) {
    form[k] = asList(params[k]);
  }
  for (const k of TEXT_KEYS) {
    form[k] = typeof params[k] === 'string' ? params[k] : '';
  }
  return form;
}

/** Form → URL query parameters (empty values removed, so URLs stay short and shareable). */
export function paramsFromForm(form: SearchForm, page = 0): Params {
  const params: Params = {};
  for (const k of LIST_KEYS) {
    if (form[k].length) {
      params[k] = form[k];
    }
  }
  for (const k of TEXT_KEYS) {
    if (text(form[k])) {
      params[k] = text(form[k]);
    }
  }
  if (page > 0) {
    params['page'] = String(page);
  }
  return params;
}

/** Form → API filters (dates converted to instants: "to" dates are inclusive of the whole day). */
export function apiFilters(form: SearchForm): SearchFilters {
  const f: SearchFilters = {};
  for (const k of LIST_KEYS) {
    if (form[k].length) {
      f[k] = form[k];
    }
  }
  for (const k of ['q', 'authority', 'city', 'nuts', 'currency', 'language', 'sort'] as const) {
    if (text(form[k])) {
      f[k] = k === 'nuts' ? text(form[k]).toUpperCase() : text(form[k]);
    }
  }
  for (const k of ['valueMinEur', 'valueMaxEur'] as const) {
    const n = Number(text(form[k]));
    if (text(form[k]) && Number.isFinite(n) && n >= 0) {
      f[k] = n;
    }
  }
  const pf = dateInputToIso(form.publishedFrom);
  const pt = dateInputToIso(form.publishedTo, true);
  const df = dateInputToIso(form.deadlineFrom);
  const dt = dateInputToIso(form.deadlineTo, true);
  if (pf) f.publishedFrom = pf;
  if (pt) f.publishedTo = pt;
  if (df) f.deadlineFrom = df;
  if (dt) f.deadlineTo = dt;
  return f;
}

/** Saved API filters → URL query parameters (inverse of apiFilters). */
export function paramsFromApiFilters(filters: SearchFilters): Params {
  const form = emptyForm();
  for (const k of LIST_KEYS) {
    form[k] = asList(filters[k]);
  }
  for (const k of ['q', 'authority', 'city', 'nuts', 'currency', 'language', 'sort'] as const) {
    form[k] = typeof filters[k] === 'string' ? (filters[k] as string) : '';
  }
  for (const k of ['valueMinEur', 'valueMaxEur'] as const) {
    form[k] = filters[k] !== undefined ? String(filters[k]) : '';
  }
  form.publishedFrom = isoToDateInput(filters.publishedFrom as string | undefined);
  form.publishedTo = isoToDateInput(filters.publishedTo as string | undefined, true);
  form.deadlineFrom = isoToDateInput(filters.deadlineFrom as string | undefined);
  form.deadlineTo = isoToDateInput(filters.deadlineTo as string | undefined, true);
  return paramsFromForm(form);
}

export function activeFilterCount(form: SearchForm): number {
  return LIST_KEYS.filter((k) => form[k].length).length +
    TEXT_KEYS.filter((k) => k !== 'sort' && k !== 'q' && text(form[k])).length;
}
