import { Component, inject, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import {
  ACCOUNT_KIND_LABELS, BankAccount, BankAccountKind, CHANNEL_LABELS, CollectionChannel, CollectionDraft, CollectionOutcome, CollectionRecord,
  CostCenter, OUTCOME_LABELS, Statement,
} from './finance.models';
import { FinanceService } from './finance.service';

const localToday = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// ======================================================================= aba: contas e centros de custo
@Component({
  selector: 'app-finance-setup',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, MatProgressBarModule, EmptyStateComponent, CentsPipe],
  template: `
    <div class="setup">
      <section>
        <div class="head">
          <h3><mat-icon>account_balance</mat-icon>Contas bancárias e caixa</h3>
          @if (canWrite) { <button mat-stroked-button (click)="editAccount(null)"><mat-icon>add</mat-icon>Nova conta</button> }
        </div>
        <p class="muted small">O saldo é calculado pelo saldo inicial e pelas baixas registradas em cada conta. A conciliação automática com o banco depende de integração autorizada (Fase 4).</p>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && !accounts().length) {
          <app-empty-state icon="account_balance" title="Nenhuma conta cadastrada" text="Cadastre as contas do escritório para acompanhar saldos e extratos." />
        }
        <div class="grid">
          @for (a of accounts(); track a.id) {
            <div class="card acc" [class.off]="!a.active">
              <div class="row">
                <div><strong>{{ a.name }}</strong><div class="muted small">{{ kinds[a.kind] }}{{ a.bankName ? ' · ' + a.bankName : '' }}{{ a.accountMasked ? ' · ' + a.accountMasked : '' }}</div></div>
                <button mat-icon-button [matMenuTriggerFor]="am" aria-label="Ações da conta"><mat-icon>more_vert</mat-icon></button>
                <mat-menu #am="matMenu" xPosition="before">
                  <button mat-menu-item (click)="openStatement(a)"><mat-icon>receipt_long</mat-icon>Extrato</button>
                  @if (canWrite) {
                    <button mat-menu-item (click)="editAccount(a)"><mat-icon>edit</mat-icon>Editar</button>
                    <button mat-menu-item (click)="removeAccount(a)"><mat-icon>delete</mat-icon>Excluir</button>
                  }
                </mat-menu>
              </div>
              <div class="bal mono" [class.neg]="a.balanceCents < 0">{{ a.balanceCents | cents }}</div>
              <div class="muted small">Entradas {{ a.incomeCents | cents }} · saídas {{ a.expenseCents | cents }} · {{ a.movements }} movimento(s)</div>
              @if (!a.active) { <span class="badge">inativa</span> }
              <button mat-button class="st" (click)="openStatement(a)"><mat-icon>receipt_long</mat-icon>Ver extrato</button>
            </div>
          }
        </div>
      </section>

      <section>
        <div class="head">
          <h3><mat-icon>account_tree</mat-icon>Centros de custo</h3>
          @if (canWrite) { <button mat-stroked-button (click)="editCenter(null)"><mat-icon>add</mat-icon>Novo centro de custo</button> }
        </div>
        <p class="muted small">Classifique receitas e despesas por área (contencioso, consultivo…), unidade ou projeto — o relatório de despesas agrupa por centro de custo.</p>
        @if (!centers().length) {
          <p class="muted small">Nenhum centro de custo cadastrado.</p>
        } @else {
          <div class="table-wrap">
            <table class="data stack">
              <thead><tr><th>Nome</th><th>Código</th><th>Descrição</th><th>Situação</th><th></th></tr></thead>
              <tbody>
                @for (c of centers(); track c.id) {
                  <tr>
                    <td data-label="Nome"><strong>{{ c.name }}</strong></td>
                    <td data-label="Código" class="mono">{{ c.code || '—' }}</td>
                    <td data-label="Descrição">{{ c.description || '—' }}</td>
                    <td data-label="Situação"><span class="badge" [class.ok]="c.active">{{ c.active ? 'Ativo' : 'Inativo' }}</span></td>
                    <td class="actions">
                      @if (canWrite) {
                        <button mat-icon-button (click)="editCenter(c)" aria-label="Editar"><mat-icon>edit</mat-icon></button>
                        <button mat-icon-button (click)="removeCenter(c)" aria-label="Excluir"><mat-icon>delete</mat-icon></button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .setup { display: flex; flex-direction: column; gap: 24px; padding-top: 12px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
    h3 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; }
    h3 mat-icon { color: var(--app-gold); }
    .small { font-size: 12px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
    .acc { padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
    .acc.off { opacity: .7; }
    .row { display: flex; justify-content: space-between; align-items: flex-start; gap: 8px; }
    .bal { font-size: 24px; font-weight: 700; margin-top: 6px; }
    .bal.neg { color: var(--app-danger); }
    .st { align-self: flex-start; margin-top: 4px; }
    .badge.ok { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .actions { width: 100px; text-align: right; }
  `,
})
export class FinanceSetupComponent {
  private readonly service = inject(FinanceService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('FINANCE_WRITE');
  readonly kinds = ACCOUNT_KIND_LABELS;
  readonly accounts = signal<BankAccount[]>([]);
  readonly centers = signal<CostCenter[]>([]);
  readonly loading = signal(false);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.accounts().subscribe({ next: (a) => { this.accounts.set(a); this.loading.set(false); }, error: () => this.loading.set(false) });
    this.service.costCenters().subscribe((c) => this.centers.set(c));
  }

