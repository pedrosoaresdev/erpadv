import { Component, inject, signal } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';

interface PublicPolicy { officeName: string; title: string; version: number; publishedAt: string; content: string }

/** Página pública (sem login) com a política de privacidade vigente do escritório. */
@Component({
  selector: 'app-privacy-public',
  imports: [BrDatePipe],
  template: `
    <div class="card auth-card wide">
      @if (policy(); as p) {
        <p class="eyebrow">{{ p.officeName }}</p>
        <h2>{{ p.title }}</h2>
        <p class="muted small">Versão {{ p.version }} · vigente desde {{ p.publishedAt | brDate }}</p>
        <div class="doc" [innerHTML]="html()"></div>
      } @else if (missing()) {
        <h2>Política não encontrada</h2>
        <p class="muted">Este escritório ainda não publicou a política de privacidade.</p>
      } @else {
        <p class="muted">Carregando…</p>
      }
    </div>
  `,
  styles: `
    .wide { max-width: 860px; width: 100%; padding: 32px; }
    .eyebrow { text-transform: uppercase; letter-spacing: .08em; font-size: 12px; color: var(--app-accent, #b8955a); margin: 0; }
    h2 { margin: 4px 0; }
    .small { font-size: 12px; }
    .doc { line-height: 1.65; margin-top: 16px; }
  `,
})
export class PrivacyPublicComponent {
  private readonly sanitizer = inject(DomSanitizer);
  readonly policy = signal<PublicPolicy | null>(null);
  readonly missing = signal(false);
  readonly html = signal<SafeHtml>('');

  constructor() {
    const id = inject(ActivatedRoute).snapshot.paramMap.get('officeId') ?? '';
    inject(ApiService).get<PublicPolicy>(`/public/privacy/${encodeURIComponent(id)}`, undefined, true).subscribe({
      next: (p) => {
        this.policy.set(p);
        // conteúdo saneado no servidor (jsoup, lista de permissões) antes de ser gravado
        this.html.set(this.sanitizer.bypassSecurityTrustHtml(p.content));
      },
      error: () => this.missing.set(true),
    });
  }
}
