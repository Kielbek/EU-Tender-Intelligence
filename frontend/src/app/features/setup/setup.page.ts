import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideArrowLeft, LucideCheck, LucideLandmark, LucideLoaderCircle } from '@lucide/angular';
import { AccountService } from '../../core/api/account.service';
import { ApiError, toApiError } from '../../core/api/api-error';
import {
  CompanyProfile,
  CompanyProfileRequest,
  DEFAULT_MATCHING,
  CompanyService,
  CompanySize,
  MatchingService,
  ProfileContractType,
} from '../../core/api/company.service';
import { ReferenceService } from '../../core/api/reference.service';
import { AuthService } from '../../core/auth/auth.service';
import { countryName } from '../../core/format/format';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { CpvPickerComponent } from '../../shared/ui/cpv-picker.component';
import { CheckOption, MultiCheckComponent } from '../../shared/ui/multi-check.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';

type Step = 1 | 2 | 3 | 4;

/**
 * First-run setup after registration: a full-screen, step-by-step company profile (the basis of tender matching).
 * Everything can be skipped; finishing or skipping is remembered on the account, so it is shown only once.
 * Team members (who cannot edit the company profile) go straight to the dashboard.
 */
@Component({
  selector: 'eti-setup-page',
  imports: [FormsModule, TranslocoPipe, ApiErrorAlertComponent, CpvPickerComponent, MultiCheckComponent,
    SelectComponent, LucideArrowLeft, LucideCheck, LucideLandmark, LucideLoaderCircle],
  templateUrl: './setup.page.html',
})
export class SetupPage {
  private readonly company = inject(CompanyService);
  private readonly matching = inject(MatchingService);
  private readonly account = inject(AccountService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly auth = inject(AuthService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  private readonly countries = toSignal(inject(ReferenceService).countries(), { initialValue: [] });
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  protected readonly step = signal<Step>(1);
  protected readonly totalSteps = 3;
  protected readonly ready = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly attempted = signal(false);
  protected readonly matchCount = signal<number | null>(null);
  private existing: CompanyProfile | null = null;

  // Answers
  protected readonly name = signal('');
  protected readonly registrationCountry = signal<string | null>('PL');
  protected readonly size = signal<string | null>('');
  protected readonly cpvCodes = signal<string[]>([]);
  protected readonly cpvLabels = signal<Record<string, string>>({});
  protected readonly contractTypes = signal<ProfileContractType[]>([]);
  protected readonly countriesOfInterest = signal<string[]>(['PL']);
  protected readonly minValue = signal<number | null>(null);
  protected readonly voivodeship = signal('');
  protected readonly baseNuts = signal('');
  protected readonly radius = signal('');
  protected readonly maxValue = signal<number | null>(null);

  protected readonly typeOptions: ProfileContractType[] = ['WORKS', 'SUPPLIES', 'SERVICES'];
  protected readonly firstName = computed(() => (this.auth.user()?.fullName ?? '').split(/\s+/)[0] ?? '');

  protected readonly countryOptions = computed<CheckOption[]>(() => {
    const lang = this.language.current();
    return this.countries()
      .map((c) => ({ value: c.code, label: countryName(c.code, lang) }))
      .sort((a, b) => a.label.localeCompare(b.label, lang));
  });
  protected readonly countrySelect = computed<SelectOption[]>(() => [{ value: '', label: '—' }, ...this.countryOptions()]);
  protected readonly sizeSelect = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: '—' },
      ...(['MICRO', 'SMALL', 'MEDIUM', 'LARGE'] as CompanySize[]).map((s) => ({
        value: s, label: this.transloco.translate('company.sizes.' + s) }))];
  });

  private readonly reference = inject(ReferenceService);
  private readonly voivodeships = toSignal(this.reference.regions('PL', 2), { initialValue: [] });
  private readonly subregions = toSignal(this.reference.regions('PL', 3), { initialValue: [] });
  protected readonly voivodeshipOptions = computed<SelectOption[]>(() =>
    [{ value: '', label: '—' }, ...this.voivodeships().map((r) => ({ value: r.code, label: r.name }))]);
  protected readonly subregionOptions = computed<SelectOption[]>(() => {
    const v = this.voivodeship();
    return [{ value: '', label: '—' }, ...this.subregions().filter((r) => v && r.code.startsWith(v)).map((r) => ({ value: r.code, label: r.name }))];
  });
  protected readonly radiusOptions = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('matchSettings.noLimit') },
      ...[50, 100, 150, 200, 300].map((km) => ({ value: String(km), label: this.transloco.translate('matchSettings.km', { km }) }))];
  });

  protected setVoivodeship(code: string): void {
    this.voivodeship.set(code);
    if (!this.baseNuts().startsWith(code)) {
      this.baseNuts.set('');
    }
  }

  protected readonly rangeInvalid = computed(() => {
    const min = this.minValue();
    const max = this.maxValue();
    return min != null && max != null && min > max;
  });
  protected readonly stepValid = computed(() => {
    switch (this.step()) {
      case 1:
        return this.name().trim().length > 0;
      case 2:
        return this.cpvCodes().length > 0;
      case 3:
        return !this.rangeInvalid();
      default:
        return true;
    }
  });

  constructor() {
    // Wait for the organization list, then decide: admins get the wizard, members go straight on.
    effect(() => {
      const loaded = this.org.loaded();
      const current = this.org.current();
      if (!loaded) {
        return;
      }
      untracked(() => {
        if (!current || current.role !== 'COMPANY_ADMIN') {
          this.finish('/');
          return;
        }
        this.name.set(current.name);
        this.company.get(current.id, this.language.current()).subscribe({
          next: (p) => {
            this.existing = p;
            this.name.set(p.name);
            this.registrationCountry.set(p.registrationCountry ?? 'PL');
            this.size.set(p.companySize ?? '');
            this.cpvCodes.set(p.cpvCodes);
            this.cpvLabels.set(p.cpvLabels);
            this.contractTypes.set(p.contractTypes);
            if (p.countriesOfInterest.length) {
              this.countriesOfInterest.set(p.countriesOfInterest);
            }
            this.minValue.set(p.minContractValueEur ?? null);
            if (p.matching?.baseNuts) {
              this.baseNuts.set(p.matching.baseNuts);
              this.voivodeship.set(p.matching.baseNuts.slice(0, 4));
            }
            if (p.matching?.maxDistanceKm) {
              this.radius.set(String(p.matching.maxDistanceKm));
            }
            this.maxValue.set(p.maxContractValueEur ?? null);
            this.ready.set(true);
          },
          error: () => this.ready.set(true), // no profile yet
        });
      });
    });
  }

  protected toggleType(t: ProfileContractType, checked: boolean): void {
    this.contractTypes.update((list) => (checked ? [...list, t] : list.filter((x) => x !== t)));
  }

  protected back(): void {
    this.attempted.set(false);
    this.step.update((s) => (s > 1 ? ((s - 1) as Step) : s));
  }

  protected next(): void {
    this.attempted.set(true);
    if (!this.stepValid() || this.busy()) {
      return;
    }
    this.attempted.set(false);
    if (this.step() < this.totalSteps) {
      this.step.update((s) => (s + 1) as Step);
      return;
    }
    this.save();
  }

  private save(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    const p = this.existing;
    const body: CompanyProfileRequest = {
      name: this.name().trim(),
      registrationCountry: this.registrationCountry() || undefined,
      industry: p?.industry,
      description: p?.description,
      website: p?.website,
      countriesOfInterest: this.countriesOfInterest(),
      contractTypes: this.contractTypes(),
      companySize: (this.size() || undefined) as CompanySize | undefined,
      employees: p?.employees,
      annualRevenue: p?.annualRevenue,
      revenueCurrency: p?.revenueCurrency,
      yearsOfExperience: p?.yearsOfExperience,
      minContractValueEur: this.minValue() ?? undefined,
      maxContractValueEur: this.maxValue() ?? undefined,
      technicalResources: p?.technicalResources,
      staffResources: p?.staffResources,
      financialCapacity: p?.financialCapacity,
      cpvCodes: this.cpvCodes(),
      // Only when the base was chosen; otherwise the stored matching settings stay as they are.
      matching: this.baseNuts() ? { ...DEFAULT_MATCHING, ...(p?.matching ?? {}), baseNuts: this.baseNuts(),
        maxDistanceKm: this.radius() ? Number(this.radius()) : null } : undefined,
    };
    this.busy.set(true);
    this.error.set(null);
    this.company.save(orgId, body, this.language.current()).subscribe({
      next: () => {
        this.busy.set(false);
        this.step.set(4);
        this.account.completeSetup().subscribe({ next: (me) => this.auth.updateUser(me) });
        this.org.reload();
        this.pollMatches(orgId, 0);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(toApiError(err));
      },
    });
  }

  /** Matches are computed in the background; ask a few times until they are there. */
  private pollMatches(orgId: string, attempt: number): void {
    this.matching.list(orgId, 60, 0, 1, this.language.current()).subscribe({
      next: (page) => {
        if (page.totalItems > 0 || attempt >= 5) {
          this.matchCount.set(page.totalItems);
        } else {
          setTimeout(() => this.pollMatches(orgId, attempt + 1), 1500);
        }
      },
      error: () => this.matchCount.set(0),
    });
  }

  protected skip(): void {
    this.finish('/');
  }

  protected go(url: string): void {
    void this.router.navigateByUrl(url);
  }

  private finish(url: string): void {
    this.account.completeSetup().subscribe({
      next: (me) => {
        this.auth.updateUser(me);
        void this.router.navigateByUrl(url);
      },
      error: () => {
        // Do not trap the user in the wizard if the server is unreachable: remember it for this session.
        const me = this.auth.user();
        if (me) {
          this.auth.updateUser({ ...me, setupCompleted: true });
        }
        void this.router.navigateByUrl(url);
      },
    });
  }

  protected num(v: unknown): number | null {
    if (v === '' || v === null || v === undefined) {
      return null;
    }
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
}