  editAccount(a: BankAccount | null): void {
    this.dialog.open(BankAccountDialogComponent, { data: a, width: '620px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Conta salva.'); this.load(); } });
  }

  removeAccount(a: BankAccount): void {
    this.confirm(`Excluir a conta “${a.name}”? Só é possível sem baixas registradas — se houver, desative-a.`, () =>
      this.service.deleteAccount(a.id).subscribe(() => { this.toast.success('Conta excluída.'); this.load(); }));
  }

  openStatement(a: BankAccount): void {
    this.dialog.open(StatementDialogComponent, { data: a, width: '900px', maxWidth: '98vw' });
  }

  editCenter(c: CostCenter | null): void {
    this.dialog.open(CostCenterDialogComponent, { data: c, width: '520px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Centro de custo salvo.'); this.load(); } });
  }

  removeCenter(c: CostCenter): void {
    this.confirm(`Excluir o centro de custo “${c.name}”? Só é possível sem lançamentos — se houver, desative-o.`, () =>
      this.service.deleteCostCenter(c.id).subscribe(() => { this.toast.success('Centro de custo excluído.'); this.load(); }));
  }

  private confirm(message: string, then: () => void): void {
    this.dialog.open(ConfirmDialogComponent, { data: { title: 'Confirmar exclusão', message, confirmLabel: 'Excluir', danger: true }, width: '440px' })
      .afterClosed().subscribe((ok) => { if (ok) then(); });
  }
}

