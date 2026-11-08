import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeOption } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { CATEGORY_LABELS, FinanceCategory } from '../finance/finance.models';
import {
  CommissionRule, CommissionStatement, FEE_CATEGORIES, FeeGroup, FeeGroupBy, FeeItem, FeeSummary, FeesService, Payout, percent,
} from './fees.service';

type Period = 'mes' | '3m' | '12m' | 'ano';
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

const SITUATION: Record<FeeItem['situation'], string> = {
  A_VENCER: 'A vencer', VENCE_HOJE: 'Vence hoje', ATRASADO: 'Atrasado', PAGO: 'Recebido', CANCELADO: 'Cancelado',
};

/** Honorários por cliente, processo, contrato e categoria; comissões sobre honorários recebidos. */
@Component({
  selector: 'app-fees-page',
  imports: [NgTemplateOutlet, FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressBarModule, MatTabsModule, MatTooltipModule,
    PageHeaderComponent, StatCardComponent, EmptyStateComponent, CentsPipe, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão" title="Honorários" subtitle="Contratados, recebidos, pendentes e em atraso — por cliente, processo e contrato — e comissões da equipe.">
        <mat-button-toggle-group [value]="period()" (change)="setPeriod($event.value)" hideSingleSelectionIndicator aria-label="Período">
          <mat-button-toggle value="mes">Mês</mat-button-toggle>
          <mat-button-toggle value="3m">3 meses</mat-button-toggle>
          <mat-button-toggle value="12m">12 meses</mat-button-toggle>
          <mat-button-toggle value="ano">Ano</mat-button-toggle>
        </mat-button-toggle-group>
      </app-page-header>

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" (selectedIndexChange)="onTab($event)">
        @if (canRead) {
          <mat-tab label="Honorários">
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (summary(); as s) {
              <section class="kpis">
                <app-stat-card label="Contratado no período" [value]="money(s.totals.contractedCents)" icon="gavel" [accent]="true" [hint]="s.totals.titles + ' título(s)'" />
                <app-stat-card label="Recebido" [value]="money(s.totals.receivedCents)" icon="payments" tone="success" [hint]="rate(s.totals) + ' do contratado'" />
                <app-stat-card label="Pendente" [value]="money(s.totals.pendingCents)" icon="hourglass_top" />
                <app-stat-card label="Em atraso" [value]="money(s.totals.overdueCents)" icon="report" [tone]="s.totals.overdueCents ? 'danger' : 'success'" />
              </section>
              <div class="card">
                <div class="toolbar">
                  <mat-button-toggle-group [value]="groupBy()" (change)="groupBy.set($event.value); load()" aria-label="Agrupar por">
                    <mat-button-toggle value="CLIENT">Por cliente</mat-button-toggle>
                    <mat-button-toggle value="CASE">Por processo</mat-button-toggle>
                    <mat-button-toggle value="CONTRACT">Por contrato</mat-button-toggle>
                    <mat-button-toggle value="CATEGORY">Por tipo</mat-button-toggle>
                  </mat-button-toggle-group>
                </div>
                @if (!s.groups.length) {
                  <app-empty-state icon="gavel" title="Nenhum honorário no período" text="Os honorários vêm das contas a receber (honorários, êxito, sucumbência e partido mensal) e dos contratos ativos." />
                } @else {
                  <div class="table-wrap">
                    <table class="data stack">
                      <thead><tr><th>{{ groupLabel() }}</th><th class="r">Contratado</th><th class="r">Recebido</th><th class="r">Pendente</th><th class="r">Em atraso</th><th></th></tr></thead>
                      <tbody>
                        @for (g of s.groups; track g.key) {
                          <tr>
                            <td [attr.data-label]="groupLabel()">
                              <strong>{{ g.label }}</strong>
                              <div class="muted small">{{ g.detail || '' }}{{ g.feeModel ? (g.detail ? ' · ' : '') + g.feeModel : '' }}{{ g.successPercentBp ? ' · êxito ' + pct(g.successPercentBp) : '' }}</div>
                            </td>
                            <td data-label="Contratado" class="r mono">{{ g.totals.contractedCents | cents }}</td>
                            <td data-label="Recebido" class="r mono ok">{{ g.totals.receivedCents | cents }}</td>
                            <td data-label="Pendente" class="r mono">{{ g.totals.pendingCents | cents }}</td>
                            <td data-label="Em atraso" class="r mono" [class.bad]="g.totals.overdueCents">{{ g.totals.overdueCents | cents }}</td>
                            <td class="actions">
                              @if (g.key !== '-' && groupBy() !== 'CATEGORY') {
                                <button mat-icon-button (click)="toggle(g)" [matTooltip]="open() === g.key ? 'Ocultar parcelas' : 'Parcelas e pagamentos'" aria-label="Parcelas e pagamentos">
                                  <mat-icon>{{ open() === g.key ? 'expand_less' : 'expand_more' }}</mat-icon></button>
                              }
                            </td>
                          </tr>
                          @if (open() === g.key) {
                            <tr class="detail"><td colspan="6">
                              @if (!items()) { <mat-progress-bar mode="indeterminate" /> }
                              @else {
                                <table class="data compact">
                                  <thead><tr><th>Vencimento</th><th>Descrição</th><th>Tipo</th><th class="r">Valor</th><th>Situação</th><th>Pagamento</th></tr></thead>
                                  <tbody>
                                    @for (i of items(); track i.id) {
                                      <tr>
                                        <td>{{ i.dueDate | brDate }}</td>
                                        <td>{{ i.description }}@if (i.installmentCount) { <span class="badge">{{ i.installmentNumber }}/{{ i.installmentCount }}</span> }</td>
                                        <td>{{ i.categoryLabel }}</td>
                                        <td class="r mono">{{ i.amountCents | cents }}</td>
                                        <td><span class="badge" [class.ok]="i.situation === 'PAGO'" [class.danger]="i.situation === 'ATRASADO'">{{ situation[i.situation] }}</span></td>
                                        <td>@if (i.paidDate) { {{ i.paidDate | brDate }} · {{ (i.paidAmountCents ?? i.amountCents) | cents }}{{ i.receiptNumber ? ' · recibo ' + i.receiptNumber : '' }} } @else { — }</td>
                                      </tr>
                                    }
                                  </tbody>
                                </table>
                                @if (g.key && groupBy() === 'CLIENT') { <a mat-button [routerLink]="['/clientes', g.key]"><mat-icon>person</mat-icon>Abrir cliente</a> }
                                @if (groupBy() === 'CONTRACT') { <a mat-button [routerLink]="['/contratos', g.key]"><mat-icon>handshake</mat-icon>Abrir contrato</a> }
                                @if (groupBy() === 'CASE') { <a mat-button [routerLink]="['/processos', g.key]"><mat-icon>gavel</mat-icon>Abrir processo</a> }
                              }
                            </td></tr>
                          }
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </div>
            }
          </mat-tab>
          <mat-tab label="Comissões">
            <div class="tabpad">
              <div class="head">
                <h3>Regras de comissão</h3>
                @if (canWrite) { <button mat-stroked-button (click)="editRule(null)"><mat-icon>add</mat-icon>Nova regra</button> }
              </div>
              <p class="muted small">Percentual sobre honorários efetivamente recebidos, por integrante — em geral ou restrito a um cliente, a um processo ou aos processos em que ele é o responsável.</p>
              @if (!rules().length) { <p class="muted small">Nenhuma regra cadastrada.</p> }
              @else {
                <div class="table-wrap">
                  <table class="data stack">
                    <thead><tr><th>Integrante</th><th>Percentual</th><th>Sobre</th><th>Abrangência</th><th>Situação</th><th></th></tr></thead>
                    <tbody>
                      @for (r of rules(); track r.id) {
                        <tr>
                          <td data-label="Integrante"><strong>{{ r.beneficiaryName }}</strong>@if (r.description) { <div class="muted small">{{ r.description }}</div> }</td>
                          <td data-label="Percentual" class="mono">{{ pct(r.percentBp) }}</td>
                          <td data-label="Sobre">{{ catLabels(r.categories) }}</td>
                          <td data-label="Abrangência">{{ r.caseNumber ? 'Processo ' + r.caseNumber : r.clientName ? 'Cliente ' + r.clientName : r.responsibleOnly ? 'Processos sob sua responsabilidade' : 'Todos os honorários' }}</td>
                          <td data-label="Situação"><span class="badge" [class.ok]="r.active">{{ r.active ? 'Ativa' : 'Inativa' }}</span></td>
                          <td class="actions">@if (canWrite) {
                            <button mat-icon-button (click)="editRule(r)" aria-label="Editar"><mat-icon>edit</mat-icon></button>
                            <button mat-icon-button (click)="removeRule(r)" aria-label="Excluir"><mat-icon>delete</mat-icon></button> }</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
              <div class="head"><h3>Apuração do período ({{ from() | brDate }} a {{ to() | brDate }})</h3></div>
              <ng-container [ngTemplateOutlet]="statementsTpl" [ngTemplateOutletContext]="{ list: statements(), pay: canWrite }" />
              @if (payouts().length) {
                <div class="head"><h3>Comissões lançadas no contas a pagar</h3></div>
                <ul class="payouts">
                  @for (p of payouts(); track p.id) {
                    <li><strong>{{ p.beneficiaryName }}</strong> · {{ p.periodFrom | brDate }} a {{ p.periodTo | brDate }} · {{ p.amountCents | cents }} · {{ p.receipts }} recebimento(s) · lançado por {{ p.createdByName }}</li>
                  }
                </ul>
              }
            </div>
          </mat-tab>
        }
        <mat-tab label="Minhas comissões">
          <div class="tabpad">
            <p class="muted small">Comissões apuradas para você no período, sobre honorários já recebidos pelo escritório.</p>
            <ng-container [ngTemplateOutlet]="statementsTpl" [ngTemplateOutletContext]="{ list: mine(), pay: false }" />
          </div>
        </mat-tab>
      </mat-tab-group>

      <ng-template #statementsTpl let-list="list" let-pay="pay">
        @if (!list.length) {
          <app-empty-state icon="savings" title="Nenhuma comissão no período" text="As comissões aparecem quando há regra ativa e honorários recebidos." />
        }
        @for (st of list; track st.beneficiaryUid) {
          <div class="card st">
            <div class="st-head">
              <div><strong>{{ st.beneficiaryName }}</strong>
                <div class="muted small">Base {{ st.baseCents | cents }} · comissão {{ st.commissionCents | cents }} · já lançada {{ st.paidOutCents | cents }}</div></div>
              <span class="mono pend">{{ st.pendingCents | cents }} <small class="muted">pendente</small></span>
              @if (pay && st.pendingCents > 0) { <button mat-flat-button (click)="payout(st)"><mat-icon>payments</mat-icon>Lançar no contas a pagar</button> }
            </div>
            <div class="table-wrap">
              <table class="data compact">
                <thead><tr><th>Recebido em</th><th>Honorário</th><th>Cliente / processo</th><th class="r">Base</th><th>%</th><th class="r">Comissão</th><th></th></tr></thead>
                <tbody>
                  @for (l of st.lines; track l.receiptId + l.ruleId) {
                    <tr>
                      <td>{{ l.paidDate | brDate }}</td><td>{{ l.description }}</td>
                      <td>{{ l.clientName || '—' }}@if (l.caseNumber) { <div class="small mono">{{ l.caseNumber }}</div> }</td>
                      <td class="r mono">{{ l.baseCents | cents }}</td><td class="mono">{{ pct(l.percentBp) }}</td>
                      <td class="r mono">{{ l.commissionCents | cents }}</td>
                      <td>@if (l.paidOut) { <span class="badge ok">lançada</span> }</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }
      </ng-template>
    </div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; margin: 16px 0; }
    .toolbar { display: flex; gap: 10px; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .r { text-align: right; }
    .ok { color: var(--app-success, #2e7d32); }
    .bad { color: var(--app-danger); font-weight: 700; }
    .small { font-size: 12px; }
    .actions { width: 96px; text-align: right; }
    .detail td { background: var(--app-surface-2); }
    .badge.ok { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
    .tabpad { padding: 16px 0; display: flex; flex-direction: column; gap: 10px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
    h3 { margin: 0; font-size: 16px; }
    .st { padding: 0; margin-bottom: 12px; }
    .st-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .st-head > div { flex: 1; min-width: 200px; }
    .pend { font-size: 18px; font-weight: 700; }
    .payouts { margin: 0; padding-left: 18px; font-size: 13px; }
  `,
})
export class FeesPageComponent {
  private readonly service = inject(FeesService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  readonly canRead = this.auth.has('FINANCE_READ');
  readonly canWrite = this.auth.has('FINANCE_WRITE');
  readonly situation = SITUATION;
  readonly period = signal<Period>('12m');
  readonly groupBy = signal<FeeGroupBy>('CLIENT');
  readonly summary = signal<FeeSummary | null>(null);
  readonly loading = signal(false);
  readonly open = signal<string | null>(null);
  readonly items = signal<FeeItem[] | null>(null);
  readonly rules = signal<CommissionRule[]>([]);
  readonly statements = signal<CommissionStatement[]>([]);
  readonly mine = signal<CommissionStatement[]>([]);
  readonly payouts = signal<Payout[]>([]);
  readonly from = signal('');
  readonly to = signal('');
  readonly groupLabel = computed(() => ({ CLIENT: 'Cliente', CASE: 'Processo', CONTRACT: 'Contrato', CATEGORY: 'Tipo de honorário' } as const)[this.groupBy()]);
  private tab = 0;

  constructor() {
    this.computeRange();
    this.load();
    this.loadMine();
  }

  money(cents: number): string {
    return BRL.format(cents / 100);
  }

  pct(bp: number | null): string {
    return percent(bp);
  }

  rate(t: { contractedCents: number; receivedCents: number }): string {
    return t.contractedCents ? Math.round((t.receivedCents / t.contractedCents) * 100) + '%' : '—';
  }

  catLabels(c: FinanceCategory[]): string {
    return c.length === FEE_CATEGORIES.length ? 'Todos os honorários' : c.map((x) => CATEGORY_LABELS[x]).join(', ');
  }

  setPeriod(p: Period): void {
    this.period.set(p);
    this.computeRange();
    this.load();
    this.loadMine();
    if (this.tab === 1 && this.canRead) this.loadCommissions();
  }

  private computeRange(): void {
    const today = new Date();
    let from: Date;
    let to: Date;
    switch (this.period()) {
      case 'mes': from = new Date(today.getFullYear(), today.getMonth(), 1); to = new Date(today.getFullYear(), today.getMonth() + 1, 0); break;
      case '3m': from = new Date(today.getFullYear(), today.getMonth() - 2, 1); to = new Date(today.getFullYear(), today.getMonth() + 1, 0); break;
      case 'ano': from = new Date(today.getFullYear(), 0, 1); to = new Date(today.getFullYear(), 11, 31); break;
      default: from = new Date(today.getFullYear(), today.getMonth() - 11, 1); to = new Date(today.getFullYear(), today.getMonth() + 1, 0);
    }
    this.from.set(iso(from));
    this.to.set(iso(to));
  }

  load(): void {
    if (!this.canRead) return;
    this.loading.set(true);
    this.open.set(null);
    this.service.summary(this.from(), this.to(), this.groupBy()).subscribe({
      next: (s) => { this.summary.set(s); this.loading.set(false); }, error: () => this.loading.set(false),
    });
  }

  private loadMine(): void {
    this.service.mine(this.from(), this.to()).pipe(catchError(() => of([]))).subscribe((m) => this.mine.set(m));
  }

  private loadCommissions(): void {
    this.service.rules().subscribe((r) => this.rules.set(r));
    this.service.commissions(this.from(), this.to()).subscribe((s) => this.statements.set(s));
    this.service.payouts().subscribe((p) => this.payouts.set(p));
  }

  onTab(index: number): void {
    this.tab = index;
    if (index === 1 && this.canRead) this.loadCommissions();
  }

  toggle(g: FeeGroup): void {
    if (this.open() === g.key) {
      this.open.set(null);
      return;
    }
    this.open.set(g.key);
    this.items.set(null);
    const q = this.groupBy() === 'CASE' ? { caseId: g.key } : this.groupBy() === 'CONTRACT' ? { contractId: g.key } : { clientId: g.key };
    this.service.items(q).subscribe({ next: (i) => this.items.set(i), error: () => this.items.set([]) });
  }

  editRule(r: CommissionRule | null): void {
    this.dialog.open(CommissionRuleDialogComponent, { data: r, width: '620px', maxWidth: '96vw' })
      .afterClosed().subscribe((ok) => { if (ok) { this.toast.success('Regra salva.'); this.loadCommissions(); } });
  }

  removeRule(r: CommissionRule): void {
    this.dialog.open(ConfirmDialogComponent, { data: { title: 'Excluir regra', message: `Excluir a regra de ${r.beneficiaryName} (${percent(r.percentBp)})? Comissões já lançadas não são alteradas.`, confirmLabel: 'Excluir', danger: true }, width: '440px' })
      .afterClosed().subscribe((ok) => { if (ok) this.service.deleteRule(r.id).subscribe(() => { this.toast.success('Regra excluída.'); this.loadCommissions(); }); });
  }

  payout(st: CommissionStatement): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Lançar comissões', message: `Lançar ${this.money(st.pendingCents)} de comissões de ${st.beneficiaryName} (${this.from().split('-').reverse().join('/')} a ${this.to().split('-').reverse().join('/')}) no contas a pagar, com vencimento hoje? Os recebimentos ficam marcados como já comissionados.`, confirmLabel: 'Lançar' },
      width: '480px',
    }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.service.payout({ beneficiaryUid: st.beneficiaryUid, from: this.from(), to: this.to(), dueDate: iso(new Date()) })
        .subscribe(() => { this.toast.success('Comissões lançadas no contas a pagar.'); this.loadCommissions(); });
    });
  }
}

// ======================================================================= regra de comissão
@Component({
  selector: 'app-commission-rule-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>savings</mat-icon>{{ r ? 'Editar regra de comissão' : 'Nova regra de comissão' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="ruleForm" (ngSubmit)="save()">
        <mat-form-field class="c8"><mat-label>Integrante</mat-label>
          <mat-select formControlName="beneficiaryUid">@for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field class="c4"><mat-label>Percentual (%)</mat-label><input matInput formControlName="percent" inputmode="decimal" placeholder="10" />
          @if (form.controls.percent.invalid) { <mat-error>Entre 0,01 e 100</mat-error> }</mat-form-field>
        <mat-form-field><mat-label>Sobre</mat-label>
          <mat-select formControlName="categories" multiple>@for (c of cats; track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }</mat-select>
          <mat-hint>Vazio = todos os tipos de honorário</mat-hint></mat-form-field>
        <mat-form-field><mat-label>Descrição (opcional)</mat-label><input matInput formControlName="description" maxlength="200" placeholder="Ex.: Captação de clientes" /></mat-form-field>
        <mat-checkbox formControlName="responsibleOnly">Somente processos em que o integrante é o advogado responsável</mat-checkbox>
        <mat-checkbox formControlName="active">Regra ativa</mat-checkbox>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="ruleForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
})
export class CommissionRuleDialogComponent {
  readonly r = inject<CommissionRule | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CommissionRuleDialogComponent>);
  private readonly service = inject(FeesService);
  readonly staff = toSignal(inject(ApiService).get<EmployeeOption[]>('/employees/options').pipe(catchError(() => of([]))), { initialValue: [] as EmployeeOption[] });
  readonly cats = FEE_CATEGORIES;
  readonly labels = CATEGORY_LABELS;
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    beneficiaryUid: [this.r?.beneficiaryUid ?? '', Validators.required],
    percent: [this.r ? String(this.r.percentBp / 100).replace('.', ',') : '', [Validators.required, Validators.pattern(/^\d{1,3}([.,]\d{1,2})?$/)]],
    categories: [(this.r && this.r.categories.length !== FEE_CATEGORIES.length ? this.r.categories : []) as FinanceCategory[]],
    description: [this.r?.description ?? ''],
    responsibleOnly: [this.r?.responsibleOnly ?? false],
    active: [this.r?.active ?? true],
  });

  save(): void {
    const v = this.form.getRawValue();
    const bp = Math.round(Number(v.percent.replace(',', '.')) * 100);
    if (this.form.invalid || !(bp >= 1 && bp <= 10000)) {
      this.form.controls.percent.setErrors({ range: true });
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.service.saveRule(this.r?.id ?? null, {
      beneficiaryUid: v.beneficiaryUid, percentBp: bp, categories: v.categories, description: v.description.trim() || null,
      clientId: this.r?.clientId ?? null, caseId: this.r?.caseId ?? null, responsibleOnly: v.responsibleOnly, active: v.active,
    }).subscribe({ next: (x) => this.ref.close(x), error: () => this.saving.set(false) });
  }
}
