import { cpvStems, highlight } from './cpv-picker.component';

describe('CPV suggestions', () => {
  it('cuts inflected endings like the server and ignores code queries', () => {
    expect(cpvStems('Drogi remont')).toEqual(['drog', 'remo']);
    expect(cpvStems('4523')).toEqual([]);
    expect(cpvStems('  ')).toEqual([]);
  });

  it('highlights stems only at the start of words, keeping Polish letters', () => {
    const parts = highlight('Roboty w zakresie dróg, podróże', cpvStems('drogi'));
    expect(parts.filter((p) => p.hit).map((p) => p.text)).toEqual(['dróg']);
    expect(parts.map((p) => p.text).join('')).toBe('Roboty w zakresie dróg, podróże');
  });

  it('marks every word of the query', () => {
    const parts = highlight('Usługi sprzątania biurowców', cpvStems('sprzatanie biur'));
    expect(parts.filter((p) => p.hit).map((p) => p.text)).toEqual(['sprzątan', 'biur']);
  });
});
