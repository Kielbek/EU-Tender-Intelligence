import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { SelectOption } from './select.component';

/**
 * `values | etiOptions: 'watch.status.'` → translated {@link SelectOption}s. Impure so labels follow the active
 * language once it loads, but returns the same array instance while labels are unchanged (no re-render churn).
 */
@Pipe({ name: 'etiOptions', pure: false })
export class OptionsPipe implements PipeTransform {
  private readonly transloco = inject(TranslocoService);
  private cacheKey = '';
  private cache: SelectOption[] = [];

  transform(values: readonly string[], prefix: string): SelectOption[] {
    const labels = values.map((v) => this.transloco.translate(prefix + v));
    const key = values.join('|') + '§' + labels.join('|');
    if (key !== this.cacheKey) {
      this.cacheKey = key;
      this.cache = values.map((v, i) => ({ value: v, label: labels[i] }));
    }
    return this.cache;
  }
}
