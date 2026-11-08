import { TestBed } from '@angular/core/testing';
import { translocoTesting } from '../../testing/transloco-testing';
import { BarListComponent } from './bar-list.component';
import { ColumnChartComponent } from './column-chart.component';

describe('charts', () => {
  beforeEach(() => TestBed.configureTestingModule({ imports: [translocoTesting()] }));

  it('column chart uses a clean axis maximum and exposes values to keyboard users and in a table', () => {
    const fixture = TestBed.createComponent(ColumnChartComponent);
    fixture.componentRef.setInput('points', [
      { key: 'a', label: '1 lis', value: 3799 },
      { key: 'b', label: '2 lis', value: 0 },
    ]);
    fixture.componentRef.setInput('caption', 'Ogłoszenia');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const ticks = Array.from(el.querySelectorAll('span[aria-hidden="true"]')).map((s) => s.textContent!.trim());
    expect(ticks).toContain('5000');
    const columns = el.querySelectorAll('[tabindex="0"]');
    expect(columns[0].getAttribute('aria-label')).toBe('1 lis: 3799');
    (columns[0] as HTMLElement).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    expect(el.querySelector('[role="tooltip"]')!.textContent).toContain('3799');
    expect(el.querySelectorAll('table tbody tr').length).toBe(2);
  });

  it('bar list labels every bar with its value', () => {
    const fixture = TestBed.createComponent(BarListComponent);
    fixture.componentRef.setInput('items', [
      { key: 'DE', label: 'Niemcy', value: 1733 },
      { key: 'PL', label: 'Polska', value: 1575 },
    ]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Niemcy');
    expect(text).toContain('1733');
    expect(text).toContain('1575');
  });
});
