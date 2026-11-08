import { countryName, dateInputToIso, daysUntil, isoToDateInput, languageName, money } from './format';

describe('format', () => {
  it('names countries and languages in the UI language via Intl', () => {
    expect(countryName('DE', 'pl')).toBe('Niemcy');
    expect(countryName('DE', 'en')).toBe('Germany');
    expect(languageName('de', 'pl')).toBe('niemiecki');
    expect(countryName(undefined, 'pl')).toBe('');
  });

  it('formats money and survives currency codes Intl does not know', () => {
    expect(money(1559098, 'EUR', 'pl').replace(/\s/g, ' ')).toBe('1 559 098 €');
    expect(money(5, 'XYZQ', 'en')).toBe('5 XYZQ');
    expect(money(undefined, 'EUR', 'en')).toBe('');
  });

  it('converts date inputs to inclusive instant ranges and back', () => {
    const from = dateInputToIso('2026-11-10')!;
    const to = dateInputToIso('2026-11-10', true)!;
    expect(new Date(to).getTime() - new Date(from).getTime()).toBe(86_400_000);
    expect(isoToDateInput(from)).toBe('2026-11-10');
    expect(isoToDateInput(to, true)).toBe('2026-11-10');
  });

  it('counts days until a deadline', () => {
    const now = Date.parse('2026-11-08T12:00:00Z');
    expect(daysUntil('2026-11-10T12:00:00Z', now)).toBe(2);
    expect(daysUntil('2026-11-07T12:00:00Z', now)).toBe(-1);
  });
});
