import { Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideMapPin } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import { CompanyProfile, CompanyService, DEFAULT_MATCHING, MatchingSettings, profileToRequest } from '../../core/api/company.service';
import { ReferenceService } from '../../core/api/reference.service';
import { LanguageService } from '../../core/i18n/language.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { CpvPickerComponent } from '../../shared/ui/cpv-picker.component';
import { KeywordsInputComponent } from '../../shared/ui/keywords-input.component';
import { CheckOption, MultiCheckComponent } from '../../shared/ui/multi-check.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';

const RADII = [25, 50, 75, 100, 150, 200, 300, 500];
const PREP_DAYS = [3, 5, 7, 10, 14, 21, 30];

/**
 * Where the company works and what it does not do – the settings that make matching precise: base subregion and
 * radius (distance between NUTS 3 points), strict area, extra voivodeships, nationwide supplies, keywords, excluded
 * sectors and the minimum time needed to prepare an offer.
 */
@Component({
  selector: 'eti-matching-settings',
  imports: [TranslocoPipe, ApiErrorAlertComponent, CpvPickerComponent, KeywordsInputComponent, MultiCheckComponent,
    SelectComponent, LucideMapPin],
  template: `
    @let lang = language.current();
    <section class="eti-card p-5" aria-labelledby="ms-h">
      <div class="flex items-start gap-3">
        <span class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent" aria-hidden="true"><svg lucideMapPin class="size-4"></svg></span>
        <div>
          <h2 id="ms-h" class="eti-h2">{{ 'matchSettings.title' | transloco }}</h2>
          <p class="eti-subtitle mt-1">{{ 'matchSettings.subtitle' | transloco }}</p>
        </div>
      </div>

      <fieldset class="mt-6" [disabled]="!editable()">
        <legend class="eti-overline">{{ 'matchSettings.area' | transloco }}</legend>
        <div class="mt-3 grid gap-4 md:grid-cols-3">
          <div>
            <label class="eti-label" for="ms-voiv">{{ 'matchSettings.voivodeship' | transloco }}</label>
            <eti-select inputId="ms-voiv" [options]="voivodeshipOptions()" [value]="voivodeship()" (valueChange)="setVoivodeship($any($event))" [disabled]="!editable()" />
          </div>
          <div>
            <label class="eti-label" for="ms-base">{{ 'matchSettings.base' | transloco }}</label>
            <eti-select inputId="ms-base" [options]="subregionOptions()" [value]="s().baseNuts ?? ''" (valueChange)="patch({ baseNuts: $any($event) || null })" [disabled]="!editable() || !voivodeship()" />
            <p class="eti-hint">{{ 'matchSettings.baseHint' | transloco }}</p>
          </div>
          <div>
            <label class="eti-label" for="ms-radius">{{ 'matchSettings.radius' | transloco }}</label>
            <eti-select inputId="ms-radius" [options]="radiusOptions()" [value]="s().maxDistanceKm ? '' + s().maxDistanceKm : ''" (valueChange)="patch({ maxDistanceKm: $event ? +$event : null })" [disabled]="!editable()" />
          </div>
        </div>
        <div class="mt-4 space-y-2.5 text-[13px]">
          <label class="flex items-start gap-2">
            <input type="checkbox" class="mt-0.5" [checked]="s().regionMode === 'STRICT'" (change)="patch({ regionMode: $any($event.target).checked ? 'STRICT' : 'PREFER' })" />
            <span>{{ 'matchSettings.strict' | transloco }} <span class="block text-xs text-ink-muted">{{ 'matchSettings.strictHint' | transloco }}</span></span>
          </label>
          <label class="flex items-start gap-2">
            <input type="checkbox" class="mt-0.5" [checked]="s().suppliesNationwide" (change)="patch({ suppliesNationwide: $any($event.target).checked })" />
            <span>{{ 'matchSettings.supplies' | transloco }} <span class="block text-xs text-ink-muted">{{ 'matchSettings.suppliesHint' | transloco }}</span></span>
          </label>
        </div>
        <div class="mt-4 max-w-xl">
          <eti-multi-check class="block" [stacked]="true" [label]="'matchSettings.extra' | transloco" [allLabel]="'matchSettings.extraNone' | transloco"
                           [options]="extraOptions()" [selected]="s().extraRegions" (selectedChange)="patch({ extraRegions: $event })" />
          <p class="eti-hint">{{ 'matchSettings.extraHint' | transloco }}</p>
        </div>
      </fieldset>

      <fieldset class="mt-7" [disabled]="!editable()">
        <legend class="eti-overline">{{ 'matchSettings.what' | transloco }}</legend>
        <div class="mt-3 grid gap-4 md:grid-cols-2">
          <div>
            <label class="eti-label" for="ms-inc">{{ 'matchSettings.include' | transloco }}</label>
            <eti-keywords-input inputId="ms-inc" [placeholder]="'matchSettings.includePh' | transloco" [words]="s().includeKeywords" (wordsChange)="patch({ includeKeywords: $event })" />
            <p class="eti-hint">{{ 'matchSettings.includeHint' | transloco }}</p>
          </div>
          <div>
            <label class="eti-label" for="ms-exc">{{ 'matchSettings.exclude' | transloco }}</label>
            <eti-keywords-input inputId="ms-exc" tone="danger" [placeholder]="'matchSettings.excludePh' | transloco" [words]="s().excludeKeywords" (wordsChange)="patch({ excludeKeywords: $event })" />
            <p class="eti-hint">{{ 'matchSettings.excludeHint' | transloco }}</p>
          </div>
        </div>
        <div class="mt-4 max-w-xl">
          <eti-cpv-picker class="block" [label]="'matchSettings.excludedCpv' | transloco" [lang]="lang"
                          [selected]="s().excludedCpv" (selectedChange)="patch({ excludedCpv: $event })" />
          <p class="eti-hint">{{ 'matchSettings.excludedCpvHint' | transloco }}</p>
        </div>
        <div class="mt-4 max-w-xs">
          <label class="eti-label" for="ms-days">{{ 'matchSettings.prep' | transloco }}</label>
          <eti-select inputId="ms-days" [options]="prepOptions()" [value]="s().minDaysToDeadline ? '' + s().minDaysToDeadline : ''" (valueChange)="patch({ minDaysToDeadline: $event ? +$event : null })" [disabled]="!editable()" />
          <p class="eti-hint">{{ 'matchSettings.prepHint' | transloco }}</p>
        </div>
      </fieldset>

      <eti-api-error class="mt-4 block" [error]="error()" />
      @if (editable()) {
        <div class="mt-5 flex items-center gap-3">
          <button type="button" class="eti-btn-primary" [disabled]="busy() || !dirty()" (click)="save()">{{ 'matchSettings.save' | transloco }}</button>
          @if (saved()) {
            <p class="text-sm text-success" role="status">{{ 'matchSettings.saved' | transloco }}</p>
          }
        </div>
      }
    </section>
  `,
})
export class MatchingSettingsComponent {
  readonly profile = input.required<CompanyProfile>();
  readonly orgId = input.required<string>();
  readonly editable = input(true);
  readonly savedProfile = output<CompanyProfile>();

