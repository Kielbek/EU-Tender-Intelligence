import { HttpClient } from '@angular/common/http';
import {
  Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, input, signal, untracked, viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import type * as Leaflet from 'leaflet';
import { MapPoint, TenderMap } from '../../core/api/tender.model';
import { TenderService } from '../../core/api/tender.service';
import { compactNumber, countryName, integer } from '../../core/format/format';

type Level = 0 | 2 | 3;
type Scope = 'ALL' | 'MATCHES';

/** Zoom at which each level starts: countries, then NUTS 2 regions, then NUTS 3 subregions. */
const LEVEL_ZOOM: Record<Level, number> = { 0: 4, 2: 5.5, 3: 7 };
const EUROPE: [[number, number], [number, number]] = [[36, -10], [68, 31]];
/** Sequential ramp, one hue light → dark (accent blue); index 0 = fewest tenders. */
const RAMP = ['#dfe8fc', '#bccff8', '#8eaef0', '#5a86e2', '#2457d6'];
const NO_DATA = '#e9edf3';

export function levelForZoom(zoom: number): Level {
  return zoom >= LEVEL_ZOOM[3] ? 3 : zoom >= LEVEL_ZOOM[2] ? 2 : 0;
}

/** Quantile class breaks (4 thresholds for 5 colours) of the counts; equal counts share a class. */
export function breaks(counts: number[]): number[] {
  const sorted = [...counts].filter((c) => c > 0).sort((a, b) => a - b);
  if (!sorted.length) {
    return [];
  }
  return [0.2, 0.4, 0.6, 0.8].map((q) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))]);
}

export function rampColor(count: number, b: number[]): string {
  if (!count || !b.length) {
    return NO_DATA;
  }
  const i = b.findIndex((t) => count < t);
  return RAMP[i < 0 ? RAMP.length - 1 : i];
}

/**
 * Bubble diameter in px: area proportional to the count (sqrt). Countries are already shaded, so their bubbles stay
 * smaller (22–48 px) to avoid covering each other in Central Europe; regions 24–60 px.
 */
export function bubbleSize(count: number, max: number, level: Level): number {
  const [min, span] = level === 0 ? [22, 26] : [24, 36];
  return Math.round(min + span * Math.sqrt(count / Math.max(1, max)));
}

/**
 * Map of open tenders in Europe. Zoomed out: countries (shaded by count) with a bubble each; zooming in switches to
 * NUTS 2 regions and then NUTS 3 subregions, like a parcel-locker map. A bubble zooms in; at the subregion level it
 * opens the tenders of that subregion in the search. Borders: Natural Earth (public domain); region points:
 * Eurostat GISCO. No external map tiles are loaded.
 */
