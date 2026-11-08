import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { translocoTesting } from '../../testing/transloco-testing';
import { DateInputComponent } from './date-input.component';
import { SelectComponent, SelectOption } from './select.component';

const OPTIONS: SelectOption[] = [
  { value: '', label: 'Wszystkie' },
  { value: 'WORKS', label: 'Roboty budowlane' },
  { value: 'SUPPLIES', label: 'Dostawy' },
  { value: 'SERVICES', label: 'Usługi' },
];

@Component({
  imports: [ReactiveFormsModule, SelectComponent, DateInputComponent],
  template: `
    <label for="type">Rodzaj</label>
    <eti-select inputId="type" [formControl]="type" [options]="options" />
    <eti-select inputId="plain" [options]="options" [(value)]="plain" ariaLabel="Plain" />
    <eti-date-input inputId="date" [formControl]="date" locale="pl" />
  `,
})
class Host {
  readonly options = OPTIONS;
  readonly type = new FormControl('SUPPLIES');
  readonly date = new FormControl('2026-11-20');
  readonly plain = signal<string | null>('');
}

describe('custom form controls', () => {
  beforeEach(() => {
    window.matchMedia = ((q: string) => ({ matches: false, media: q })) as unknown as typeof window.matchMedia;
    TestBed.configureTestingModule({ imports: [Host, translocoTesting()] });
  });

  function setup() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
  }

  it('select shows the form value, opens a listbox and writes the choice back', async () => {
    const { fixture, el, host } = setup();
    const trigger = el.querySelector<HTMLButtonElement>('#type')!;
    expect(trigger.getAttribute('role')).toBe('combobox');
    expect(trigger.textContent).toContain('Dostawy');
    trigger.click();
    fixture.detectChanges();
    const options = el.querySelectorAll('[role=option]');
    expect(options.length).toBe(4);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    (options[3] as HTMLElement).click();
    fixture.detectChanges();
    expect(host.type.value).toBe('SERVICES');
    expect(el.querySelector('[role=listbox]')).toBeNull();
    expect(trigger.textContent).toContain('Usługi');
  });

  it('select is keyboard operable and supports two-way value binding', () => {
    const { fixture, el, host } = setup();
    const trigger = el.querySelector<HTMLButtonElement>('#plain')!;
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    fixture.detectChanges();
    const key = (k: string) => {
      el.querySelector('[role=listbox]')!.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
      fixture.detectChanges();
    };
    key('ArrowDown');
    key('ArrowDown');
    key('Enter');
    expect(host.plain()).toBe('SUPPLIES');
    // Escape closes without changing the value.
    trigger.click();
    fixture.detectChanges();
    key('Escape');
    expect(el.querySelector('[role=listbox]')).toBeNull();
    expect(host.plain()).toBe('SUPPLIES');
  });

  it('select follows the disabled state of the form control', () => {
    const { fixture, el, host } = setup();
    host.type.disable();
    fixture.detectChanges();
    expect(el.querySelector<HTMLButtonElement>('#type')!.disabled).toBe(true);
  });

  it('date input shows a localised date and picks a day from its own calendar', () => {
    const { fixture, el, host } = setup();
    const trigger = el.querySelector<HTMLButtonElement>('#date')!;
    expect(trigger.textContent).toContain('20.11.2026');
    trigger.click();
    fixture.detectChanges();
    expect(el.querySelector('[role=dialog]')!.textContent).toContain('listopad 2026');
    el.querySelector<HTMLButtonElement>('[data-iso="2026-11-27"]')!.click();
    fixture.detectChanges();
    expect(host.date.value).toBe('2026-11-27');
    expect(el.querySelector('[role=dialog]')).toBeNull();
    // Clear button empties the value.
    el.querySelector<HTMLButtonElement>('[aria-label="Wyczyść datę"]')!.click();
    fixture.detectChanges();
    expect(host.date.value).toBe('');
    expect(trigger.textContent).toContain('dd.mm.rrrr');
  });
});
