import { initials, parseEmails, relative } from './organization.page';

describe('organization page helpers', () => {
  it('splits pasted addresses on commas, semicolons and new lines, dropping duplicates', () => {
    const r = parseEmails('Anna@Firma.pl, jan@firma.pl;\nanna@firma.pl  zly-adres');
    expect(r.valid).toEqual(['anna@firma.pl', 'jan@firma.pl']);
    expect(r.invalid).toEqual(['zly-adres']);
    expect(parseEmails('  ').valid).toEqual([]);
  });

  it('formats relative times in the UI language', () => {
    const now = Date.parse('2026-11-09T12:00:00Z');
    expect(relative('2026-11-06T12:00:00Z', 'pl', now)).toBe('3 dni temu');
    expect(relative('2026-11-09T11:30:00Z', 'en', now)).toBe('30 minutes ago');
    expect(relative(null, 'pl', now)).toBeNull();
  });

  it('builds avatar initials from the name, else from the e-mail', () => {
    expect(initials('Anna Maria Nowak', 'a@x.pl')).toBe('AN');
    expect(initials('', 'jan@x.pl')).toBe('JA');
  });
});