@Component({
  selector: 'eti-tender-map',
  imports: [TranslocoPipe],
  template: `
    <div class="flex flex-wrap items-center gap-2">
      @if (hasProfile()) {
        <div class="inline-flex rounded-[var(--radius-control)] border border-border-strong p-0.5 text-[12px]" role="group"
             [attr.aria-label]="'dashboard.map.scope' | transloco">
          @for (s of scopes; track s) {
            <button type="button" class="rounded-[5px] px-2.5 py-1 font-medium" [attr.aria-pressed]="scope() === s"
                    [class]="scope() === s ? 'bg-accent text-white' : 'text-ink-soft hover:text-ink'" (click)="scope.set(s)">
              {{ 'dashboard.map.scopes.' + s | transloco }}
            </button>
          }
        </div>
      }
      <div class="inline-flex rounded-[var(--radius-control)] border border-border-strong p-0.5 text-[12px]" role="group"
           [attr.aria-label]="'dashboard.map.level' | transloco">
        @for (l of levels; track l) {
          <button type="button" class="rounded-[5px] px-2.5 py-1 font-medium" [attr.aria-pressed]="level() === l"
                  [class]="level() === l ? 'bg-navy-950 text-white' : 'text-ink-soft hover:text-ink'" (click)="goToLevel(l)">
            {{ 'dashboard.map.levels.' + l | transloco }}
          </button>
        }
      </div>
      <button type="button" class="eti-btn-ghost ml-auto h-8 px-2.5 text-[12px]" (click)="reset()">{{ 'dashboard.map.reset' | transloco }}</button>
    </div>

    <div class="mt-3 grid gap-4 lg:grid-cols-[minmax(0,1fr)_260px]">
      <div class="relative self-start">
        <div #map class="eti-map h-[360px] w-full overflow-hidden rounded-[var(--radius-control)] border border-border sm:h-[460px]"
             role="region" [attr.aria-label]="'dashboard.map.aria' | transloco"></div>
        @if (loading()) {
          <div class="pointer-events-none absolute top-3 left-1/2 -translate-x-1/2 rounded-full bg-surface/95 px-3 py-1 text-[12px] text-ink-muted shadow">
            {{ 'common.loading' | transloco }}
          </div>
        }
        <div class="pointer-events-none absolute bottom-2 left-2 hidden rounded-md sm:block bg-surface/95 px-2.5 py-2 text-[11px] text-ink-muted shadow-sm">
          @if (level() === 0 && legend(); as lg) {
            <div class="mb-0.5">{{ 'dashboard.map.legendShade' | transloco }}</div>
            <div class="flex items-center gap-0.5" aria-hidden="true">
              @for (c of lg.colors; track c) { <span class="h-2.5 w-6 first:rounded-l-sm last:rounded-r-sm" [style.background]="c"></span> }
            </div>
            <div class="mt-0.5 flex justify-between gap-6"><span>{{ lg.min }}</span><span>{{ lg.max }}</span></div>
          }
          <div class="mt-1">{{ 'dashboard.map.legendSize' | transloco }}</div>
        </div>
      </div>

      <aside class="min-w-0">
        <p class="text-[12px] font-semibold tracking-[0.06em] text-ink-faint uppercase">
          {{ 'dashboard.map.top' | transloco: { level: ('dashboard.map.levels.' + level() | transloco) } }}
        </p>
        <ol class="mt-2 space-y-1">
          @for (p of top(); track p.code) {
            <li>
              <button type="button" class="group w-full rounded-md px-2 py-1.5 text-left hover:bg-canvas" (click)="focusPoint(p)">
                <span class="flex items-baseline justify-between gap-2 text-[13px]">
                  <span class="min-w-0 truncate text-ink-soft group-hover:text-accent">{{ label(p) }}</span>
                  <span class="eti-num shrink-0 text-ink">{{ fmt(p.count) }}</span>
                </span>
                @if (level() !== 0) {
                  <span class="block truncate text-[11px] text-ink-faint">{{ country(p.country) }} · {{ p.code }}</span>
                }
                <span class="mt-1 block h-1.5 rounded-full bg-track" aria-hidden="true">
                  <span class="block h-1.5 rounded-full bg-chart" [style.width.%]="(p.count / (top()[0]?.count || 1)) * 100"></span>
                </span>
              </button>
            </li>
          } @empty {
            <li class="px-2 py-2 text-[13px] text-ink-muted">{{ (loading() ? 'common.loading' : 'dashboard.map.empty') | transloco }}</li>
          }
        </ol>
        @if (data(); as d) {
          <p class="mt-3 px-2 text-[11px] text-ink-muted">
            {{ 'dashboard.map.total' | transloco: { n: fmt(d.total) } }}
            @if (level() !== 0 && d.withoutRegion) { {{ 'dashboard.map.without' | transloco: { n: fmt(d.withoutRegion) } }} }
          </p>
        }
      </aside>
    </div>
    <p class="mt-2 text-[11px] text-ink-faint">{{ 'dashboard.map.hint' | transloco }}</p>
  `,
})
export class TenderMapComponent {
  readonly orgId = input.required<string>();
  readonly lang = input.required<string>();
  readonly hasProfile = input(false);

