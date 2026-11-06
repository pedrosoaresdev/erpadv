import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-auth-layout',
  imports: [RouterOutlet, MatIconModule],
  template: `
    <div class="auth">
      <section class="hero" aria-hidden="true">
        <div class="hero-inner">
          <div class="logo">
            <svg viewBox="0 0 64 64"><path d="M32 12v40M18 22h28M18 22l-7 14a7 7 0 0 0 14 0zM46 22l-7 14a7 7 0 0 0 14 0zM24 52h16" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </div>
          <h1 class="brand-serif">ERPAdv</h1>
          <p class="tag">Gestão jurídica de alto padrão para escritórios que tratam sigilo como prioridade.</p>
          <ul>
            <li><mat-icon>verified_user</mat-icon>Trilha de auditoria imutável e controle de acesso granular</li>
            <li><mat-icon>lock</mat-icon>Dados pessoais criptografados, em conformidade com a LGPD</li>
            <li><mat-icon>gavel</mat-icon>Processos, clientes, prazos e financeiro em um só lugar</li>
          </ul>
        </div>
        <div class="glow"></div>
      </section>
      <section class="panel">
        <div class="panel-inner"><router-outlet /></div>
      </section>
    </div>
  `,
  styles: `
    .auth { min-height: 100vh; display: grid; grid-template-columns: minmax(380px, 1.05fr) 1fr; }
    .hero { position: relative; overflow: hidden; color: #dfe6f1; display: flex; align-items: center; justify-content: center; padding: 48px;
      background: radial-gradient(1200px 600px at -10% -10%, #1b3c6e 0%, transparent 60%), linear-gradient(160deg, #0b1f3a 0%, #0c1626 60%, #111418 100%); }
    .hero-inner { position: relative; z-index: 1; max-width: 460px; }
    .logo { width: 64px; height: 64px; border-radius: 18px; display: grid; place-items: center; color: var(--app-gold);
      background: rgb(197 164 109 / 12%); border: 1px solid rgb(197 164 109 / 40%); }
    .logo svg { width: 40px; height: 40px; }
    h1 { color: #fff; font-size: 44px; margin-top: 24px; }
    .tag { font-size: 17px; line-height: 1.55; color: #b9c5d8; margin: 12px 0 32px; }
    ul { list-style: none; padding: 0; margin: 0; display: grid; gap: 16px; }
    li { display: flex; gap: 12px; align-items: center; color: #cfd8e6; }
    li mat-icon { color: var(--app-gold); }
    .glow { position: absolute; right: -160px; bottom: -160px; width: 420px; height: 420px; border-radius: 50%;
      background: radial-gradient(circle, rgb(197 164 109 / 22%), transparent 70%); }
    .panel { display: flex; align-items: center; justify-content: center; padding: 32px 20px; background: var(--app-bg); }
    .panel-inner { width: 100%; max-width: 440px; }
    @media (max-width: 900px) { .auth { grid-template-columns: 1fr; } .hero { display: none; } }
  `,
})
export class AuthLayoutComponent {}
