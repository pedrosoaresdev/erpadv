import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { CursorPage, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ClientPickerComponent, PickedClient } from '../../shared/components/client-picker';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';

type Kind = 'PAGAMENTO' | 'REEMBOLSO' | 'QUITACAO' | 'ENTREGA_DOCUMENTOS' | 'DEVOLUCAO_DOCUMENTOS';

interface Receipt {
  id: string; number: string; kind: Kind; clientId: string; clientName: string; payerName: string; amountCents: number | null;
  description: string; date: string; paymentMethod: string | null; caseNumber: string | null; issuerName: string; documentId: string | null;
  canceled: boolean; cancelReason: string | null; canceledByName: string | null;
}

interface PendingRevenue {
  transactionId: string; description: string; clientId: string; clientName: string; amountCents: number; paidDate: string;
  paymentMethod: string | null; caseNumber: string | null;
}

const KINDS: { value: Kind; label: string; monetary: boolean; hint: string }[] = [
  { value: 'PAGAMENTO', label: 'Recibo de pagamento', monetary: true, hint: 'Honorários, parcelas e outros valores recebidos do cliente.' },
  { value: 'REEMBOLSO', label: 'Recibo de reembolso de despesas', monetary: true, hint: 'Custas, diligências, cópias e despesas repassadas.' },
  { value: 'QUITACAO', label: 'Declaração de quitação', monetary: true, hint: 'Declara que o cliente quitou integralmente um valor.' },
  { value: 'ENTREGA_DOCUMENTOS', label: 'Recibo de entrega de documentos', monetary: false, hint: 'Documentos que o cliente entregou ao escritório.' },
  { value: 'DEVOLUCAO_DOCUMENTOS', label: 'Recibo de devolução de documentos', monetary: false, hint: 'Documentos devolvidos ao cliente (o cliente assina).' },
];

