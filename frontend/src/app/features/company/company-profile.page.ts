import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideCheck, LucideChevronRight, LucideCircle, LucideLock, LucideTrash2 } from '@lucide/angular';
import { Observable, map, startWith } from 'rxjs';
import { ApiError, toApiError } from '../../core/api/api-error';
import {
  CompanyProfile,
  CompanyProfileRequest,
  CompanyService,
  CompanySize,
  DEFAULT_MATCHING,
  MatchingSettings,
  ProfileContractType,
} from '../../core/api/company.service';
import { ReferenceService } from '../../core/api/reference.service';
import { countryName } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { CpvPickerComponent } from '../../shared/ui/cpv-picker.component';
import { MultiOption, MultiSelectComponent, MultiShortcut } from '../../shared/ui/multi-select.component';
import { DateInputComponent } from '../../shared/ui/date-input.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';
import { MatchingSettingsComponent, NUTS_EXTRA } from './matching-settings.component';

type Num = number | null;

export type ProfileTab = 'scope' | 'area' | 'preferences' | 'basics' | 'references';
const CHECK_TAB: Record<string, ProfileTab> = {
  cpv: 'scope', countries: 'scope', area: 'area', contractTypes: 'scope', valueRange: 'scope', references: 'references',
};

/** '' / null / NaN → undefined, so optional numbers are sent as absent rather than 0. */
function num(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const n = Number(value);
  return Number.isFinite(n) ? n : undefined;
}

function text(value: string | null | undefined): string | undefined {
  const t = (value ?? '').trim();
  return t ? t : undefined;
}

