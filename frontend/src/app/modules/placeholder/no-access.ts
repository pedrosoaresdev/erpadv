import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/** Usuário autenticado sem acesso à área interna (ex.: cliente antes do portal — Etapa 14). */
@Component({
  selector: 'app-no-access',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <div class="wrap">
      <div class="card box">
        <mat-icon class="ic">lock_person</mat-icon>
        <h2>Acesso não disponível</h2>
        @if (auth.me()?.role === 'CLIENTE') {
          <p class="muted">O Portal do Cliente será liberado em breve. Enquanto isso, fale com o escritório responsável.</p>
        } @else {
          <p class="muted">Seu usuário não possui acesso a esta área. Procure o administrador do escritório.</p>
        }
        <div class="row">
          <a mat-stroked-button routerLink="/login">Voltar</a>
          <button mat-flat-button (click)="auth.logout()">Sair</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .wrap { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
    .box { max-width: 460px; padding: 36px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .ic { font-size: 44px; width: 44px; height: 44px; color: var(--app-gold); }
    .row { margin-top: 12px; }
  `,
})
export class NoAccessComponent {
  readonly auth = inject(AuthService);
}
