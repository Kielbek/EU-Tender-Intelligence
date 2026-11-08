import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'eti-verify-email-page',
  imports: [RouterLink, TranslocoPipe],
  template: `
    <h1 class="text-xl font-semibold tracking-tight">{{ 'auth.verify.title' | transloco }}</h1>
    <div class="mt-4" aria-live="polite">
      @switch (state()) {
        @case ('pending') {
          <p class="text-sm text-ink-muted">{{ 'auth.verify.pending' | transloco }}</p>
        }
        @case ('success') {
          <p role="status" class="eti-alert-success">{{ 'auth.verify.success' | transloco }}</p>
        }
        @case ('failed') {
          <p role="alert" class="eti-alert-error">{{ 'auth.verify.failed' | transloco }}</p>
        }
        @case ('missing') {
          <p role="alert" class="eti-alert-error">{{ 'auth.verify.missing' | transloco }}</p>
        }
      }
    </div>
    @if (state() !== 'pending') {
      <a routerLink="/auth/login" class="eti-btn-primary mt-6 w-full">{{ 'auth.verify.login' | transloco }}</a>
    }
  `,
})
export class VerifyEmailPage implements OnInit {
  private readonly auth = inject(AuthService);
  readonly token = input<string>();
  protected readonly state = signal<'pending' | 'success' | 'failed' | 'missing'>('pending');

  ngOnInit(): void {
    const token = this.token();
    if (!token) {
      this.state.set('missing');
      return;
    }
    this.auth.verifyEmail(token).subscribe({
      next: () => this.state.set('success'),
      error: () => this.state.set('failed'),
    });
  }
}
