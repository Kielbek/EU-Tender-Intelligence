/**
 * Locale-aware formatting based on the platform Intl API – country and language names come from the browser's CLDR
 * data, so all 24 EU languages work without hand-written dictionaries.
 */
const displayNames = new Map<string, Intl.DisplayNames>();

function names(locale: string, type: 'region' | 'language'): Intl.DisplayNames | null {
  const key = `${locale}:${type}`;
  if (!displayNames.has(key)) {
    try {
      displayNames.set(key, new Intl.DisplayNames([locale], { type }));
    } catch {
      return null;
    }
  }
  return displayNames.get(key) ?? null;
}

export function countryName(code: string | undefined | null, locale: string): string {
  if (!code) {
    return '';
  }
  try {
    return names(locale, 'region')?.of(code) ?? code;
  } catch {
    return code;
  }
}

export function languageName(code: string | undefined | null, locale: string): string {
  if (!code) {
    return '';
  }
  try {
    return names(locale, 'language')?.of(code) ?? code;
  } catch {
    return code;
  }
}

export function money(value: number | null | undefined, currency: string | null | undefined, locale: string): string {
  if (value === null || value === undefined) {
    return '';
  }
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currency || 'EUR',
      maximumFractionDigits: 0,
    }).format(value);
  } catch {
    // Unknown currency code from the source: show number + code instead of failing.
    return `${new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(value)} ${currency ?? ''}`.trim();
  }
}

export function compactNumber(value: number, locale: string): string {
  return new Intl.NumberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

export function integer(value: number, locale: string): string {
  return new Intl.NumberFormat(locale).format(value);
}

/** Date-time in the user's time zone, e.g. "8 gru 2026, 09:00". */
export function dateTime(iso: string | undefined | null, locale: string, timeZone?: string): string {
  if (!iso) {
    return '';
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(iso));
}

export function date(iso: string | undefined | null, locale: string, timeZone?: string): string {
  if (!iso) {
    return '';
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone }).format(new Date(iso));
}

/** Whole days from now until the instant (negative = past). */
export function daysUntil(iso: string, now = Date.now()): number {
  return Math.ceil((new Date(iso).getTime() - now) / 86_400_000);
}

/** "2026-11-10" (date input, local) → ISO instant at local midnight; `endExclusive` gives the next midnight. */
export function dateInputToIso(value: string | undefined, endExclusive = false): string | undefined {
  if (!value) {
    return undefined;
  }
  const [y, m, d] = value.split('-').map(Number);
  const dt = new Date(y, m - 1, d + (endExclusive ? 1 : 0));
  return dt.toISOString();
}

/** Inverse of dateInputToIso for showing stored filters in date inputs. */
export function isoToDateInput(iso: string | undefined, endExclusive = false): string {
  if (!iso) {
    return '';
  }
  const dt = new Date(iso);
  if (endExclusive) {
    dt.setDate(dt.getDate() - 1);
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`;
}

/** Amount in minor units (grosze, cents) as charged by Stripe, e.g. 24900 PLN → "249,00 zł". */
export function moneyMinor(minor: number | null | undefined, currency: string | null | undefined, locale: string): string {
  if (minor === null || minor === undefined || !currency) {
    return '';
  }
  const code = currency.toUpperCase();
  try {
    const digits = new Intl.NumberFormat('en', { style: 'currency', currency: code }).resolvedOptions()
      .maximumFractionDigits ?? 2;
    return new Intl.NumberFormat(locale, { style: 'currency', currency: code }).format(minor / 10 ** digits);
  } catch {
    return `${(minor / 100).toFixed(2)} ${code}`;
  }
}
