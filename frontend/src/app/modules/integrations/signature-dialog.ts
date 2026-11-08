import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ToastService } from '../../core/services/toast.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { IntegrationsService, SignatureRequest, Signer } from './integrations.service';

const STATUS: Record<SignatureRequest['status'], string> = { ENVIADO: 'Aguardando assinaturas', ASSINADO: 'Assinado por todos', RECUSADO: 'Recusado', CANCELADO: 'Cancelado' };

/** Envio de um PDF da central para assinatura eletrônica (ZapSign) e acompanhamento dos signatários. */
@Component({
  selector: 'app-signature-dialog',
  imports: [FormsModule, RouterLink, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatProgressBarModule, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>draw</mat-icon>Assinatura eletrônica · {{ data.title }}</h2>
    <mat-dialog-content>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      @for (r of requests(); track r.id) {
        <div class="req">
          <div class="row"><strong>{{ status[r.status] }}</strong><span class="muted small">enviado por {{ r.createdByName }} em {{ r.createdAt | brDate: true }}{{ r.sandbox ? ' · sandbox' : '' }}</span>
            <span class="spacer"></span><button mat-button (click)="refresh(r)"><mat-icon>sync</mat-icon>Atualizar</button></div>
          <ul>
            @for (s of r.signers; track $index) {
              <li><strong>{{ s.name }}</strong> · {{ s.email }} · {{ s.status === 'ASSINOU' ? 'assinou' + (s.signedAt ? ' em ' + (s.signedAt | brDate: true) : '') : s.status === 'RECUSOU' ? 'recusou' : 'pendente' }}
                @if (s.signUrl && s.status !== 'ASSINOU') { · <a [href]="s.signUrl" target="_blank" rel="noopener noreferrer">link de assinatura</a> }</li>
            }
          </ul>
          @if (r.signedStored) { <p class="ok small"><mat-icon class="mi">verified</mat-icon>Via assinada guardada como versão {{ r.signedVersion }} do documento.</p> }
        </div>
      }
      <h4>{{ requests().length ? 'Novo envio' : 'Signatários' }}</h4>
      @for (s of signers; track $index; let i = $index) {
        <div class="signer">
          <mat-form-field><mat-label>Nome</mat-label><input matInput [(ngModel)]="s.name" /></mat-form-field>
          <mat-form-field><mat-label>E-mail</mat-label><input matInput type="email" [(ngModel)]="s.email" /></mat-form-field>
          <mat-form-field><mat-label>Celular (opcional)</mat-label><input matInput [(ngModel)]="s.phone" /></mat-form-field>
          <button mat-icon-button (click)="signers.splice(i, 1)" [disabled]="signers.length === 1" aria-label="Remover signatário"><mat-icon>close</mat-icon></button>
        </div>
      }
      <button mat-button (click)="signers.push({ name: '', email: '', phone: '' })"><mat-icon>person_add</mat-icon>Adicionar signatário</button>
      <p class="muted small">O provedor envia o link por e-mail a cada signatário. Quando todos assinarem, a via assinada vira uma nova versão deste
        documento. Configuração em <a routerLink="/integracoes" mat-dialog-close>Integrações</a>.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Fechar</button>
      <button mat-flat-button (click)="send()" [disabled]="busy()"><mat-icon>send</mat-icon>Enviar para assinatura</button>
    </mat-dialog-actions>
  `,
  styles: `
    .req { border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; margin-bottom: 10px; }
    .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .req ul { margin: 4px 0; padding-left: 18px; font-size: 13px; }
    .signer { display: grid; grid-template-columns: 1fr 1fr 160px 40px; gap: 8px; align-items: center; }
    .small { font-size: 12px; }
    .mi { font-size: 16px; width: 16px; height: 16px; vertical-align: -3px; }
    .ok { color: var(--app-success, #2e7d32); }
    h4 { margin: 12px 0 6px; }
    @media (max-width: 700px) { .signer { grid-template-columns: 1fr; } }
  `,
})
export class SignatureDialogComponent {
  readonly data = inject<{ documentId: string; title: string; signer?: Signer | null }>(MAT_DIALOG_DATA);
  private readonly service = inject(IntegrationsService);
  private readonly toast = inject(ToastService);
  readonly status = STATUS;
  readonly requests = signal<SignatureRequest[]>([]);
  readonly busy = signal(false);
  signers: Signer[] = [this.data.signer ? { ...this.data.signer } : { name: '', email: '', phone: '' }];

  constructor() {
    this.service.signatures(this.data.documentId).subscribe((r) => this.requests.set(r));
  }

  send(): void {
    this.busy.set(true);
    this.service.sendSignature(this.data.documentId, this.signers.map((s) => ({ name: s.name.trim(), email: s.email.trim(), phone: s.phone || null }))).subscribe({
      next: (r) => { this.busy.set(false); this.requests.update((l) => [r, ...l]); this.toast.success('Documento enviado para assinatura.'); },
      error: () => this.busy.set(false),
    });
  }

  refresh(r: SignatureRequest): void {
    this.busy.set(true);
    this.service.refreshSignature(r.id).subscribe({
      next: (x) => { this.busy.set(false); this.requests.update((l) => l.map((i) => (i.id === x.id ? x : i))); },
      error: () => this.busy.set(false),
    });
  }
}