  private readonly api = inject(CompanyService);
  private readonly reference = inject(ReferenceService);
  private readonly transloco = inject(TranslocoService);
  protected readonly language = inject(LanguageService);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  protected readonly s = signal<MatchingSettings>(DEFAULT_MATCHING);
  protected readonly voivodeship = signal<string>('');
  protected readonly dirty = signal(false);
  protected readonly busy = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<ApiError | null>(null);

  private readonly voivodeships = toSignal(this.reference.regions('PL', 2), { initialValue: [] });
  private readonly subregions = toSignal(this.reference.regions('PL', 3), { initialValue: [] });

  protected readonly voivodeshipOptions = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: '—' }, ...this.voivodeships().map((r) => ({ value: r.code, label: r.name, hint: r.code }))];
  });
  protected readonly subregionOptions = computed<SelectOption[]>(() => {
    const v = this.voivodeship();
    return [{ value: '', label: '—' }, ...this.subregions().filter((r) => v && r.code.startsWith(v)).map((r) => ({ value: r.code, label: r.name, hint: r.code }))];
  });
  protected readonly extraOptions = computed<CheckOption[]>(() => this.voivodeships().map((r) => ({ value: r.code, label: r.name })));
  protected readonly radiusOptions = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('matchSettings.noLimit') },
      ...RADII.map((km) => ({ value: String(km), label: this.transloco.translate('matchSettings.km', { km }) }))];
  });
  protected readonly prepOptions = computed<SelectOption[]>(() => {
    this.translations();
    return [{ value: '', label: this.transloco.translate('matchSettings.prepNone') },
      ...PREP_DAYS.map((d) => ({ value: String(d), label: this.transloco.translate('matchSettings.days', { n: d }) }))];
  });

  constructor() {
    effect(() => {
      const m = this.profile().matching ?? DEFAULT_MATCHING;
      this.s.set({ ...DEFAULT_MATCHING, ...m });
      // Polish voivodeships changed in NUTS 2016+ (PL91/PL92 = Mazowieckie): the first four characters identify them.
      this.voivodeship.set(m.baseNuts ? m.baseNuts.slice(0, 4) : '');
      this.dirty.set(false);
    });
  }

  protected setVoivodeship(code: string): void {
    this.voivodeship.set(code);
    if (!this.s().baseNuts?.startsWith(code)) {
      this.patch({ baseNuts: null });
    }
  }

  protected patch(change: Partial<MatchingSettings>): void {
    this.s.update((v) => ({ ...v, ...change }));
    this.dirty.set(true);
    this.saved.set(false);
  }

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    const body = { ...profileToRequest(this.profile()), matching: this.s() };
    this.api.save(this.orgId(), body, this.language.current()).subscribe({
      next: (p) => {
        this.busy.set(false);
        this.saved.set(true);
        this.dirty.set(false);
        this.savedProfile.emit(p);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(toApiError(err));
      },
    });
  }
}