// ======================================================================= conta bancária
@Component({
  selector: 'app-bank-account-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>account_balance</mat-icon>{{ a ? 'Editar conta' : 'Nova conta' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="accForm" (ngSubmit)="save()">
        <mat-form-field class="c8"><mat-label>Nome</mat-label><input matInput formControlName="name" maxlength="80" placeholder="Ex.: Conta principal — Banco X" /></mat-form-field>
        <mat-form-field class="c4"><mat-label>Tipo</mat-label>
          <mat-select formControlName="kind">@for (k of kindKeys; track k) { <mat-option [value]="k">{{ kinds[k] }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field class="c6"><mat-label>Banco</mat-label><input matInput formControlName="bankName" maxlength="80" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Código</mat-label><input matInput formControlName="bankCode" maxlength="5" /></mat-form-field>
        <mat-form-field class="c4"><mat-label>Agência</mat-label><input matInput formControlName="agency" maxlength="10" /></mat-form-field>
        <mat-form-field class="c6"><mat-label>Conta</mat-label><input matInput formControlName="accountNumber" maxlength="20" /></mat-form-field>
        <mat-form-field class="c3"><mat-label>Saldo inicial (R$)</mat-label><input matInput formControlName="initial" inputmode="decimal" /></mat-form-field>
        <mat-form-field class="c3"><mat-label>Saldo em</mat-label><input matInput type="date" formControlName="initialDate" /></mat-form-field>
        <mat-checkbox formControlName="active">Conta ativa (disponível nas baixas)</mat-checkbox>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="accForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
})
export class BankAccountDialogComponent {
  readonly a = inject<BankAccount | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<BankAccountDialogComponent>);
  private readonly service = inject(FinanceService);
  readonly kinds = ACCOUNT_KIND_LABELS;
  readonly kindKeys = Object.keys(ACCOUNT_KIND_LABELS) as BankAccountKind[];
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.a?.name ?? '', [Validators.required, Validators.maxLength(80)]],
    kind: [(this.a?.kind ?? 'CORRENTE') as BankAccountKind],
    bankName: [this.a?.bankName ?? ''],
    bankCode: [this.a?.bankCode ?? ''],
    agency: [this.a?.agency ?? ''],
    accountNumber: [this.a?.accountNumber ?? ''],
    initial: [centsToInput(this.a?.initialBalanceCents ?? 0)],
    initialDate: [this.a?.initialBalanceDate ?? localToday()],
    active: [this.a?.active ?? true],
  });

  save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    const raw = String(v.initial ?? '').trim();
    const negative = raw.startsWith('-');
    const cents = (parseMoneyToCents(raw.replace(/^-/, '')) ?? 0) * (negative ? -1 : 1);
    this.saving.set(true);
    this.service.saveAccount(this.a?.id ?? null, {
      name: v.name.trim(), kind: v.kind, bankName: v.bankName.trim() || null, bankCode: v.bankCode.trim() || null, agency: v.agency.trim() || null,
      accountNumber: v.accountNumber.trim() || null, initialBalanceCents: cents, initialBalanceDate: v.initialDate || null, active: v.active,
    }).subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= extrato
