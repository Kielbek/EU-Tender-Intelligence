import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { LucideCheck, LucideExternalLink, LucideLoaderCircle, LucideShieldCheck } from '@lucide/angular';
import { ApiError, toApiError } from '../../core/api/api-error';
import {
  BillingInterval,
  BillingOverview,
  BillingService,
  Meter,
  PlanCode,
  PlanInfo,
  PlansResponse,
} from '../../core/api/billing.service';
import { moneyMinor } from '../../core/format/format';
import { FORMAT_PIPES } from '../../core/format/format.pipes';
import { LanguageService } from '../../core/i18n/language.service';
import { OrgContextService } from '../../core/org/org-context.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';
import { SkeletonComponent } from '../../shared/ui/skeleton.component';

const ENTITLED = ['active', 'trialing', 'past_due'];

/**
 * Plan of the organization, usage of its limits, plan comparison with prices from Stripe, and invoices. Buying and
 * managing happen on Stripe's hosted pages; the plan changes only after Stripe confirms the payment (webhook).
 */
@Component({
  selector: 'eti-billing-page',
  imports: [TranslocoPipe, ...FORMAT_PIPES, ApiErrorAlertComponent, SkeletonComponent, LucideCheck, LucideExternalLink,
    LucideLoaderCircle, LucideShieldCheck],
  templateUrl: './billing.page.html',
})
export class BillingPage {
  private readonly api = inject(BillingService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  protected readonly org = inject(OrgContextService);
  protected readonly language = inject(LanguageService);
  private readonly translations = toSignal(this.transloco.selectTranslation(), { initialValue: {} });

  protected readonly overview = signal<BillingOverview | null>(null);
  protected readonly plans = signal<PlansResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly redirecting = signal<string | null>(null);
  protected readonly interval = signal<BillingInterval>('MONTH');
  /** Return from Checkout: 'waiting' until the webhook switched the plan, then 'done' (or 'slow'). */
  protected readonly checkout = signal<'waiting' | 'done' | 'slow' | 'cancelled' | null>(null);
  private pollTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly subscription = computed(() => this.overview()?.subscription ?? null);
  protected readonly entitled = computed(() => {
    const s = this.subscription();
    return !!s && ENTITLED.includes(s.status);
  });
  protected readonly visiblePlans = computed(() => (this.plans()?.plans ?? []).filter((p) => p.code !== 'ENTERPRISE'));
  protected readonly yearlyAvailable = computed(() =>
    (this.plans()?.plans ?? []).some((p) => p.prices.some((x) => x.interval === 'YEAR')),
  );
  protected readonly meters = computed(() => {
    const o = this.overview();
    if (!o) {
      return [];
    }
    return (['seats', 'savedSearches', 'aiAnalyses', 'exports'] as const).map((k) => ({ key: k, meter: o.usage[k] }));
  });

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    if (params.get('checkout') === 'success') {
      this.checkout.set('waiting');
    } else if (params.get('checkout') === 'cancelled') {
      this.checkout.set('cancelled');
    }
    if (params.get('checkout')) {
      void this.router.navigate([], { queryParams: { checkout: null }, replaceUrl: true });
    }
    this.api.plans().subscribe({
      next: (p) => this.plans.set(p),
      error: (err: unknown) => this.error.set(toApiError(err)),
    });
    effect(() => {
      const orgId = this.org.currentId();
      if (orgId) {
        untracked(() => this.load(orgId, 0));
      }
    });
    inject(DestroyRef).onDestroy(() => {
      if (this.pollTimer) {
        clearTimeout(this.pollTimer);
      }
    });
  }

  /** After Checkout the webhook usually arrives within seconds; poll a little while waiting for it. */
  private load(orgId: string, attempt: number): void {
    this.api.overview(orgId).subscribe({
      next: (o) => {
        this.overview.set(o);
        this.loading.set(false);
        if (this.checkout() === 'waiting') {
          if (o.subscription && ENTITLED.includes(o.subscription.status)) {
            this.checkout.set('done');
            this.org.reload();
          } else if (attempt < 15) {
            this.pollTimer = setTimeout(() => this.load(orgId, attempt + 1), 2000);
          } else {
            this.checkout.set('slow');
          }
        }
      },
      error: (err: unknown) => {
        this.error.set(toApiError(err));
        this.loading.set(false);
      },
    });
  }

  protected planName(code: PlanCode | string | undefined): string {
    this.translations();
    return code ? this.transloco.translate('billing.plan.' + code) : '';
  }

  protected price(p: PlanInfo): { amount: string; per: string; note: string } | null {
    this.translations();
    const price = p.prices.find((x) => x.interval === this.interval());
    if (!price) {
      return null;
    }
    return {
      amount: moneyMinor(price.unitAmount, price.currency, this.language.current()),
      per: this.transloco.translate(price.interval === 'YEAR' ? 'billing.perYear' : 'billing.perMonth'),
      note: price.taxBehavior === 'inclusive' ? this.transloco.translate('billing.gross')
        : this.transloco.translate('billing.netPlusVat'),
    };
  }

  protected features(p: PlanInfo): string[] {
    this.translations();
    const t = (k: string, n?: number | null): string => this.transloco.translate('billing.feature.' + k, { n });
    const l = p.limits;
    return [
      l.seats === null ? t('seatsUnlimited') : t('seats', l.seats),
      t('savedSearches', l.savedSearchesPerUser),
      t('ai', l.aiAnalysesPerMonth),
      l.exportsPerMonth === null ? t('exportsUnlimited') : t('exports', l.exportsPerMonth),
      l.instantEmails ? t('instant') : t('digest'),
    ];
  }

  protected meterLabel(m: Meter): string {
    this.translations();
    return m.limit === null
      ? `${m.used} · ${this.transloco.translate('billing.unlimited')}`
      : this.transloco.translate('billing.of', { used: m.used, limit: m.limit });
  }

  protected percent(m: Meter): number {
    return m.limit ? Math.min(100, Math.round((m.used / m.limit) * 100)) : 0;
  }

  protected invoiceAmount(minor: number | undefined, currency: string): string {
    return moneyMinor(minor ?? null, currency, this.language.current());
  }

  /** What the button on a plan card does for this organization (null = no button). */
  protected action(p: PlanInfo): 'current' | 'choose' | 'change' | null {
    const o = this.overview();
    if (!o) {
      return null;
    }
    if (p.code === o.plan) {
      return 'current';
    }
    if (!o.canManage || !o.configured || o.planSource === 'MANUAL' || !p.purchasable || !this.price(p)) {
      return null;
    }
    return this.entitled() ? (o.hasCustomer ? 'change' : null) : 'choose';
  }

  protected choose(p: PlanInfo): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.actionError.set(null);
    this.redirecting.set(p.code);
    this.api.checkout(orgId, p.code, this.interval(), this.language.current()).subscribe({
      next: (r) => window.location.assign(r.url),
      error: (err: unknown) => {
        this.actionError.set(toApiError(err));
        this.redirecting.set(null);
      },
    });
  }

  protected manage(): void {
    const orgId = this.org.currentId();
    if (!orgId) {
      return;
    }
    this.actionError.set(null);
    this.redirecting.set('portal');
    this.api.portal(orgId, this.language.current()).subscribe({
      next: (r) => window.location.assign(r.url),
      error: (err: unknown) => {
        this.actionError.set(toApiError(err));
        this.redirecting.set(null);
      },
    });
  }
}
