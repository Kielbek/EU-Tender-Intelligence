// Builds public/geo/europe.json – country borders for the dashboard map.
// Source: world-atlas (Natural Earth 1:50m, public domain). Run: node scripts/europe-geo.mjs
import { readFileSync, writeFileSync } from 'node:fs';
import { feature } from 'topojson-client';

/** ISO 3166 numeric → alpha-2 for the countries shown around Europe. */
const ISO = {
  '008': 'AL', '020': 'AD', '040': 'AT', '056': 'BE', '070': 'BA', '100': 'BG', '112': 'BY', '191': 'HR', '196': 'CY',
  '203': 'CZ', '208': 'DK', '233': 'EE', '246': 'FI', '250': 'FR', '268': 'GE', '276': 'DE', '300': 'GR', '348': 'HU',
  '352': 'IS', '372': 'IE', '380': 'IT', '428': 'LV', '438': 'LI', '440': 'LT', '442': 'LU', '470': 'MT', '492': 'MC',
  '498': 'MD', '499': 'ME', '528': 'NL', '578': 'NO', '616': 'PL', '620': 'PT', '642': 'RO', '643': 'RU', '674': 'SM',
  '688': 'RS', '703': 'SK', '705': 'SI', '724': 'ES', '752': 'SE', '756': 'CH', '792': 'TR', '804': 'UA', '807': 'MK',
  '826': 'GB', '012': 'DZ', '504': 'MA', '788': 'TN', '434': 'LY', '818': 'EG', '051': 'AM', '031': 'AZ', '760': 'SY',
  '368': 'IQ', '364': 'IR', '400': 'JO', '422': 'LB', '376': 'IL', '398': 'KZ', '304': 'GL', '336': 'VA',
};
const BOX = [-32, 26, 50, 72]; // west, south, east, north

const topo = JSON.parse(readFileSync(new URL('../node_modules/world-atlas/countries-50m.json', import.meta.url)));
const all = feature(topo, topo.objects.countries).features;

// Longitudes are clamped to the map's area: Russia's mainland crosses the antimeridian, which would otherwise draw a
// band across the whole map.
const lon = (x) => Math.min(70, Math.max(-45, x));
const round = (c) => (typeof c[0] === 'number' ? [Math.round(lon(c[0]) * 100) / 100, Math.round(c[1] * 100) / 100] : c.map(round));
const inBox = ([x, y]) => x >= BOX[0] - 15 && x <= BOX[2] + 15 && y >= BOX[1] - 10 && y <= BOX[3] + 10;
/** Keeps only polygons with a point near Europe (drops e.g. French Guiana, the Russian Far East). */
function clip(geometry) {
  const polys = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates;
  const kept = polys.filter((p) => p[0].some(inBox));
  return kept.length ? { type: 'MultiPolygon', coordinates: round(kept) } : null;
}

const features = all
  .map((f) => ({ f, iso: ISO[f.id] }))
  .filter(({ f, iso }) => iso && f.geometry)
  .map(({ f, iso }) => ({ type: 'Feature', id: iso, properties: {}, geometry: clip(f.geometry) }))
  .filter((f) => f.geometry);

writeFileSync(new URL('../public/geo/europe.json', import.meta.url), JSON.stringify({ type: 'FeatureCollection', features }));
console.log(features.length, 'countries');