@Component({
  selector: 'app-statement-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatProgressBarModule, CentsPipe, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>receipt_long</mat-icon>Extrato · {{ a.name }}</h2>
    <mat-dialog-content>
      <div class="filters">
        <label>De <input type="date" [(ngModel)]="from" (change)="load()" /></label>
        <label>Até <input type="date" [(ngModel)]="to" (change)="load()" /></label>
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (st(); as s) {
        <div class="sum">
          <span>Saldo anterior <strong class="mono">{{ s.openingCents | cents }}</strong></span>
          <span>Saldo final <strong class="mono" [class.neg]="s.closingCents < 0">{{ s.closingCents | cents }}</strong></span>
        </div>
        <div class="table-wrap">
          <table class="data stack">
            <thead><tr><th>Data</th><th>Descrição</th><th>Categoria</th><th class="r">Valor</th><th class="r">Saldo</th></tr></thead>
            <tbody>
              @for (l of s.lines; track l.transactionId) {
                <tr>
                  <td data-label="Data">{{ l.date | brDate }}</td>
                  <td data-label="Descrição">{{ l.description }}@if (l.counterparty) { <div class="muted small">{{ l.counterparty }}</div> }</td>
                  <td data-label="Categoria">{{ l.categoryLabel }}</td>
                  <td data-label="Valor" class="r mono" [class.neg]="l.amountCents < 0" [class.pos]="l.amountCents > 0">{{ l.amountCents | cents }}</td>
                  <td data-label="Saldo" class="r mono">{{ l.balanceCents | cents }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="muted">Nenhum movimento no período.</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Fechar</button></mat-dialog-actions>
  `,
  styles: `
    .filters { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .filters input { margin-left: 6px; border: 1px solid var(--app-border); border-radius: 8px; padding: 6px 8px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .sum { display: flex; gap: 24px; flex-wrap: wrap; margin: 8px 0 12px; }
    .neg { color: var(--app-danger); }
    .pos { color: var(--app-success, #2e7d32); }
    .r { text-align: right; }
    .small { font-size: 12px; }
  `,
})
export class StatementDialogComponent {
  readonly a = inject<BankAccount>(MAT_DIALOG_DATA);
  private readonly service = inject(FinanceService);
  readonly st = signal<Statement | null>(null);
  readonly loading = signal(false);
  from = localToday().slice(0, 8) + '01';
  to = localToday();

  constructor() {
    this.load();
  }

  load(): void {
    if (!this.from || !this.to) return;
    this.loading.set(true);
    this.service.statement(this.a.id, this.from, this.to).subscribe({
      next: (s) => { this.st.set(s); this.loading.set(false); }, error: () => this.loading.set(false),
    });
  }
}

// ======================================================================= centro de custo
@Component({
  selector: 'app-cost-center-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>account_tree</mat-icon>{{ c ? 'Editar centro de custo' : 'Novo centro de custo' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="ccForm" (ngSubmit)="save()">
        <mat-form-field class="c8"><mat-label>Nome</mat-label><input matInput formControlName="name" maxlength="80" placeholder="Ex.: Contencioso cível" /></mat-form-field>
        <mat-form-field class="c4"><mat-label>Código</mat-label><input matInput formControlName="code" maxlength="20" /></mat-form-field>
        <mat-form-field><mat-label>Descrição</mat-label><input matInput formControlName="description" maxlength="300" /></mat-form-field>
        <mat-checkbox formControlName="active">Ativo</mat-checkbox>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="ccForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
})
export class CostCenterDialogComponent {
  readonly c = inject<CostCenter | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CostCenterDialogComponent>);
  private readonly service = inject(FinanceService);
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.c?.name ?? '', [Validators.required, Validators.maxLength(80)]],
    code: [this.c?.code ?? ''],
    description: [this.c?.description ?? ''],
    active: [this.c?.active ?? true],
  });

  save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.saveCostCenter(this.c?.id ?? null, { name: v.name.trim(), code: v.code.trim() || null, description: v.description.trim() || null, active: v.active })
      .subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= cobrança
export interface CollectionDialogData {
  clientId: string;
  clientName: string;
}

@Component({
  selector: 'app-collection-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatProgressBarModule,
    CentsPipe, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>campaign</mat-icon>Cobrança · {{ data.clientName }}</h2>
    <mat-dialog-content>
      @if (!draft()) { <mat-progress-bar mode="indeterminate" /> }
      @if (draft(); as d) {
        <p class="muted small">{{ d.titles }} título(s) em atraso · <strong>{{ d.amountCents | cents }}</strong>{{ d.phone ? ' · ' + d.phone : '' }}{{ d.email ? ' · ' + d.email : '' }}</p>
        <mat-form-field class="full">
          <mat-label>Mensagem de cobrança</mat-label>
          <textarea matInput rows="8" [(ngModel)]="message" maxlength="2000"></textarea>
        </mat-form-field>
        <div class="row">
          <button mat-stroked-button (click)="copy()"><mat-icon>content_copy</mat-icon>Copiar mensagem</button>
          @if (d.whatsappUrl) { <button mat-stroked-button (click)="whatsapp(d)"><mat-icon>chat</mat-icon>Abrir no WhatsApp</button> }
          @if (d.email) { <a mat-stroked-button [href]="mailto(d)"><mat-icon>mail</mat-icon>Abrir e-mail</a> }
        </div>
        <h4>Registrar o contato</h4>
        <div class="grid">
          <mat-form-field><mat-label>Canal</mat-label>
            <mat-select [(ngModel)]="channel">@for (c of channels; track c) { <mat-option [value]="c">{{ channelLabels[c] }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field><mat-label>Resultado</mat-label>
            <mat-select [(ngModel)]="outcome">@for (o of outcomes; track o) { <mat-option [value]="o">{{ outcomeLabels[o] }}</mat-option> }</mat-select></mat-form-field>
          @if (outcome === 'PROMESSA_PAGAMENTO') {
            <mat-form-field><mat-label>Pagamento prometido para</mat-label><input matInput type="date" [(ngModel)]="promisedDate" /></mat-form-field>
          }
        </div>
        @if (history().length) {
          <h4>Histórico de cobranças</h4>
          <ul class="hist">
            @for (h of history(); track h.id) {
              <li><strong>{{ h.createdAt | brDate: true }}</strong> · {{ channelLabels[h.channel] }} · {{ outcomeLabels[h.outcome] }}{{ h.promisedDate ? ' (até ' + (h.promisedDate | brDate) + ')' : '' }} · {{ h.createdByName }}</li>
            }
          </ul>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Fechar</button>
      @if (canWrite) { <button mat-flat-button (click)="register()" [disabled]="saving() || !draft() || !ids().length"><mat-icon>save</mat-icon>Registrar cobrança</button> }
    </mat-dialog-actions>
  `,
  styles: `
    .full { width: 100%; }
    .small { font-size: 12px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
    h4 { margin: 16px 0 8px; font-size: 13px; text-transform: uppercase; letter-spacing: .05em; color: var(--app-text-muted); }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 10px; }
    .hist { margin: 0; padding-left: 18px; font-size: 13px; }
  `,
})
export class CollectionDialogComponent {
  readonly data = inject<CollectionDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CollectionDialogComponent>);
  private readonly service = inject(FinanceService);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('FINANCE_WRITE');
  readonly channelLabels = CHANNEL_LABELS;
  readonly outcomeLabels = OUTCOME_LABELS;
  readonly channels = Object.keys(CHANNEL_LABELS) as CollectionChannel[];
  readonly outcomes = Object.keys(OUTCOME_LABELS) as CollectionOutcome[];
  readonly draft = signal<CollectionDraft | null>(null);
  readonly history = signal<CollectionRecord[]>([]);
  readonly ids = signal<string[]>([]);
  readonly saving = signal(false);
  message = '';
  channel: CollectionChannel = 'WHATSAPP';
  outcome: CollectionOutcome = 'SEM_RETORNO';
  promisedDate = '';

  constructor() {
    this.service.collectionDraft(this.data.clientId).subscribe((d) => { this.draft.set(d); this.message = d.message; });
    this.service.collections(this.data.clientId).subscribe((h) => this.history.set(h));
    this.service.list({ scope: 'ESCRITORIO', direction: 'RECEITA', view: 'ATRASADOS', clientId: this.data.clientId }, null, 100)
      .subscribe((p) => this.ids.set(p.items.map((t) => t.id)));
  }

  copy(): void {
    navigator.clipboard?.writeText(this.message).then(() => this.toast.success('Mensagem copiada.'), () => undefined);
  }

  whatsapp(d: CollectionDraft): void {
    // Link oficial "clique para conversar": abre o WhatsApp do próprio escritório com a mensagem pronta.
    const base = d.whatsappUrl!.split('?')[0];
    window.open(`${base}?text=${encodeURIComponent(this.message)}`, '_blank', 'noopener');
    this.channel = 'WHATSAPP';
  }

  mailto(d: CollectionDraft): string {
    return `mailto:${d.email}?subject=${encodeURIComponent('Pagamentos em aberto')}&body=${encodeURIComponent(this.message)}`;
  }

  register(): void {
    if (this.outcome === 'PROMESSA_PAGAMENTO' && !this.promisedDate) {
      this.toast.error('Informe a data prometida.');
      return;
    }
    this.saving.set(true);
    this.service.registerCollection({ transactionIds: this.ids(), channel: this.channel, outcome: this.outcome,
      message: this.message.trim() || null, promisedDate: this.promisedDate || null })
      .subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
