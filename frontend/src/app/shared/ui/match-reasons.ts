import { TranslocoService } from '@jsverse/transloco';
import { MatchReason } from '../../core/api/matching.model';
import { countryName, money } from '../../core/format/format';

/** Human sentence for a reason code, with parameters formatted for the UI language (countries, EUR values, types). */
export function reasonText(reason: MatchReason, transloco: TranslocoService, lang: string): string {
  const p: Record<string, string> = {};
  for (const [key, value] of Object.entries(reason.params ?? {})) {
    if (key === 'country') {
      p[key] = countryName(String(value), lang);
    } else if (key === 'value' || key === 'min' || key === 'max') {
      p[key] = money(Number(value), 'EUR', lang);
    } else if (key === 'type') {
      p[key] = transloco.translate(`tender.contractType.${value}`).toLocaleLowerCase(lang);
    } else {
      p[key] = String(value);
    }
  }
  return transloco.translate(`match.reason.${reason.code}`, p);
}
