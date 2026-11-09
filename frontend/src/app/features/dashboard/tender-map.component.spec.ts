import { breaks, bubbleSize, levelForZoom, rampColor } from './tender-map.component';

describe('tender map', () => {
  it('switches from countries to regions to subregions when zooming in', () => {
    expect(levelForZoom(4)).toBe(0);
    expect(levelForZoom(5.5)).toBe(2);
    expect(levelForZoom(6.5)).toBe(2);
    expect(levelForZoom(7)).toBe(3);
  });

  it('shades countries by quantiles, light to dark, and leaves countries without tenders grey', () => {
    const b = breaks([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    expect(b).toEqual([3, 5, 7, 9]);
    expect(rampColor(1, b)).toBe('#dfe8fc');
    expect(rampColor(10, b)).toBe('#2457d6');
    expect(rampColor(0, b)).toBe('#e9edf3');
    expect(breaks([])).toEqual([]);
  });

  it('sizes bubbles by area, smaller for countries', () => {
    expect(bubbleSize(100, 100, 0)).toBe(48);
    expect(bubbleSize(25, 100, 2)).toBe(42);
    expect(bubbleSize(0, 100, 3)).toBe(24);
  });
});
