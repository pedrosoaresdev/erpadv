import { Component, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import { saveBlob } from '../finance/finance.service';

export type ProposalStatus = 'RASCUNHO' | 'ENVIADA' | 'ACEITA' | 'RECUSADA';

export interface Proposal {
  id: string;
  leadId: string;
  number: string;
  title: string;
  scope: string | null;
  feeTerms: string | null;
  valueCents: number;
  validUntil: string | null;
  status: ProposalStatus;
  sentAt: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  createdByName: string;
  createdAt: string;
}

const STATUS_LABELS: Record<ProposalStatus, string> = { RASCUNHO: 'Rascunho', ENVIADA: 'Enviada', ACEITA: 'Aceita', RECUSADA: 'Recusada' };

/** Propostas de honorários da oportunidade (rascunho → enviada → aceita/recusada), com PDF. */
@Component({
  selector: 'app-lead-proposals',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, CentsPipe, BrDatePipe],
  template: `
    <div class="head">
      <div class="form-section-title">Propostas</div>
      <button mat-stroked-button type="button" (click)="edit(null)"><mat-icon>request_quote</mat-icon>Nova proposta</button>
    </div>
    @if (!items().length) { <p class="muted small">Nenhuma proposta. Envie uma proposta de honorários para avançar a negociação.</p> }
    @for (p of items(); track p.id) {
      <div class="prop">
        <div class="main">
          <strong>{{ p.title }}</strong> <span class="badge" [class]="p.status">{{ labels[p.status] }}</span>
          <div class="muted small">{{ p.number }} · {{ p.valueCents | cents }}{{ p.validUntil ? ' · válida até ' + (p.validUntil | brDate) : '' }}{{ p.decisionNote ? ' · ' + p.decisionNote : '' }}</div>
        </div>
        <button mat-icon-button type="button" (click)="pdf(p)" aria-label="Baixar PDF"><mat-icon>picture_as_pdf</mat-icon></button>
        <button mat-icon-button type="button" [matMenuTriggerFor]="pm" aria-label="Ações da proposta"><mat-icon>more_vert</mat-icon></button>
        <mat-menu #pm="matMenu" xPosition="before">
          @if (p.status === 'RASCUNHO') {
            <button mat-menu-item (click)="edit(p)"><mat-icon>edit</mat-icon>Editar</button>
            <button mat-menu-item (click)="send(p)"><mat-icon>send</mat-icon>Marcar como enviada</button>
            <button mat-menu-item (click)="remove(p)"><mat-icon>delete</mat-icon>Excluir rascunho</button>
          }
          @if (p.status === 'ENVIADA') {
            <button mat-menu-item (click)="decide(p, 'ACEITA')"><mat-icon>thumb_up</mat-icon>Cliente aceitou</button>
            <button mat-menu-item (click)="decide(p, 'RECUSADA')"><mat-icon>thumb_down</mat-icon>Cliente recusou</button>
          }
          <button mat-menu-item (click)="pdf(p)"><mat-icon>picture_as_pdf</mat-icon>Baixar PDF</button>
        </mat-menu>
      </div>
    }
  `,
  styles: `
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 12px; }
    .prop { display: flex; align-items: center; gap: 4px; padding: 8px 0; border-bottom: 1px solid var(--app-border); }
    .main { flex: 1; min-width: 0; }
    .small { font-size: 12px; }
    .badge.ENVIADA { background: var(--app-info-bg); color: var(--app-info); }
    .badge.ACEITA { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .badge.RECUSADA { background: var(--app-danger-bg); color: var(--app-danger); }
  `,
})
export class LeadProposalsComponent {
  readonly leadId = input.required<string>();
  /** A oportunidade pode ter mudado de etapa (enviar/aceitar move o funil). */
  readonly changed = output<void>();
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly labels = STATUS_LABELS;
  readonly items = signal<Proposal[]>([]);

  constructor() {
    queueMicrotask(() => this.load());
  }

