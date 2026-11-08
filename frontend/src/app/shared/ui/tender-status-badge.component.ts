import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { TenderStatus } from '../../core/api/tender.model';
import { BadgeComponent, BadgeTone } from './badge.component';

const TONE: Record<TenderStatus, BadgeTone> = {
  OPEN: 'success',
  PLANNED: 'info',
  CLOSED: 'neutral',
  AWARDED: 'info',
  CANCELLED: 'danger',
  UNKNOWN: 'neutral',
};

/** Status is always shown as text; colour only supports it. */
@Component({
  selector: 'eti-tender-status',
  imports: [BadgeComponent, TranslocoPipe],
  template: `<eti-badge [tone]="tone()">{{ 'tender.status.' + status() | transloco }}</eti-badge>`,
})
export class TenderStatusBadgeComponent {
  readonly status = input.required<TenderStatus>();
  protected readonly tone = computed(() => TONE[this.status()]);
}