const brl = (cents: number | null | undefined) =>
  cents == null ? '—' : (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

// ======================================================================= novo recibo
@Component({
  selector: 'app-receipt-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatProgressBarModule, ClientPickerComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>receipt_long</mat-icon>Novo documento para o cliente</h2>
    <mat-dialog-content>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="grid">
        <mat-form-field class="c2"><mat-label>Tipo</mat-label>
          <mat-select [(ngModel)]="m.kind">
            @for (k of kinds(); track k.value) { <mat-option [value]="k.value">{{ k.label }}</mat-option> }
          </mat-select>
          <mat-hint>{{ hint() }}</mat-hint>
        </mat-form-field>
        <div class="c2"><app-client-picker label="Cliente" (picked)="pickClient($event)" /></div>
        <mat-form-field><mat-label>{{ m.kind === 'DEVOLUCAO_DOCUMENTOS' ? 'Quem recebe os documentos' : 'Nome de quem pagou/entregou' }}</mat-label>
          <input matInput [(ngModel)]="m.payerName" maxlength="200" placeholder="Em branco = o próprio cliente" /></mat-form-field>
        <mat-form-field><mat-label>Processo (opcional)</mat-label>
          <mat-select [(ngModel)]="m.caseId" [disabled]="!cases().length">
            <mat-option [value]="''">Nenhum</mat-option>
            @for (c of cases(); track c.id) { <mat-option [value]="c.id">{{ c.cnjNumber || 'Sem número' }} · {{ c.subject || c.area }}</mat-option> }
          </mat-select></mat-form-field>
        @if (monetary()) {
          <mat-form-field><mat-label>Valor (R$)</mat-label><input matInput [(ngModel)]="amount" inputmode="decimal" placeholder="1.500,00" /></mat-form-field>
          <mat-form-field><mat-label>Forma de pagamento</mat-label>
            <mat-select [(ngModel)]="m.paymentMethod">@for (p of methods; track p) { <mat-option [value]="p">{{ p }}</mat-option> }</mat-select></mat-form-field>
        }
        <mat-form-field class="c2"><mat-label>{{ monetary() ? 'Referente a' : 'Documentos (um por linha)' }}</mat-label>
          <textarea matInput rows="4" [(ngModel)]="m.description" maxlength="2000"
            [placeholder]="monetary() ? 'honorários advocatícios da ação de indenização' : 'RG original\nComprovante de residência'"></textarea></mat-form-field>
        <mat-form-field><mat-label>Data</mat-label><input matInput type="date" [(ngModel)]="m.date" [max]="today" /></mat-form-field>
        <mat-form-field><mat-label>Cidade</mat-label><input matInput [(ngModel)]="m.city" maxlength="80" placeholder="Em branco = cidade do escritório" /></mat-form-field>
        @if (auth.has('DOCUMENTS_WRITE')) {
          <mat-checkbox class="c2" [(ngModel)]="m.saveToDocuments">Guardar o PDF na central documental do cliente</mat-checkbox>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy() || !client || !m.description.trim() || (monetary() && !cents())">
        <mat-icon>picture_as_pdf</mat-icon>Emitir</button>
    </mat-dialog-actions>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
    .c2 { grid-column: span 2; }
    mat-form-field { width: 100%; }
    @media (max-width: 640px) { .grid { grid-template-columns: minmax(0, 1fr); } .c2 { grid-column: span 1; } }
  `,
})
export class ReceiptDialogComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<ReceiptDialogComponent>);
  readonly data = inject<{ kind: Kind | null }>(MAT_DIALOG_DATA);
  readonly busy = signal(false);
  readonly cases = signal<LegalCase[]>([]);
  readonly today = new Date().toISOString().slice(0, 10);
  readonly methods = ['Pix', 'Transferência bancária', 'Boleto', 'Cartão', 'Dinheiro', 'Cheque'];
  /** Só aparecem os tipos que o perfil pode emitir (o servidor também confere). */
  readonly kinds = computed(() => KINDS.filter((k) => (k.monetary ? this.auth.has('FINANCE_WRITE') : this.auth.has('DOCUMENTS_WRITE'))));
  client: PickedClient | null = null;
  amount = '';
  m = { kind: (this.data.kind ?? this.kinds()[0]?.value ?? 'PAGAMENTO') as Kind, payerName: '', caseId: '', paymentMethod: 'Pix', description: '',
    date: this.today, city: '', saveToDocuments: true };

  monetary(): boolean {
    return KINDS.find((k) => k.value === this.m.kind)?.monetary ?? true;
  }

  hint(): string {
    return KINDS.find((k) => k.value === this.m.kind)?.hint ?? '';
  }

  cents(): number | null {
    const v = Number(this.amount.replace(/\./g, '').replace(',', '.').replace(/[^\d.]/g, ''));
    return Number.isFinite(v) && v > 0 ? Math.round(v * 100) : null;
  }

  pickClient(c: PickedClient | null): void {
    this.client = c;
    this.m.caseId = '';
    this.cases.set([]);
    if (c && this.auth.has('CASES_READ')) {
      this.api.get<CursorPage<LegalCase>>('/cases', { clientId: c.id, size: 50 }, true).subscribe((p) => this.cases.set(p.items));
    }
  }

  save(): void {
    this.busy.set(true);
    this.api.post<Receipt>('/receipts', { kind: this.m.kind, clientId: this.client!.id, payerName: this.m.payerName || null,
      amountCents: this.monetary() ? this.cents() : null, description: this.m.description, date: this.m.date,
      paymentMethod: this.monetary() ? this.m.paymentMethod : null, caseId: this.m.caseId || null, city: this.m.city || null,
      saveToDocuments: this.auth.has('DOCUMENTS_WRITE') && this.m.saveToDocuments }).subscribe({
      next: (r) => this.ref.close(r),
      error: () => this.busy.set(false),
    });
  }
}

// ======================================================================= página
@Component({
  selector: 'app-receipts-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatTabsModule, MatTooltipModule, MatProgressBarModule, MatFormFieldModule,
    MatInputModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Clientes" title="Recibos"
        subtitle="Recibos de pagamento e de reembolso, declarações de quitação e recibos de entrega ou devolução de documentos, em PDF no papel timbrado.">
        @if (canIssue()) { <button mat-flat-button (click)="newReceipt()"><mat-icon>add</mat-icon>Novo recibo</button> }
      </app-page-header>

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false">
        <mat-tab label="Emitidos">
          <section class="card">
            <div class="bar">
              <mat-form-field appearance="outline" subscriptSizing="dynamic" class="search">
                <mat-label>Buscar por número, cliente ou descrição</mat-label>
                <input matInput [(ngModel)]="search" />
                <mat-icon matSuffix>search</mat-icon>
              </mat-form-field>
              <span class="spacer"></span>
              <span class="muted small">{{ filtered().length }} documento(s)</span>
            </div>
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (!loading() && filtered().length === 0) {
              <app-empty-state icon="receipt_long" title="Nenhum recibo emitido" text="Use “Novo recibo” para gerar o primeiro documento para um cliente." />
            } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Número</th><th>Tipo</th><th>Cliente</th><th>Valor</th><th>Data</th><th>Situação</th><th></th></tr></thead>
                <tbody>
                  @for (r of filtered(); track r.id) {
                    <tr [class.canceled]="r.canceled">
                      <td data-label="Número" class="mono">{{ r.number }}</td>
                      <td data-label="Tipo">{{ kindLabel(r.kind) }}</td>
                      <td data-label="Cliente"><a [routerLink]="['/clientes', r.clientId]">{{ r.clientName }}</a>
                        <div class="muted small ellipsis" [matTooltip]="r.description">{{ r.description }}</div></td>
                      <td data-label="Valor">{{ money(r.amountCents) }}</td>
                      <td data-label="Data">{{ r.date | brDate }}</td>
                      <td data-label="Situação">
                        @if (r.canceled) { <span class="badge danger" [matTooltip]="r.cancelReason || ''">cancelado</span> }
                        @else { <span class="badge success">válido</span> }
                      </td>
                      <td class="actions">
                        <button mat-icon-button (click)="pdf(r)" matTooltip="Baixar PDF" aria-label="Baixar PDF"><mat-icon>picture_as_pdf</mat-icon></button>
                        @if (r.documentId) {
                          <a mat-icon-button [routerLink]="['/documentos']" [queryParams]="{ doc: r.documentId }" matTooltip="Abrir na central documental"
                            aria-label="Abrir na central documental"><mat-icon>folder_open</mat-icon></a>
                        }
                        @if (!r.canceled && canCancel(r)) {
                          <button mat-icon-button (click)="cancel(r)" matTooltip="Cancelar" aria-label="Cancelar recibo"><mat-icon>block</mat-icon></button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        @if (auth.has('FINANCE_READ')) {
          <mat-tab [label]="'Recebimentos sem recibo' + (pending().length ? ' (' + pending().length + ')' : '')">
            <section class="card">
              <div class="bar"><span class="muted small">Receitas do escritório recebidas nos últimos 180 dias, vinculadas a cliente e ainda sem recibo.</span></div>
              @if (pending().length === 0) { <app-empty-state icon="task_alt" title="Todos os recebimentos já têm recibo" /> } @else {
                <div class="table-wrap"><table class="data stack">
                  <thead><tr><th>Recebido em</th><th>Cliente</th><th>Descrição</th><th>Valor</th><th></th></tr></thead>
                  <tbody>
                    @for (p of pending(); track p.transactionId) {
                      <tr>
                        <td data-label="Recebido em">{{ p.paidDate | brDate }}</td>
                        <td data-label="Cliente">{{ p.clientName }}</td>
                        <td data-label="Descrição">{{ p.description }}@if (p.caseNumber) { <div class="muted small">{{ p.caseNumber }}</div> }</td>
                        <td data-label="Valor">{{ money(p.amountCents) }}</td>
                        <td class="actions">@if (auth.has('FINANCE_WRITE')) {
                          <button mat-stroked-button (click)="fromTransaction(p)" [disabled]="busy()"><mat-icon>receipt_long</mat-icon>Emitir recibo</button> }</td>
                      </tr>
                    }
                  </tbody>
                </table></div>
              }
            </section>
          </mat-tab>
        }
      </mat-tab-group>
    </div>
  `,
  styles: `
    .card { margin-top: 16px; }
    .bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .search { flex: 1 1 280px; max-width: 460px; }
    .small { font-size: 12px; }
    .ellipsis { max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .actions { white-space: nowrap; text-align: right; }
    tr.canceled td { opacity: .6; }
    .badge.success { background: var(--app-success-bg); color: var(--app-success); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
  `,
})
export class ReceiptsPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly router = inject(Router);
  readonly items = signal<Receipt[]>([]);
  readonly pending = signal<PendingRevenue[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  search = '';
  readonly canIssue = computed(() => this.auth.hasAny(['FINANCE_WRITE', 'DOCUMENTS_WRITE']));

  constructor() {
    this.load();
  }

  filtered(): Receipt[] {
    const q = this.search.trim().toLowerCase();
    return !q ? this.items() : this.items().filter((r) =>
      [r.number, r.clientName, r.payerName, r.description, r.caseNumber].some((v) => (v ?? '').toLowerCase().includes(q)));
  }

  kindLabel(k: Kind): string {
    return KINDS.find((x) => x.value === k)?.label ?? k;
  }

  money(c: number | null): string {
    return brl(c);
  }

  canCancel(r: Receipt): boolean {
    return KINDS.find((k) => k.value === r.kind)?.monetary ? this.auth.has('FINANCE_WRITE') : this.auth.has('DOCUMENTS_WRITE');
  }

  load(): void {
    this.loading.set(true);
    this.api.get<Receipt[]>('/receipts').subscribe({
      next: (l) => { this.items.set(l); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
    if (this.auth.has('FINANCE_READ')) {
      this.api.get<PendingRevenue[]>('/receipts/pending', undefined, true).subscribe((l) => this.pending.set(l));
    }
  }

  newReceipt(): void {
    this.dialog.open(ReceiptDialogComponent, { data: { kind: null }, width: '720px', maxWidth: '96vw' }).afterClosed().subscribe((r?: Receipt) => {
      if (r) {
        this.toast.success(`${this.kindLabel(r.kind)} ${r.number} emitido.`);
        this.pdf(r);
        this.load();
      }
    });
  }

  fromTransaction(p: PendingRevenue): void {
    this.busy.set(true);
    this.api.post<Receipt>('/receipts/from-transaction', { transactionId: p.transactionId, saveToDocuments: this.auth.has('DOCUMENTS_WRITE') })
      .subscribe({
        next: (r) => { this.busy.set(false); this.toast.success(`Recibo ${r.number} emitido.`); this.pdf(r); this.load(); },
        error: () => this.busy.set(false),
      });
  }

  pdf(r: Receipt): void {
    this.api.getBlob(`/receipts/${r.id}/pdf`).subscribe((res) => saveBlob(res, `recibo-${r.number}.pdf`));
  }

  cancel(r: Receipt): void {
    const data: ConfirmData = { title: `Cancelar ${r.number}`, danger: true, confirmLabel: 'Cancelar recibo', reasonLabel: 'Motivo do cancelamento',
      reasonRequired: true, message: 'O número não é reaproveitado e o PDF passa a mostrar “documento cancelado”. Emita um novo, se necessário.' };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((reason) => {
      if (typeof reason !== 'string' || !reason.trim()) return;
      this.api.post<Receipt>(`/receipts/${r.id}/cancel`, { reason }).subscribe(() => { this.toast.success('Recibo cancelado.'); this.load(); });
    });
  }
}