  load(): void {
    this.api.get<Proposal[]>(`/crm/leads/${encodeURIComponent(this.leadId())}/proposals`).subscribe((p) => this.items.set(p));
  }

  edit(p: Proposal | null): void {
    this.dialog.open(ProposalDialogComponent, { data: { leadId: this.leadId(), proposal: p }, width: '680px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Proposta salva.'); this.load(); } });
  }

  send(p: Proposal): void {
    this.api.post(`/crm/proposals/${encodeURIComponent(p.id)}/send`).subscribe(() => {
      this.toast.success('Proposta marcada como enviada.');
      this.load();
      this.changed.emit();
    });
  }

  decide(p: Proposal, status: 'ACEITA' | 'RECUSADA'): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: status === 'ACEITA' ? 'Proposta aceita' : 'Proposta recusada', message: p.title, confirmLabel: 'Registrar',
        reasonLabel: 'Observação (opcional)', reasonRequired: false },
      width: '440px',
    }).afterClosed().subscribe((r) => {
      if (r === false || r === undefined || r === null) return;
      this.api.post(`/crm/proposals/${encodeURIComponent(p.id)}/decision`, { status, note: typeof r === 'string' ? r : null }).subscribe(() => {
        this.toast.success(status === 'ACEITA' ? 'Aceite registrado — oportunidade em “Contrato enviado”.' : 'Recusa registrada.');
        this.load();
        this.changed.emit();
      });
    });
  }

  remove(p: Proposal): void {
    (this.api.delete(`/crm/proposals/${encodeURIComponent(p.id)}`) as Observable<void>).subscribe(() => this.load());
  }

  pdf(p: Proposal): void {
    this.api.getBlob(`/crm/proposals/${encodeURIComponent(p.id)}/pdf`).subscribe((r) => saveBlob(r, `${p.number}.pdf`));
  }
}

@Component({
  selector: 'app-proposal-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>request_quote</mat-icon>{{ data.proposal ? 'Editar proposta' : 'Nova proposta' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="propForm" (ngSubmit)="save()">
        <mat-form-field><mat-label>Título</mat-label><input matInput formControlName="title" maxlength="200" placeholder="Ex.: Ação revisional de contrato bancário" /></mat-form-field>
        <mat-form-field><mat-label>Escopo dos serviços</mat-label><textarea matInput rows="5" formControlName="scope" maxlength="8000"></textarea></mat-form-field>
        <mat-form-field class="c4"><mat-label>Valor (R$)</mat-label><input matInput formControlName="value" inputmode="decimal" /></mat-form-field>
        <mat-form-field class="c4"><mat-label>Válida até</mat-label><input matInput type="date" formControlName="validUntil" /></mat-form-field>
        <mat-form-field><mat-label>Forma de cobrança</mat-label><textarea matInput rows="2" formControlName="feeTerms" maxlength="1000" placeholder="Ex.: entrada de 30% + 5 parcelas mensais; 20% de êxito"></textarea></mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="propForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
})
export class ProposalDialogComponent {
  readonly data = inject<{ leadId: string; proposal: Proposal | null }>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ProposalDialogComponent>);
  private readonly api = inject(ApiService);
  readonly saving = signal(false);
  private readonly p = this.data.proposal;
  readonly form = inject(FormBuilder).nonNullable.group({
    title: [this.p?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    scope: [this.p?.scope ?? ''],
    value: [centsToInput(this.p?.valueCents ?? 0), Validators.required],
    validUntil: [this.p?.validUntil ?? ''],
    feeTerms: [this.p?.feeTerms ?? ''],
  });

  save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    const body = { title: v.title.trim(), scope: v.scope.trim() || null, feeTerms: v.feeTerms.trim() || null,
      valueCents: parseMoneyToCents(v.value) ?? 0, validUntil: v.validUntil || null };
    this.saving.set(true);
    const req = this.p ? this.api.put(`/crm/proposals/${encodeURIComponent(this.p.id)}`, body)
      : this.api.post(`/crm/leads/${encodeURIComponent(this.data.leadId)}/proposals`, body);
    req.subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
