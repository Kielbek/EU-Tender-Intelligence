import { SearchForm, activeFilterCount, apiFilters, emptyForm, formFromParams, paramsFromApiFilters, paramsFromForm } from './search-state';

describe('search state', () => {
  it('round-trips URL params and builds API filters', () => {
    const form = formFromParams({ country: ['DE', 'PL'], cpv: '45000000', q: ' szkoła ', valueMinEur: '500000' });
    expect(form.country).toEqual(['DE', 'PL']);
    expect(form.cpv).toEqual(['45000000']);
    expect(paramsFromForm(form, 2)).toEqual({ country: ['DE', 'PL'], cpv: ['45000000'], q: 'szkoła', valueMinEur: '500000', page: '2' });
    expect(apiFilters(form)).toEqual({ country: ['DE', 'PL'], cpv: ['45000000'], q: 'szkoła', valueMinEur: 500000 });
    expect(activeFilterCount(form)).toBe(3);
  });

  it('accepts numbers written by type="number" inputs', () => {
    const form = { ...emptyForm(), valueMinEur: 750000 as unknown as string, valueMaxEur: null as unknown as string } as SearchForm;
    expect(paramsFromForm(form)).toEqual({ valueMinEur: '750000' });
    expect(apiFilters(form)).toEqual({ valueMinEur: 750000 });
  });

  it('makes the "to" date inclusive and restores it from saved filters', () => {
    const form = { ...emptyForm(), deadlineFrom: '2026-11-10', deadlineTo: '2026-11-20', nuts: 'pl91' };
    const f = apiFilters(form);
    expect(new Date(f.deadlineTo as string).getDate()).toBe(21);
    expect(f.nuts).toBe('PL91');
    expect(paramsFromApiFilters(f)).toMatchObject({ deadlineFrom: '2026-11-10', deadlineTo: '2026-11-20' });
  });

  it('rejects negative or non-numeric values', () => {
    expect(apiFilters({ ...emptyForm(), valueMinEur: '-5', valueMaxEur: 'abc' })).toEqual({});
  });
});