  private readonly api = inject(TenderService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly el = viewChild.required<ElementRef<HTMLDivElement>>('map');

  protected readonly scopes: Scope[] = ['ALL', 'MATCHES'];
  protected readonly levels: Level[] = [0, 2, 3];
  protected readonly scope = signal<Scope>('ALL');
  protected readonly level = signal<Level>(0);
  protected readonly data = signal<TenderMap | null>(null);
  protected readonly loading = signal(true);
  /** Points inside the current view, biggest first. */
  protected readonly inView = signal<MapPoint[]>([]);
  protected readonly top = computed(() => this.inView().slice(0, 8));

  private L?: typeof Leaflet;
  private map?: Leaflet.Map;
  private countries?: Leaflet.GeoJSON;
  private bubbles?: Leaflet.LayerGroup;
  private readonly cache = new Map<string, TenderMap>();
  private countryCounts = new Map<string, number>();
  private countryBreaks: number[] = [];

  /** Country shading legend: the ramp from the smallest to the largest country count. */
  protected readonly legend = computed(() => {
    const d = this.data();
    if (!d || d.level !== 0 || !d.points.length) {
      return null;
    }
    const lang = this.lang();
    return { colors: RAMP, min: compactNumber(d.points.at(-1)!.count, lang), max: compactNumber(d.points[0].count, lang) };
  });

  constructor() {
    const destroy = inject(DestroyRef);
    afterNextRender(() => void this.init());
    destroy.onDestroy(() => this.map?.remove());
    effect(() => {
      const scope = this.scope();
      const level = this.level();
      this.orgId();
      untracked(() => this.fetch(level, scope));
    });
  }

  private async init(): Promise<void> {
    const L = (this.L = await import('leaflet'));
    const map = (this.map = L.map(this.el().nativeElement, {
      zoomSnap: 0.5, minZoom: 3, maxZoom: 9, scrollWheelZoom: false, dragging: !L.Browser.mobile,
      maxBounds: L.latLngBounds([[20, -45], [78, 65]]), attributionControl: true, zoomControl: true,
    }));
    map.attributionControl.setPrefix(false).addAttribution(this.transloco.translate('dashboard.map.attribution'));
    map.fitBounds(EUROPE);
    // Wheel zoom only after the user clicks the map, so the page keeps scrolling normally.
    map.on('click focus', () => map.scrollWheelZoom.enable());
    map.on('blur mouseout', () => map.scrollWheelZoom.disable());
    map.on('zoomend', () => {
      const l = levelForZoom(map.getZoom());
      if (l !== this.level()) {
        this.level.set(l);
      } else {
        this.draw();
      }
    });
    map.on('moveend', () => this.draw());
    this.bubbles = L.layerGroup().addTo(map);
    this.http.get<GeoJSON.FeatureCollection>('/geo/europe.json').subscribe((geo) => {
      this.countries = L.geoJSON(geo, {
        style: (f) => this.countryStyle(String(f?.id)),
        onEachFeature: (f, layer) => {
          const iso = String(f.id);
          layer.on('click', () => {
            if (this.countryCounts.has(iso)) {
              map.fitBounds((layer as Leaflet.Polygon).getBounds(), { maxZoom: LEVEL_ZOOM[2] + 0.5 });
            }
          });
          layer.bindTooltip(() => this.countryTooltip(iso), { sticky: true, className: 'eti-map-tip' });
        },
      }).addTo(map);
      this.countries.bringToBack();
    });
    this.draw();
  }

  private fetch(level: Level, scope: Scope): void {
    const key = `${this.orgId()}:${scope}:${level}`;
    const hit = this.cache.get(key);
    if (hit) {
      this.show(hit);
      return;
    }
    this.loading.set(true);
    this.api.tenderMap(this.orgId(), level, scope).subscribe({
      next: (m) => {
        this.cache.set(key, m);
        if (this.level() === level && this.scope() === scope) {
          this.show(m);
        }
      },
      error: () => this.loading.set(false),
    });
    if (level !== 0) {
      // country shading stays in sync with the scope at every level
      const k0 = `${this.orgId()}:${scope}:0`;
      if (!this.cache.has(k0)) {
        this.api.tenderMap(this.orgId(), 0, scope).subscribe((m) => {
          this.cache.set(k0, m);
          this.shadeCountries(m);
        });
      } else {
        this.shadeCountries(this.cache.get(k0)!);
      }
    }
  }

  private show(m: TenderMap): void {
    this.loading.set(false);
    this.data.set(m);
    if (m.level === 0) {
      this.shadeCountries(m);
    }
    this.draw();
  }

  private shadeCountries(m: TenderMap): void {
    this.countryCounts = new Map(m.points.map((p) => [p.country, p.count]));
    this.countryBreaks = breaks(m.points.map((p) => p.count));
    this.countries?.setStyle((f) => this.countryStyle(String(f?.id)));
  }

  private countryStyle(iso: string): Leaflet.PathOptions {
    const n = this.countryCounts.get(iso) ?? 0;
    const shaded = this.level() === 0;
    return {
      color: '#ffffff', weight: 0.8, fillOpacity: 1,
      fillColor: shaded ? rampColor(n, this.countryBreaks) : n ? '#f1f4fa' : NO_DATA,
    };
  }

  private countryTooltip(iso: string): string {
    const n = this.countryCounts.get(iso);
    return `<strong>${escapeHtml(this.country(iso))}</strong>`
      + (n != null ? `<br>${escapeHtml(this.countLabel(n))}` : '');
  }

  /** Bubbles of the points inside the view (padded), sized by count relative to the largest of the level. */
  private draw(): void {
    const L = this.L;
    const map = this.map;
    const d = this.data();
    if (!L || !map || !this.bubbles || !d || d.level !== this.level()) {
      return;
    }
    this.countries?.setStyle((f) => this.countryStyle(String(f?.id)));
    this.bubbles.clearLayers();
    const bounds = map.getBounds().pad(0.15);
    const max = d.points[0]?.count ?? 1;
    const visible: MapPoint[] = [];
    const placed: { x: number; y: number; r: number }[] = [];
    for (const p of d.points) {
      if (!bounds.contains([p.lat, p.lon])) {
        continue;
      }
      visible.push(p);
      // narrow maps (phones) get proportionally smaller bubbles
      const size = Math.max(18, Math.round(bubbleSize(p.count, max, d.level) * Math.min(1, map.getSize().x / 700)));
      // Biggest first; a bubble that would mostly cover one already drawn is left out (it reappears when zooming in;
      // the country stays shaded and listed on the right).
      const xy = map.latLngToContainerPoint([p.lat, p.lon]);
      if (placed.some((q) => Math.hypot(q.x - xy.x, q.y - xy.y) < 0.9 * (q.r + size / 2))) {
        continue;
      }
      placed.push({ x: xy.x, y: xy.y, r: size / 2 });
      const marker = L.marker([p.lat, p.lon], {
        icon: L.divIcon({ className: 'eti-map-bubble', html: size >= 30 ? `<span>${this.fmtCompact(p.count)}</span>` : '', iconSize: [size, size] }),
        keyboard: true, riseOnHover: true, title: `${this.label(p)}: ${this.countLabel(p.count)}`,
        zIndexOffset: Math.round(p.count),
      });
      marker.bindTooltip(`<strong>${escapeHtml(this.label(p))}</strong>`
        + (d.level ? `<br><span class="eti-map-tip-sub">${escapeHtml(this.country(p.country))} · ${p.code}</span>` : '')
        + `<br>${escapeHtml(this.countLabel(p.count))}`, { direction: 'top', offset: [0, -size / 2], className: 'eti-map-tip' });
      marker.on('click keypress', () => this.open(p));
      marker.addTo(this.bubbles);
    }
    this.inView.set(visible);
  }

  /** Zooms in one level; at subregions opens a popup with a link to the tenders there. */
  private open(p: MapPoint): void {
    const map = this.map!;
    const level = this.data()?.level ?? 0;
    if (level === 0) {
      map.flyTo([p.lat, p.lon], LEVEL_ZOOM[2] + 0.5, { duration: 0.6 });
      return;
    }
    if (level === 2) {
      map.flyTo([p.lat, p.lon], LEVEL_ZOOM[3] + 0.5, { duration: 0.6 });
      return;
    }
    const box = document.createElement('div');
    box.className = 'eti-map-popup';
    const title = document.createElement('p');
    title.className = 'eti-map-popup-title';
    title.textContent = p.name;
    const sub = document.createElement('p');
    sub.className = 'eti-map-popup-sub';
    sub.textContent = `${this.country(p.country)} · ${p.code} · ${this.countLabel(p.count)}`;
    const link = document.createElement('button');
    link.type = 'button';
    link.className = 'eti-btn-primary eti-map-popup-btn';
    link.textContent = this.transloco.translate(this.scope() === 'MATCHES' ? 'dashboard.map.showMatches' : 'dashboard.map.showTenders');
    link.addEventListener('click', () => this.openSearch(p));
    box.append(title, sub, link);
    this.L!.popup({ closeButton: true, offset: [0, -8] }).setLatLng([p.lat, p.lon]).setContent(box).openOn(map);
  }

  private openSearch(p: MapPoint): void {
    void this.router.navigate(['/search'], { queryParams: { nuts: p.code, status: 'OPEN' } });
  }

  protected focusPoint(p: MapPoint): void {
    const level = this.level();
    if (level === 3) {
      this.map?.flyTo([p.lat, p.lon], Math.max(this.map.getZoom(), LEVEL_ZOOM[3] + 0.5), { duration: 0.5 });
      setTimeout(() => this.open(p), 600);
    } else {
      this.open(p);
    }
  }

  protected goToLevel(l: Level): void {
    const map = this.map;
    if (!map) {
      return;
    }
    if (l === 0) {
      map.fitBounds(EUROPE);
    } else {
      map.setZoom(LEVEL_ZOOM[l] + (l === 2 ? 0 : 0.5));
    }
  }

  protected reset(): void {
    this.map?.closePopup();
    this.map?.fitBounds(EUROPE);
  }

  protected label(p: MapPoint): string {
    return this.data()?.level === 0 ? this.country(p.country) : p.name;
  }

  protected country(iso: string): string {
    return countryName(iso, this.lang());
  }

  protected fmt(n: number): string {
    return integer(n, this.lang());
  }

  private fmtCompact(n: number): string {
    return compactNumber(n, this.lang());
  }

  private countLabel(n: number): string {
    return this.transloco.translate(this.scope() === 'MATCHES' ? 'dashboard.map.matchesN' : 'dashboard.map.openN',
      { n: this.fmt(n) });
  }
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}
