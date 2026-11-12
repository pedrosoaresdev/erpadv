import { Component, EventEmitter, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { MfaSetup } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { AUTH_STYLES } from '../auth/auth-pages';

/** Baixa ou copia os códigos de recuperação. */
export function downloadRecoveryCodes(codes: string[], email: string): void {
  const text = `ERPAdv — códigos de recuperação da autenticação em dois fatores\nConta: ${email}\nGerados em: ${new Date().toLocaleString('pt-BR')}\n\n`
    + codes.join('\n') + '\n\nCada código vale uma única vez. Guarde em local seguro (fora do celular do autenticador).\n';
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = 'erpadv-codigos-de-recuperacao.txt';
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/**
 * Configuração do aplicativo autenticador: QR code (SVG gerado pelo servidor, exibido como imagem), chave manual,
 * confirmação do primeiro código e entrega dos códigos de recuperação.
 */
@Component({
  selector: 'app-mfa-setup',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatProgressBarModule],
  template: `
    @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
    @if (codes(); as list) {
      <div class="done">
        <p class="ok"><mat-icon>verified_user</mat-icon>Autenticação em dois fatores ativada.</p>
        <p>Guarde estes <strong>códigos de recuperação</strong>. Cada um permite entrar uma vez se você perder o celular. Eles não serão exibidos de novo.</p>
        <div class="codes">@for (c of list; track c) { <code>{{ c }}</code> }</div>
        <div class="row">
          <button mat-stroked-button type="button" (click)="download(list)"><mat-icon>download</mat-icon>Baixar .txt</button>
          <button mat-stroked-button type="button" (click)="copy(list)"><mat-icon>content_copy</mat-icon>Copiar</button>
          <span class="spacer"></span>
          <button mat-flat-button type="button" (click)="finished.emit()">Concluir</button>
        </div>
      </div>
    } @else if (setup(); as s) {
      <ol class="steps">
        <li>Instale um aplicativo autenticador no celular (Google Authenticator, Microsoft Authenticator, Authy ou 1Password).</li>
        <li>Leia o QR code com o aplicativo. Se preferir, digite a chave manualmente.</li>
        <li>Digite o código de 6 dígitos exibido no aplicativo.</li>
      </ol>
      <div class="qr-row">
        <img class="qr" [src]="qrSrc()" alt="QR code para o aplicativo autenticador" />
        <div class="manual">
          <span class="muted small">Chave para digitação manual</span>
          <code class="key">{{ s.secret }}</code>
          <span class="muted small">Tipo: baseado em tempo · 6 dígitos · 30 segundos</span>
        </div>
      </div>
      <form (ngSubmit)="confirm()" class="code-form">
        <mat-form-field appearance="outline">
          <mat-label>Código do aplicativo</mat-label>
          <input matInput name="code" [(ngModel)]="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required />
        </mat-form-field>
        <button mat-flat-button type="submit" [disabled]="busy() || code.replace(' ', '').length !== 6">Ativar</button>
      </form>
    } @else {
      <p>Proteja a conta com um segundo fator: além da senha, será pedido um código do aplicativo autenticador do seu celular.</p>
      <button mat-flat-button type="button" (click)="start()" [disabled]="busy()"><mat-icon>qr_code_2</mat-icon>Configurar aplicativo autenticador</button>
    }
  `,
  styles: `
    .steps { padding-left: 18px; margin: 0 0 12px; line-height: 1.6; font-size: 14px; }
    .qr-row { display: flex; gap: 20px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
    .qr { width: 188px; height: 188px; border: 1px solid var(--app-border); border-radius: 8px; background: #fff; image-rendering: pixelated; }
    .manual { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
    .key { font-size: 15px; letter-spacing: 1px; word-break: break-all; background: var(--app-surface-2, rgba(0,0,0,.04)); padding: 6px 8px; border-radius: 6px; }
    .code-form { display: flex; gap: 12px; align-items: baseline; flex-wrap: wrap; }
    .code-form mat-form-field { width: 200px; }
    .codes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 12px 0; }
    .codes code { font-size: 15px; letter-spacing: 1px; padding: 6px 10px; border: 1px dashed var(--app-border); border-radius: 6px; text-align: center; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .ok { display: flex; gap: 8px; align-items: center; color: var(--app-success, #2e7d32); font-weight: 600; }
    .small { font-size: 12px; }
  `,
})
export class MfaSetupComponent {
  @Output() readonly finished = new EventEmitter<void>();
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  readonly setup = signal<MfaSetup | null>(null);
  readonly codes = signal<string[] | null>(null);
  readonly busy = signal(false);
  readonly qrSrc = computed(() => {
    const s = this.setup();
    return s ? 'data:image/svg+xml;base64,' + btoa(s.qrSvg) : '';
  });
  code = '';

  start(): void {
    this.busy.set(true);
    this.api.post<MfaSetup>('/security/mfa/setup', {}).subscribe({
      next: (s) => { this.setup.set(s); this.busy.set(false); },
      error: () => this.busy.set(false),
    });
  }

  confirm(): void {
    this.busy.set(true);
    this.api.post<{ recoveryCodes: string[] }>('/security/mfa/confirm', { code: this.code }).subscribe({
      next: async (r) => {
        await this.auth.loadSecurity();
        this.codes.set(r.recoveryCodes);
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  download(codes: string[]): void {
    downloadRecoveryCodes(codes, this.auth.me()?.email ?? '');
  }

  copy(codes: string[]): void {
    void navigator.clipboard.writeText(codes.join('\n')).then(() => this.toast.success('Códigos copiados.'));
  }
}

/** Etapa após o login: confirmar o código do autenticador ou configurá-lo quando o escritório exigir. */
@Component({
  selector: 'app-mfa-verify',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatProgressBarModule, MfaSetupComponent],
  template: `
    <div class="card auth-card">
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      @if (enroll()) {
        <h2>Configure o segundo fator</h2>
        <p class="sub">O escritório exige a autenticação em dois fatores para o seu perfil.</p>
        <app-mfa-setup (finished)="continue()" />
      } @else {
        <h2>Verificação em duas etapas</h2>
        <p class="sub">{{ recovery() ? 'Digite um dos seus códigos de recuperação (ex.: ABCDE-FGH23).' : 'Abra o aplicativo autenticador e digite o código de 6 dígitos.' }}</p>
        @if (error()) { <div class="error">{{ error() }}</div> }
        <form (ngSubmit)="verify()">
          <mat-form-field appearance="outline">
            <mat-label>{{ recovery() ? 'Código de recuperação' : 'Código' }}</mat-label>
            <input matInput name="code" [(ngModel)]="code" [attr.inputmode]="recovery() ? null : 'numeric'" autocomplete="one-time-code"
              [maxlength]="recovery() ? 11 : 7" required autofocus />
          </mat-form-field>
          <button mat-flat-button class="submit" type="submit" [disabled]="busy() || !code.trim()">Confirmar</button>
        </form>
      }
      <div class="links">
        @if (!enroll()) {
          <a href="" (click)="$event.preventDefault(); recovery.set(!recovery()); code = ''">{{ recovery() ? 'Usar o aplicativo' : 'Perdi o celular — usar código de recuperação' }}</a>
        }
        <a href="" (click)="$event.preventDefault(); auth.logout()">Sair</a>
      </div>
      @if (!enroll() && recovery()) {
        <p class="muted small">Sem códigos de recuperação? Peça ao administrador do escritório para redefinir o seu segundo fator.</p>
      }
    </div>
  `,
  styles: AUTH_STYLES + `.small { font-size: 12px; margin-top: 12px; }`,
})
export class MfaVerifyComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly recovery = signal(false);
  readonly enroll = computed(() => !!this.auth.security()?.enrollmentRequired);
  code = '';

  constructor() {
    void this.auth.loadSecurity().then((s) => {
      if (s && !s.enrollmentRequired && (!s.mfaRequired || s.mfaVerified)) {
        void this.router.navigateByUrl(this.auth.homePath());
      }
    });
  }

  async verify(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.post<void>('/security/mfa/verify', { code: this.code.trim() }, true));
      await this.continue();
    } catch (e) {
      this.error.set((e as { detail?: string }).detail ?? 'Não foi possível confirmar o código.');
      this.code = '';
    } finally {
      this.busy.set(false);
    }
  }

  async continue(): Promise<void> {
    await this.auth.loadSecurity();
    await this.router.navigateByUrl(this.auth.homePath());
  }
}
