import { Component, computed, inject, input, model, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { combineLatest, switchMap } from 'rxjs';
import { DEFAULT_MATCHING, MatchingSettings } from '../../core/api/company.service';
import { Region, ReferenceService } from '../../core/api/reference.service';
import { countryName } from '../../core/format/format';
import { LanguageService } from '../../core/i18n/language.service';
import { CpvPickerComponent } from '../../shared/ui/cpv-picker.component';
import { KeywordsInputComponent } from '../../shared/ui/keywords-input.component';
import { MultiOption, MultiSelectComponent } from '../../shared/ui/multi-select.component';
import { SelectComponent, SelectOption } from '../../shared/ui/select.component';

const RADII = [25, 50, 75, 100, 150, 200, 300, 500];
const PREP_DAYS = [3, 5, 7, 10, 14, 21, 30];
/** Non-EU countries with NUTS regions that publish in TED. */
export const NUTS_EXTRA = ['NO', 'CH', 'IS', 'LI'];

/** NUTS uses EL and UK for Greece and the United Kingdom. */
function nutsPrefix(iso: string): string {
  return iso === 'GR' ? 'EL' : iso === 'GB' ? 'UK' : iso;
}

function isoOf(nutsCode: string | null | undefined): string | null {
  if (!nutsCode) {
    return null;
  }
  const p = nutsCode.slice(0, 2);
  return p === 'EL' ? 'GR' : p === 'UK' ? 'GB' : p;
}

/**
 * Matching settings of the company profile, controlled by the page (one save for the whole profile):
 * {@code part="area"} – base, radius and regions; {@code part="preferences"} – keywords, exclusions, preparation time.
 */
@Component({
  selector: 'eti-matching-settings',
  imports: [TranslocoPipe, CpvPickerComponent, KeywordsInputComponent, MultiSelectComponent, SelectComponent],
  template: `
    @let s = settings();
    @if (part() === 'area') {
      <div class="grid gap-x-5 gap-y-5 md:grid-cols-2">
        <div>
          <label class="eti-label" for="ms-country">{{ 'matchSettings.country' | transloco }}</label>
          <eti-select inputId="ms-country" [options]="countryOptions()" [value]="baseCountry()" (valueChange)="setCountry($any($event))" [disabled]="!editable()" />
        </div>
        <div>
          <label class="eti-label" for="ms-base">{{ 'matchSettings.base' | transloco }}</label>
          <eti-select inputId="ms-base" [options]="baseOptions()" [value]="s.baseNuts ?? ''" [placeholder]="'matchSettings.basePh' | transloco"
                      (valueChange)="patch({ baseNuts: $any($event) || null })" [disabled]="!editable()" />
          <p class="eti-hint">{{ 'matchSettings.baseHint' | transloco }}</p>
        </div>
        <div>
          <label class="eti-label" for="ms-radius">{{ 'matchSettings.radius' | transloco }}</label>
          <eti-select inputId="ms-radius" [options]="radiusOptions()" [value]="s.maxDistanceKm ? '' + s.maxDistanceKm : ''"
                      (valueChange)="patch({ maxDistanceKm: $event ? +$event : null })" [disabled]="!editable()" />
          <p class="eti-hint">{{ 'matchSettings.radiusHint' | transloco }}</p>
        </div>
        <div>
          <label class="eti-label" for="ms-extra">{{ 'matchSettings.extra' | transloco }}</label>
          <eti-multi-select inputId="ms-extra" [label]="'matchSettings.extra' | transloco" [placeholder]="'matchSettings.extraNone' | transloco"
                            [options]="extraOptions()" [selected]="s.extraRegions" (selectedChange)="patch({ extraRegions: $event })" [disabled]="!editable()" />
          <p class="eti-hint">{{ 'matchSettings.extraHint' | transloco }}</p>
        </div>
      </div>

      <div class="mt-6 grid gap-3 md:grid-cols-2">
        <label class="flex cursor-pointer items-start gap-3 rounded-[var(--radius-control)] border border-border p-4 has-[:checked]:border-accent has-[:checked]:bg-accent-soft/40">
          <input type="checkbox" class="mt-0.5" [checked]="s.regionMode === 'STRICT'" [disabled]="!editable()"
                 (change)="patch({ regionMode: $any($event.target).checked ? 'STRICT' : 'PREFER' })" />
          <span class="text-[13px]"><span class="font-semibold text-ink">{{ 'matchSettings.strict' | transloco }}</span>
            <span class="mt-1 block text-xs text-ink-muted">{{ 'matchSettings.strictHint' | transloco }}</span></span>
        </label>
        <label class="flex cursor-pointer items-start gap-3 rounded-[var(--radius-control)] border border-border p-4 has-[:checked]:border-accent has-[:checked]:bg-accent-soft/40">
          <input type="checkbox" class="mt-0.5" [checked]="s.suppliesNationwide" [disabled]="!editable()"
                 (change)="patch({ suppliesNationwide: $any($event.target).checked })" />
          <span class="text-[13px]"><span class="font-semibold text-ink">{{ 'matchSettings.supplies' | transloco }}</span>
            <span class="mt-1 block text-xs text-ink-muted">{{ 'matchSettings.suppliesHint' | transloco }}</span></span>
        </label>
      </div>
    } @else {
      <div class="grid gap-x-5 gap-y-5 md:grid-cols-2">
        <div>
          <label class="eti-label" for="ms-inc">{{ 'matchSettings.include' | transloco }}</label>
          <eti-keywords-input inputId="ms-inc" [placeholder]="'matchSettings.includePh' | transloco" [words]="s.includeKeywords" (wordsChange)="patch({ includeKeywords: $event })" />
          <p class="eti-hint">{{ 'matchSettings.includeHint' | transloco }}</p>
        </div>
        <div>
          <label class="eti-label" for="ms-exc">{{ 'matchSettings.exclude' | transloco }}</label>
          <eti-keywords-input inputId="ms-exc" tone="danger" [placeholder]="'matchSettings.excludePh' | transloco" [words]="s.excludeKeywords" (wordsChange)="patch({ excludeKeywords: $event })" />
          <p class="eti-hint">{{ 'matchSettings.excludeHint' | transloco }}</p>
        </div>
        <div>
          <eti-cpv-picker class="block" [label]="'matchSettings.excludedCpv' | transloco" [lang]="language.current()" [suggest]="companyCpv()"
                          [selected]="s.excludedCpv" (selectedChange)="patch({ excludedCpv: $event })" />
          <p class="eti-hint">{{ 'matchSettings.excludedCpvHint' | transloco }}</p>
        </div>
        <div>
          <label class="eti-label" for="ms-days">{{ 'matchSettings.prep' | transloco }}</label>
          <eti-select inputId="ms-days" [options]="prepOptions()" [value]="s.minDaysToDeadline ? '' + s.minDaysToDeadline : ''"
                      (valueChange)="patch({ minDaysToDeadline: $event ? +$event : null })" [disabled]="!editable()" />
          <p class="eti-hint">{{ 'matchSettings.prepHint' | transloco }}</p>
        </div>
      </div>
    }
  `,
})
export class MatchingSettingsComponent {
  readonly settings = model<MatchingSettings>(DEFAULT_MATCHING);
  readonly part = input<'area' | 'preferences'>('area');
  readonly registrationCountry = input<string | null | undefined>(null);
  readonly countriesOfInterest = input<string[]>([]);
  /** The company's sectors: their sub-codes are suggested as exclusions. */
  readonly companyCpv = input<string[]>([]);
  readonly editable = input(true);

  private readonly reference = inject(ReferenceService);
  private readonly transloco = inject(TranslocoService);
  protected readonly language = inject(LanguageService);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });
  private readonly countries = toSignal(this.reference.countries(), { initialValue: [] });

  /** Country of the base: from the chosen subregion, else the country picked last, else the registration country. */
  private readonly chosenCountry = signal<string | null>(null);
  protected readonly baseCountry = computed(() =>
    isoOf(this.settings().baseNuts) ?? this.chosenCountry() ?? this.registrationCountry() ?? 'PL');

  /** NUTS 2 and NUTS 3 regions of the base country. */
  private readonly baseRegions = toSignal(toObservable(this.baseCountry).pipe(
    switchMap((c) => combineLatest([this.reference.regions(c, 2), this.reference.regions(c, 3)])),
  ), { initialValue: [[], []] as [Region[], Region[]] });

  /** Level-2 regions of every country (one request, cached). */
  private readonly allLevel2 = toSignal(this.reference.allRegions(2), { initialValue: [] as Region[] });

  protected readonly countryOptions = computed<SelectOption[]>(() => {
    const lang = this.language.current();
    this.translations();
    const eu = this.transloco.translate('company.groupEu');
    const other = this.transloco.translate('company.groupOtherEurope');
    return this.countries().filter((c) => c.isEu || NUTS_EXTRA.includes(c.code))
      .map((c) => ({ value: c.code, label: countryName(c.code, lang), group: c.isEu ? eu : other }))
      .sort((a, b) => (a.group === b.group ? a.label.localeCompare(b.label, lang) : a.group === eu ? -1 : 1));
  });

  /** All NUTS 3 subregions of the country, grouped under their NUTS 2 region; searchable by both names. */
  protected readonly baseOptions = computed<SelectOption[]>(() => {
    const [level2, level3] = this.baseRegions();
    const lang = this.language.current();
    const regionName = new Map(level2.map((r) => [r.code, r.name]));
    return level3
      .map((r) => ({ value: r.code, label: r.name, hint: r.code, group: regionName.get(r.code.slice(0, 4)) ?? r.code.slice(0, 4) }))
      .sort((a, b) => a.group.localeCompare(b.group, lang) || a.label.localeCompare(b.label, lang));
  });

  /**
   * Extra regions anywhere in the EU / EEA: for every country "the whole country" and its NUTS 2 regions. The base
   * country and the countries of interest come first, then the others alphabetically.
   */
  protected readonly extraOptions = computed<MultiOption[]>(() => {
    const lang = this.language.current();
    this.translations();
    const allowed = new Set(this.countries().filter((c) => c.isEu || NUTS_EXTRA.includes(c.code)).map((c) => c.code));
    const first = [...new Set([this.baseCountry(), ...this.countriesOfInterest()])];
    const rank = (iso: string) => (first.includes(iso) ? first.indexOf(iso) : first.length);
    const byCountry = new Map<string, Region[]>();
    for (const r of this.allLevel2()) {
      if (r.country && allowed.has(r.country)) {
        byCountry.set(r.country, [...(byCountry.get(r.country) ?? []), r]);
      }
    }
    return [...allowed]
      .map((iso) => ({ iso, name: countryName(iso, lang) }))
      .sort((a, b) => rank(a.iso) - rank(b.iso) || a.name.localeCompare(b.name, lang))
      .flatMap(({ iso, name }) => [
        { value: nutsPrefix(iso), label: this.transloco.translate('matchSettings.wholeCountry', { country: name }), hint: nutsPrefix(iso), group: name },
        ...(byCountry.get(iso) ?? [])
          .sort((a, b) => a.name.localeCompare(b.name, lang))
          .map((r) => ({ value: r.code, label: r.name, hint: r.code, group: name })),
      ]);
  });

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

  protected setCountry(code: string): void {
    this.chosenCountry.set(code);
    if (this.settings().baseNuts && !this.settings().baseNuts!.startsWith(nutsPrefix(code))) {
      this.patch({ baseNuts: null });
    }
  }

  protected patch(change: Partial<MatchingSettings>): void {
    this.settings.update((v) => ({ ...v, ...change }));
  }
}