@Component({
  selector: 'eti-company-profile-page',
  imports: [ReactiveFormsModule, RouterLink, TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, CpvPickerComponent,
    MultiSelectComponent, SkeletonComponent, SelectComponent, DateInputComponent, MatchingSettingsComponent, LucideCheck,
    LucideChevronRight, LucideCircle, LucideLock, LucideTrash2],
  templateUrl: './company-profile.page.html',
})
export class CompanyProfilePage {
  private readonly api = inject(CompanyService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  protected readonly countries = toSignal(inject(ReferenceService).countries(), { initialValue: [] });
  protected readonly currencies = toSignal(inject(ReferenceService).currencies(), { initialValue: [] });

  protected readonly contractTypeOptions: ProfileContractType[] = ['WORKS', 'SUPPLIES', 'SERVICES'];
  protected readonly sizes: CompanySize[] = ['MICRO', 'SMALL', 'MEDIUM', 'LARGE'];

  protected readonly profile = signal<CompanyProfile | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly busy = signal(false);
  protected readonly saved = signal(false);

  protected readonly cpvCodes = signal<string[]>([]);
  protected readonly countriesOfInterest = signal<string[]>([]);
  protected readonly contractTypes = signal<ProfileContractType[]>([]);
  protected readonly refCpv = signal<string[]>([]);
  protected readonly matching = signal<MatchingSettings>(DEFAULT_MATCHING);

  protected readonly tabs: ProfileTab[] = ['scope', 'area', 'preferences', 'basics', 'references'];
  protected readonly tab = signal<ProfileTab>('scope');

  protected readonly isAdmin = computed(() => this.org.current()?.role === 'COMPANY_ADMIN');

  protected readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    registrationCountry: [''],
    industry: ['', Validators.maxLength(200)],
    website: ['', [Validators.maxLength(300), Validators.pattern(/^(https?:\/\/.+)?$/)]],
    companySize: [''],
    employees: [null as Num, Validators.min(0)],
    yearsOfExperience: [null as Num, [Validators.min(0), Validators.max(300)]],
    annualRevenue: [null as Num, Validators.min(0)],
    revenueCurrency: ['EUR'],
    minContractValueEur: [null as Num, Validators.min(0)],
    maxContractValueEur: [null as Num, Validators.min(0)],
    description: ['', Validators.maxLength(5000)],
    technicalResources: ['', Validators.maxLength(5000)],
    staffResources: ['', Validators.maxLength(5000)],
    financialCapacity: ['', Validators.maxLength(5000)],
  });

  protected readonly certForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    issuer: ['', Validators.maxLength(200)],
    validUntil: [''],
  });

  protected readonly refForm = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(300)]],
    client: ['', Validators.maxLength(200)],
    countryCode: [''],
    value: [null as Num, Validators.min(0)],
    currency: ['PLN'],
    yearCompleted: [null as Num, [Validators.min(1950), Validators.max(2100)]],
    description: ['', Validators.maxLength(2000)],
  });

  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  private readonly countryNames = computed(() => {
    const lang = this.language.current();
    return this.countries()
      .map((c) => ({ value: c.code, label: countryName(c.code, lang), isEu: c.isEu }))
      .sort((a, b) => a.label.localeCompare(b.label, lang));
  });

  /** Countries of interest: the EU first, then the rest of Europe with NUTS regions, then everything else. */
  protected readonly countryOptions = computed<MultiOption[]>(() => {
    this.translations();
    const rank = (c: { value: string; isEu: boolean }) => (c.isEu ? 0 : NUTS_EXTRA.includes(c.value) ? 1 : 2);
    const groups = ['company.groupEu', 'company.groupOtherEurope', 'company.groupOther'].map((k) => this.transloco.translate(k));
    return this.countryNames()
      .map((c) => ({ value: c.value, label: c.label, hint: c.value, rank: rank(c) }))
      .sort((a, b) => a.rank - b.rank)
      .map(({ rank, ...o }) => ({ ...o, group: groups[rank] }));
  });
  protected readonly countryShortcuts = computed<MultiShortcut[]>(() => {
    this.translations();
    return [{ label: this.transloco.translate('company.allEu'), values: this.countries().filter((c) => c.isEu).map((c) => c.code) }];
  });

  protected readonly countrySelect = computed<SelectOption[]>(() =>
    [{ value: '', label: '—' }, ...this.countryNames().map((c) => ({ value: c.value, label: c.label }))]);
  protected readonly currencySelect = computed<SelectOption[]>(() =>
    this.currencies().map((c) => ({ value: c.code, label: c.code })),
  );
  protected readonly sizeSelect = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: '—' },
      ...this.sizes.map((s) => ({ value: s, label: this.transloco.translate('company.sizes.' + s) }))];
  });

  /** What the matching engine can use from the saved profile. */
  protected readonly checklist = computed(() => {
    this.translations();
    const p = this.profile();
    const item = (key: string, done: boolean) => ({ key, done });
    return [
      item('cpv', !!p?.cpvCodes.length),
      item('countries', !!p && (p.countriesOfInterest.length > 0 || !!p.registrationCountry)),
      item('area', !!p?.matching?.baseNuts),
      item('contractTypes', !!p?.contractTypes.length),
      item('valueRange', !!p && (p.minContractValueEur != null || p.maxContractValueEur != null)),
      item('references', !!p?.references.length),
    ];
  });
  protected readonly checklistDone = computed(() => this.checklist().filter((i) => i.done).length);
  protected readonly checkTab = CHECK_TAB;

  /** Raw form value as a signal (includes disabled controls). */
  protected readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(null), map(() => this.form.getRawValue())),
    { initialValue: this.form.getRawValue() });
  private readonly snapshot = computed(() => JSON.stringify([this.formValue(), this.cpvCodes(), this.countriesOfInterest(),
    this.contractTypes(), this.matching()]));
  private readonly savedSnapshot = signal('');
  /** Unsaved edits of the form, the lists or the matching settings. */
  protected readonly dirty = computed(() => this.isAdmin() && this.snapshot() !== this.savedSnapshot());

  constructor() {
    effect(() => {
      const orgId = this.org.currentId();
      const lang = this.language.current();
      if (orgId) {
        untracked(() => this.load(orgId, lang));
      } else if (this.org.loaded()) {
        this.loading.set(false);
      }
    });
    effect(() => {
      if (this.isAdmin()) {
        this.form.enable();
      } else {
        this.form.disable();
      }
    });
  }

  protected load(orgId = this.org.currentId()!, lang = this.language.current()): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.api.get(orgId, lang).subscribe({
      next: (p) => {
        this.apply(p);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        const e = toApiError(err);
        if (e.code === 'profile_missing') {
          this.profile.set(null);
          this.form.reset({ name: this.org.current()?.name ?? '', revenueCurrency: 'EUR' });
          this.cpvCodes.set([]);
          this.countriesOfInterest.set([]);
          this.contractTypes.set([]);
          this.matching.set(DEFAULT_MATCHING);
          this.savedSnapshot.set('');
        } else {
          this.loadError.set(e);
        }
        this.loading.set(false);
      },
    });
  }

  private apply(p: CompanyProfile): void {
    this.profile.set(p);
    this.form.reset({
      name: p.name,
      registrationCountry: p.registrationCountry ?? '',
      industry: p.industry ?? '',
      website: p.website ?? '',
      companySize: p.companySize ?? '',
      employees: p.employees ?? null,
      yearsOfExperience: p.yearsOfExperience ?? null,
      annualRevenue: p.annualRevenue ?? null,
      revenueCurrency: p.revenueCurrency ?? 'EUR',
      minContractValueEur: p.minContractValueEur ?? null,
      maxContractValueEur: p.maxContractValueEur ?? null,
      description: p.description ?? '',
      technicalResources: p.technicalResources ?? '',
      staffResources: p.staffResources ?? '',
      financialCapacity: p.financialCapacity ?? '',
    });
    this.cpvCodes.set(p.cpvCodes);
    this.countriesOfInterest.set(p.countriesOfInterest);
    this.contractTypes.set(p.contractTypes);
    this.matching.set({ ...DEFAULT_MATCHING, ...p.matching });
    this.savedSnapshot.set(this.snapshot());
  }

  /** Back to the saved profile. */
  protected discard(): void {
    const p = this.profile();
    if (p) {
      this.apply(p);
    } else {
      this.load();
    }
  }

  protected openTab(t: ProfileTab): void {
    this.tab.set(t);
  }

  /** Arrow keys, Home and End move between tabs (WAI-ARIA tabs pattern). */
  protected tabKey(e: KeyboardEvent): void {
    const i = this.tabs.indexOf(this.tab());
    const next = { ArrowRight: i + 1, ArrowLeft: i - 1, Home: 0, End: this.tabs.length - 1 }[e.key];
    if (next === undefined) {
      return;
    }
    e.preventDefault();
    const t = this.tabs[(next + this.tabs.length) % this.tabs.length];
    this.tab.set(t);
    setTimeout(() => document.getElementById('cp-tab-' + t)?.focus());
  }

  protected toggleType(type: ProfileContractType, checked: boolean): void {
    this.contractTypes.update((list) => (checked ? [...list, type] : list.filter((t) => t !== type)));
  }

  protected invalidRange(): boolean {
    const v = this.form.getRawValue();
    const min = num(v.minContractValueEur);
    const max = num(v.maxContractValueEur);
    return min !== undefined && max !== undefined && min > max;
  }

  protected save(): void {
    const orgId = this.org.currentId();
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.tab.set('basics');
    } else if (this.invalidRange()) {
      this.tab.set('scope');
    }
    if (!orgId || this.form.invalid || this.invalidRange() || this.busy()) {
      return;
    }
    const v = this.form.getRawValue();
    const body: CompanyProfileRequest = {
      name: (v.name ?? '').trim(),
      registrationCountry: text(v.registrationCountry),
      industry: text(v.industry),
      website: text(v.website),
      companySize: (text(v.companySize) as CompanySize | undefined) ?? undefined,
      employees: num(v.employees),
      yearsOfExperience: num(v.yearsOfExperience),
      annualRevenue: num(v.annualRevenue),
      revenueCurrency: num(v.annualRevenue) !== undefined ? text(v.revenueCurrency) : undefined,
      minContractValueEur: num(v.minContractValueEur),
      maxContractValueEur: num(v.maxContractValueEur),
      description: text(v.description),
      technicalResources: text(v.technicalResources),
      staffResources: text(v.staffResources),
      financialCapacity: text(v.financialCapacity),
      countriesOfInterest: this.countriesOfInterest(),
      contractTypes: this.contractTypes(),
      cpvCodes: this.cpvCodes(),
      matching: this.matching(),
    };
    this.run(this.api.save(orgId, body, this.language.current()), (p) => {
      this.apply(p);
      this.saved.set(true);
    });
  }

  protected addCertification(): void {
    const orgId = this.org.currentId();
    this.certForm.markAllAsTouched();
    if (!orgId || this.certForm.invalid || this.busy()) {
      return;
    }
    const v = this.certForm.getRawValue();
    this.run(
      this.api.addCertification(orgId, {
        name: (v.name ?? '').trim(),
        issuer: text(v.issuer),
        validUntil: text(v.validUntil),
      }),
      (c) => {
        this.profile.update((p) => (p ? { ...p, certifications: [...p.certifications, c] } : p));
        this.certForm.reset({ name: '', issuer: '', validUntil: '' });
      },
    );
  }

  protected removeCertification(id: string): void {
    const orgId = this.org.currentId();
    if (!orgId || !confirm(this.transloco.translate('company.removeConfirm'))) {
      return;
    }
    this.run(this.api.deleteCertification(orgId, id), () =>
      this.profile.update((p) => (p ? { ...p, certifications: p.certifications.filter((c) => c.id !== id) } : p)),
    );
  }

  protected addReference(): void {
    const orgId = this.org.currentId();
    this.refForm.markAllAsTouched();
    if (!orgId || this.refForm.invalid || this.busy()) {
      return;
    }
    const v = this.refForm.getRawValue();
    this.run(
      this.api.addReference(orgId, {
        title: (v.title ?? '').trim(),
        client: text(v.client),
        countryCode: text(v.countryCode),
        value: num(v.value),
        currency: num(v.value) !== undefined ? text(v.currency) : undefined,
        yearCompleted: num(v.yearCompleted),
        cpvCode: this.refCpv()[0],
        description: text(v.description),
      }),
      (r) => {
        this.profile.update((p) => (p ? { ...p, references: [...p.references, r] } : p));
        this.refForm.reset({ title: '', client: '', countryCode: '', value: null, currency: 'PLN', yearCompleted: null,
          description: '' });
        this.refCpv.set([]);
      },
    );
  }

  protected removeReference(id: string): void {
    const orgId = this.org.currentId();
    if (!orgId || !confirm(this.transloco.translate('company.removeConfirm'))) {
      return;
    }
    this.run(this.api.deleteReference(orgId, id), () =>
      this.profile.update((p) => (p ? { ...p, references: p.references.filter((r) => r.id !== id) } : p)),
    );
  }

  private run<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
    this.busy.set(true);
    this.actionError.set(null);
    this.saved.set(false);
    request.subscribe({
      next: (value) => {
        this.busy.set(false);
        onSuccess(value);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.actionError.set(toApiError(err));
      },
    });
  }
}
