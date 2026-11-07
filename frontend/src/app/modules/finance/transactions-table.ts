import { Component, ElementRef, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import {
  CATEGORY_LABELS,
  EXPENSE_CATEGORIES,
  FinanceCategory,
  FinanceDirection,
  FinanceScope,
  INCOME_CATEGORIES,
  PAYMENT_LABELS,
  TX_SITUATION_LABELS,
  TX_SITUATION_TONE,
  Transaction,
  TransactionView,
} from './finance.models';
import { FinanceService, TransactionQuery, saveBlob } from './finance.service';
import { PayDialogComponent, TransactionDialogComponent } from './transaction-dialogs';

@Component({
  selector: 'app-transactions-table',
  imports: [
    FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule,
    MatSelectModule, MatFormFieldModule, MatInputModule, MatTooltipModule, EmptyStateComponent, BrDatePipe, CentsPipe,
  ],
  template: `
    <div class="card">
      <div class="toolbar">
        <mat-button-toggle-group [value]="view()" (change)="view.set($event.value)" hideSingleSelectionIndicator aria-label="Situação">
          <mat-button-toggle value="ABERTOS">Em aberto</mat-button-toggle>
          <mat-button-toggle value="ATRASADOS">Atrasados</mat-button-toggle>
          <mat-button-toggle value="A_VENCER">A vencer</mat-button-toggle>
          <mat-button-toggle value="PAGOS">{{ direction() === 'RECEITA' ? 'Recebidos' : 'Pagos' }}</mat-button-toggle>
          <mat-button-toggle value="CANCELADOS">Cancelados</mat-button-toggle>
          <mat-button-toggle value="TODOS">Todos</mat-button-toggle>
        </mat-button-toggle-group>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="f">
          <mat-label>Categoria</mat-label>
          <mat-select [ngModel]="category()" (ngModelChange)="category.set($event)">
            <mat-option value="">Todas</mat-option>
            @for (c of categories(); track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="f d">
          <mat-label>Vencimento de</mat-label>
          <input matInput type="date" [ngModel]="from()" (ngModelChange)="from.set($event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="f d">
          <mat-label>até</mat-label>
          <input matInput type="date" [ngModel]="to()" (ngModelChange)="to.set($event)" />
        </mat-form-field>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="export()" matTooltip="Exportar CSV (Excel)"><mat-icon>download</mat-icon>CSV</button>
        @if (canWrite()) {
          <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon>{{ direction() === 'RECEITA' ? 'Conta a receber' : 'Conta a pagar' }}</button>
        }
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (!loading() && items().length === 0) {
        <app-empty-state [icon]="direction() === 'RECEITA' ? 'savings' : 'receipt_long'" title="Nenhum lançamento nesta visão" />
      } @else {
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Vencimento</th><th>Descrição</th><th>{{ direction() === 'RECEITA' ? 'Cliente' : 'Favorecido' }}</th><th class="r">Valor</th><th>Situação</th><th></th></tr></thead>
            <tbody>
              @for (t of items(); track t.id) {
                <tr>
                  <td class="nowrap">
                    <div class="mono strong">{{ t.dueDate | brDate }}</div>
                    @if (t.situation === 'ATRASADO') { <div class="small danger">{{ t.daysOverdue }} dia(s) em atraso</div> }
                    @if (t.paidDate) { <div class="small">{{ direction() === 'RECEITA' ? 'recebido' : 'pago' }} {{ t.paidDate | brDate }}</div> }
                  </td>
                  <td>
                    <div class="strong">{{ t.description }}
                      @if (t.installmentCount) { <span class="badge">{{ t.recurrent ? 'mês' : 'parc.' }} {{ t.installmentNumber }}/{{ t.installmentCount }}</span> }
                    </div>
                    <div class="small">{{ t.categoryLabel }}{{ t.documentNumber ? ' · doc. ' + t.documentNumber : '' }}</div>
                  </td>
                  <td>
                    @if (t.clientId) { <a [routerLink]="['/clientes', t.clientId]">{{ t.clientName }}</a> } @else { {{ t.counterparty || '—' }} }
                    @if (t.caseNumber) { <div class="small mono">{{ t.caseNumber }}</div> }
                  </td>
                  <td class="r mono">
                    <div class="strong" [class.income]="direction() === 'RECEITA'">{{ t.amountCents | cents }}</div>
                    @if (t.paidAmountCents != null && t.paidAmountCents !== t.amountCents) { <div class="small">efetivo {{ t.paidAmountCents | cents }}</div> }
                  </td>
                  <td>
                    <span class="badge" [class]="tone[t.situation]">{{ situation[t.situation] }}</span>
                    @if (t.hasReceipt) { <mat-icon class="att" matTooltip="Comprovante anexado">attach_file</mat-icon> }
                  </td>
                  <td class="actions">
                    <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #menu="matMenu" xPosition="before">
                      @if (canWrite() && t.status === 'ABERTO') {
                        <button mat-menu-item (click)="pay(t)"><mat-icon>price_check</mat-icon>{{ direction() === 'RECEITA' ? 'Registrar recebimento' : 'Registrar pagamento' }}</button>
                        <button mat-menu-item (click)="edit(t)"><mat-icon>edit</mat-icon>Editar</button>
                      }
                      @if (canWrite()) {
                        <button mat-menu-item (click)="pickReceipt(t)"><mat-icon>upload_file</mat-icon>{{ t.hasReceipt ? 'Substituir comprovante' : 'Anexar comprovante' }}</button>
                      }
                      @if (t.hasReceipt) {
                        <button mat-menu-item (click)="downloadReceipt(t)"><mat-icon>file_download</mat-icon>Baixar comprovante</button>
                      }
                      @if (canWrite() && t.status === 'ABERTO') {
                        <button mat-menu-item (click)="cancel(t)"><mat-icon>block</mat-icon>Cancelar</button>
                      }
                      @if (canWrite() && t.status !== 'ABERTO') {
                        <button mat-menu-item (click)="reopen(t)"><mat-icon>restart_alt</mat-icon>Reabrir</button>
                      }
                      @if (t.paymentMethod) { <div class="menu-note">Forma: {{ payments[t.paymentMethod] }}</div> }
                    </mat-menu>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
      }
      <input #file type="file" accept="application/pdf,image/png,image/jpeg" hidden (change)="upload($event)" />
    </div>
  `,
  styles: `
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .f { width: 190px; }
    .f.d { width: 160px; }
    .strong { font-weight: 600; }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .small.danger { color: var(--app-danger); font-weight: 600; }
    .income { color: var(--app-success); }
    .r { text-align: right; }
    .nowrap { white-space: nowrap; }
    .badge { margin-left: 6px; }
    td .badge:first-child { margin-left: 0; }
    .att { font-size: 18px; width: 18px; height: 18px; vertical-align: middle; color: var(--app-text-muted); margin-left: 4px; }
    .actions { width: 56px; text-align: right; }
    .menu-note { padding: 6px 16px; font-size: 12px; color: var(--app-text-muted); }
    .more { display: flex; justify-content: center; padding: 16px; }
  `,
})
export class TransactionsTableComponent {
  readonly direction = input.required<FinanceDirection>();
  readonly scope = input<FinanceScope>('ESCRITORIO');
  readonly clientId = input<string | null>(null);
  readonly contractId = input<string | null>(null);
  readonly client = input<{ id: string; name: string } | null>(null);
  /** Incrementado pelo pai para forçar recarga (ex.: após lançamento feito em outra aba). */
  readonly refreshKey = input(0);

  private readonly service = inject(FinanceService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('file');

  readonly labels = CATEGORY_LABELS;
  readonly situation = TX_SITUATION_LABELS;
  readonly tone = TX_SITUATION_TONE;
  readonly payments = PAYMENT_LABELS;
  readonly view = signal<TransactionView>('ABERTOS');
  readonly category = signal<FinanceCategory | ''>('');
  readonly from = signal('');
  readonly to = signal('');
  readonly items = signal<Transaction[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  private uploadTarget: Transaction | null = null;

  categories(): FinanceCategory[] {
    return this.direction() === 'RECEITA' ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
  }

  canWrite(): boolean {
    return this.auth.has('FINANCE_WRITE');
  }

  constructor() {
    effect(() => {
      this.view();
      this.category();
      this.from();
      this.to();
      this.refreshKey();
      this.contractId();
      this.direction();
      this.scope();
      untracked(() => this.load(false));
    });
  }

  private query(): TransactionQuery {
    return {
      scope: this.scope(),
      direction: this.direction(),
      view: this.view(),
      category: this.category(),
      clientId: this.clientId() ?? undefined,
      contractId: this.contractId() ?? undefined,
      from: this.from() || undefined,
      to: this.to() || undefined,
    };
  }

  load(more: boolean): void {
    this.loading.set(true);
    this.service.list(this.query(), more ? this.cursor() : null).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  create(): void {
    const client = this.client();
    this.dialog
      .open(TransactionDialogComponent, { data: { direction: this.direction(), scope: this.scope(), client }, width: '860px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((r?: Transaction[]) => {
        if (r) {
          this.toast.success(r.length > 1 ? `${r.length} lançamentos criados.` : 'Lançamento criado.');
          this.load(false);
        }
      });
  }

  edit(t: Transaction): void {
    this.dialog
      .open(TransactionDialogComponent, { data: { direction: this.direction(), scope: this.scope(), transaction: t }, width: '860px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((r) => {
        if (r) {
          this.toast.success('Lançamento atualizado.');
          this.load(false);
        }
      });
  }

  pay(t: Transaction): void {
    this.dialog.open(PayDialogComponent, { data: t, width: '640px', maxWidth: '96vw' }).afterClosed().subscribe((r) => {
      if (r) {
        this.toast.success('Baixa registrada. Anexe o comprovante pelo menu do lançamento.');
        this.load(false);
      }
    });
  }

  cancel(t: Transaction): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Cancelar lançamento', message: t.description, confirmLabel: 'Cancelar lançamento', danger: true, reasonLabel: 'Motivo', reasonRequired: true },
        width: '460px',
      })
      .afterClosed()
      .subscribe((reason) => {
        if (typeof reason === 'string' && reason) {
          this.service.cancel(t.id, reason).subscribe(() => { this.toast.success('Lançamento cancelado.'); this.load(false); });
        }
      });
  }

  reopen(t: Transaction): void {
    this.service.reopen(t.id).subscribe(() => { this.toast.success('Lançamento reaberto.'); this.load(false); });
  }

  pickReceipt(t: Transaction): void {
    this.uploadTarget = t;
    this.fileInput().nativeElement.value = '';
    this.fileInput().nativeElement.click();
  }

  upload(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const target = this.uploadTarget;
    if (!file || !target) return;
    if (file.size > 10 * 1024 * 1024) {
      this.toast.error('Arquivo maior que 10 MB.');
      return;
    }
    this.service.uploadReceipt(target.id, file).subscribe(() => {
      this.toast.success('Comprovante anexado.');
      this.load(false);
    });
  }

  downloadReceipt(t: Transaction): void {
    this.service.downloadReceipt(t.id).subscribe((r) => saveBlob(r, t.receiptName ?? 'comprovante'));
  }

  export(): void {
    this.service.exportCsv(this.query()).subscribe((r) => saveBlob(r, 'lancamentos.csv'));
  }
}
