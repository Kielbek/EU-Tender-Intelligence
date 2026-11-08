import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError } from '../../core/api/api-error';
import { errorKey } from '../../core/api/error-key';

/** Accessible error banner for a failed API call; shows the correlation id for support requests. */
@Component({
  selector: 'eti-api-error',
  imports: [TranslocoPipe],
  template: `
    @if (error(); as e) {
      <div role="alert" class="eti-alert-error">
        <p>{{ key() | transloco }}</p>
        @if (e.correlationId && key() === 'errors.generic') {
          <p class="mt-1 text-xs opacity-80">{{ 'errors.correlation' | transloco }}: <code>{{ e.correlationId }}</code></p>
        }
      </div>
    }
  `,
})
export class ApiErrorAlertComponent {
  readonly error = input<ApiError | null>(null);
  protected readonly key = computed(() => {
    const e = this.error();
    return e ? errorKey(e) : '';
  });
}
