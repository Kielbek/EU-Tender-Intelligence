import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { ApiError, toApiError } from '../../core/api/api-error';
import { Membership, OrganizationService } from '../../core/api/organization.service';
import { ApiErrorAlertComponent } from '../../shared/ui/api-error-alert.component';

@Component({
  selector: 'eti-accept-invitation-page',
  imports: [RouterLink, TranslocoPipe, ApiErrorAlertComponent],
  template: `
    <div class="mx-auto max-w-lg">
      <h1 class="eti-h1">{{ 'invitation.title' | transloco }}</h1>
      <div class="mt-4" aria-live="polite">
        @if (!token()) {
          <p role="alert" class="eti-alert-error">{{ 'invitation.missing' | transloco }}</p>
        } @else if (joined(); as m) {
          <p role="status" class="eti-alert-success">{{ 'invitation.success' | transloco: { name: m.name } }}</p>
          <a [routerLink]="['/organization']" [queryParams]="{ org: m.id }" class="eti-btn-primary mt-4">
            {{ 'invitation.goToOrg' | transloco }}
          </a>
        } @else if (error()) {
          <eti-api-error [error]="error()" />
        } @else {
          <p class="text-sm text-ink-muted">{{ 'invitation.pending' | transloco }}</p>
        }
      </div>
    </div>
  `,
})
export class AcceptInvitationPage implements OnInit {
  private readonly organizations = inject(OrganizationService);
  readonly token = input<string>();
  protected readonly joined = signal<Membership | null>(null);
  protected readonly error = signal<ApiError | null>(null);

  ngOnInit(): void {
    const token = this.token();
    if (token) {
      this.organizations.acceptInvitation(token).subscribe({
        next: (m) => this.joined.set(m),
        error: (err: unknown) => this.error.set(toApiError(err)),
      });
    }
  }
}
