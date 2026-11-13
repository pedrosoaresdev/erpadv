import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import { saveBlob } from '../finance/finance.service';
import {
  BillingSettings, CYCLE_LABELS, INVOICE_LABELS, InvoiceStatus, MODULE_LABELS, PlanInfo, SUBSCRIPTION_LABELS, SubscriptionInvoice,
  SubscriptionView, SupportTicket, TICKET_LABELS, TicketStatus, formatBytes, formatMb,
} from '../settings/office.service';
import { TicketThreadComponent } from '../settings/support-page';
import { PlatformMetrics, PlatformService, SubscriptionRequest } from './platform.service';

// ===================================================================== diálogo: assinatura do escritório

@Component({
  selector: 'app-subscription-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, CentsPipe, BrDatePipe],
  template: `
    <h2 mat-dialog-title>Assinatura · {{ data.officeName }}</h2>
    <mat-dialog-content>
      @if (view(); as v) {
        <p class="muted small">Atual: {{ v.planName || v.planCode || '—' }} · {{ v.status ? subLabels[v.status] : 'sem assinatura (escritório legado, todos os módulos)' }}
          · {{ v.priceCents !== null ? (v.priceCents | cents) : 'sem preço' }} · próxima fatura {{ v.nextInvoiceDate | brDate }}</p>
      }
      <div class="grid">
        <mat-form-field><mat-label>Plano</mat-label>
          <mat-select [(ngModel)]="form.planCode">@for (p of plans(); track p.code) { <mat-option [value]="p.code">{{ p.name }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field><mat-label>Ciclo</mat-label>
          <mat-select [(ngModel)]="form.billingCycle"><mat-option value="MENSAL">Mensal</mat-option><mat-option value="ANUAL">Anual</mat-option></mat-select>
        </mat-form-field>
        <mat-form-field><mat-label>Situação</mat-label>
          <mat-select [(ngModel)]="form.subscriptionStatus">
            @for (s of statuses; track s) { <mat-option [value]="s">{{ subLabels[s] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field><mat-label>Preço negociado (R$, vazio = do plano)</mat-label><input matInput [(ngModel)]="price" inputmode="decimal" /></mat-form-field>
        <mat-form-field><mat-label>Fim da avaliação</mat-label><input matInput type="date" [(ngModel)]="form.trialEndsAt" /></mat-form-field>
        <mat-form-field><mat-label>Próxima fatura</mat-label><input matInput type="date" [(ngModel)]="form.nextInvoiceDate" /></mat-form-field>
        <mat-form-field><mat-label>Carência após vencimento (dias)</mat-label><input matInput type="number" min="0" max="90" [(ngModel)]="form.graceDays" /></mat-form-field>
        <mat-form-field><mat-label>Limite de usuários (vazio = do plano)</mat-label><input matInput type="number" min="1" [(ngModel)]="form.maxUsers" /></mat-form-field>
        <mat-form-field><mat-label>Armazenamento em MB (vazio = do plano)</mat-label><input matInput type="number" min="100" [(ngModel)]="form.maxStorageMb" /></mat-form-field>
        <mat-form-field class="c3"><mat-label>E-mail de cobrança</mat-label><input matInput type="email" [(ngModel)]="form.billingEmail" /></mat-form-field>
      </div>
      <p class="muted small">“Suspensa” bloqueia o acesso da equipe; voltar para “Ativa” libera. Faturas são emitidas na data da próxima fatura pela rotina diária.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving() || !form.planCode"><mat-icon>save</mat-icon>Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 12px; }
    .c3 { grid-column: 1 / -1; }
    .small { font-size: 12px; }
    @media (max-width: 700px) { .grid { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class SubscriptionDialogComponent {
  readonly data = inject<{ officeId: string; officeName: string }>(MAT_DIALOG_DATA);
  private readonly service = inject(PlatformService);
  private readonly ref = inject(MatDialogRef<SubscriptionDialogComponent>);
  readonly subLabels = SUBSCRIPTION_LABELS as Record<string, string>;
  readonly statuses = Object.keys(SUBSCRIPTION_LABELS);
  readonly view = signal<SubscriptionView | null>(null);
  readonly plans = signal<PlanInfo[]>([]);
  readonly saving = signal(false);
  price = '';
  form: SubscriptionRequest = {
    planCode: null, billingCycle: 'MENSAL', priceOverrideCents: null, graceDays: 10, maxUsers: null, maxStorageMb: null,
    billingEmail: null, subscriptionStatus: 'ATIVA', nextInvoiceDate: null, trialEndsAt: null,
  };

  constructor() {
    this.service.plans().subscribe((p) => this.plans.set(p));
    this.service.subscription(this.data.officeId).subscribe((v) => {
      this.view.set(v);
      this.form = {
        planCode: v.planCode, billingCycle: v.billingCycle ?? 'MENSAL', priceOverrideCents: null, graceDays: v.graceDays ?? 10,
        maxUsers: null, maxStorageMb: null, billingEmail: v.billingEmail, subscriptionStatus: v.status ?? 'ATIVA',
        nextInvoiceDate: v.nextInvoiceDate, trialEndsAt: v.trialEndsAt,
      };
    });
  }

  save(): void {
    this.saving.set(true);
    const body: SubscriptionRequest = {
      ...this.form,
      priceOverrideCents: this.price.trim() ? parseMoneyToCents(this.price) : null,
      maxUsers: this.form.maxUsers || null,
      maxStorageMb: this.form.maxStorageMb || null,
      billingEmail: this.form.billingEmail?.trim() || null,
      nextInvoiceDate: this.form.nextInvoiceDate || null,
      trialEndsAt: this.form.trialEndsAt || null,
    };
    this.service.updateSubscription(this.data.officeId, body).subscribe({
      next: (v) => this.ref.close(v),
      error: () => this.saving.set(false),
    });
  }
}

// ===================================================================== diálogo: baixa de fatura

@Component({
  selector: 'app-payment-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, CentsPipe],
  template: `
    <h2 mat-dialog-title>Registrar pagamento · {{ inv.number }}</h2>
    <mat-dialog-content>
      <p class="muted small">{{ inv.officeName }} · valor da fatura {{ inv.amountCents | cents }}. Registre somente pagamentos conferidos.</p>
      <div class="grid">
        <mat-form-field><mat-label>Data do pagamento</mat-label><input matInput type="date" [(ngModel)]="paidDate" /></mat-form-field>
        <mat-form-field><mat-label>Valor recebido (R$)</mat-label><input matInput [(ngModel)]="amount" inputmode="decimal" /></mat-form-field>
        <mat-form-field><mat-label>Forma</mat-label>
          <mat-select [(ngModel)]="method">
            @for (m of methods; track m) { <mat-option [value]="m">{{ m }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="c3"><mat-label>Observação</mat-label><input matInput [(ngModel)]="note" maxlength="500" /></mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving() || !paidDate"><mat-icon>check</mat-icon>Confirmar pagamento</button>
    </mat-dialog-actions>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 12px; }
    .c3 { grid-column: 1 / -1; }
    .small { font-size: 12px; }
    @media (max-width: 700px) { .grid { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class PaymentDialogComponent {
  readonly inv = inject<SubscriptionInvoice>(MAT_DIALOG_DATA);
  private readonly service = inject(PlatformService);
  private readonly ref = inject(MatDialogRef<PaymentDialogComponent>);
  readonly methods = ['Pix', 'Boleto', 'Transferência', 'Cartão', 'Dinheiro', 'Outro'];
  readonly saving = signal(false);
  paidDate = new Date().toISOString().slice(0, 10);
  amount = centsToInput(this.inv.amountCents);
  method = 'Pix';
  note = '';

  save(): void {
    this.saving.set(true);
    this.service.pay(this.inv.id, { paidDate: this.paidDate, amountCents: parseMoneyToCents(this.amount), method: this.method, note: this.note.trim() || null })
      .subscribe({ next: (i) => this.ref.close(i), error: () => this.saving.set(false) });
  }
}

// ===================================================================== aba: faturas

@Component({
  selector: 'app-platform-invoices',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, EmptyStateComponent, BrDatePipe, CentsPipe],
  template: `
    <section class="card">
      <div class="toolbar">
        <mat-button-toggle-group [ngModel]="status()" (ngModelChange)="status.set($event); load()" aria-label="Situação da fatura">
          <mat-button-toggle [value]="null">Todas</mat-button-toggle>
          <mat-button-toggle value="ABERTA">Em aberto</mat-button-toggle>
          <mat-button-toggle value="VENCIDA">Vencidas</mat-button-toggle>
          <mat-button-toggle value="PAGA">Pagas</mat-button-toggle>
        </mat-button-toggle-group>
        <span class="spacer"></span>
        <button mat-stroked-button (click)="run()" [disabled]="busy()"><mat-icon>play_arrow</mat-icon>Rodar cobrança agora</button>
      </div>
      @if (items().length === 0) {
        <app-empty-state icon="receipt" title="Nenhuma fatura" text="As faturas são emitidas na data de cobrança de cada assinatura (rotina diária às 06:00)." />
      } @else {
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Fatura</th><th>Escritório</th><th>Período</th><th>Vencimento</th><th>Valor</th><th>Situação</th><th></th></tr></thead>
            <tbody>
              @for (i of items(); track i.id) {
                <tr>
                  <td class="mono">{{ i.number }}<div class="muted small">{{ i.planName }} · {{ cycleLabels[i.cycle] }}</div></td>
                  <td>{{ i.officeName }}</td>
                  <td class="muted small">{{ i.periodStart | brDate }} a {{ i.periodEnd | brDate }}</td>
                  <td>{{ i.dueDate | brDate }}</td>
                  <td class="mono">{{ i.amountCents | cents }}</td>
                  <td><span class="badge" [class.success]="i.status === 'PAGA'" [class.danger]="i.status === 'VENCIDA'" [class.gold]="i.status === 'ABERTA'">{{ labels[i.status] }}</span>
                    @if (i.paidDate) { <div class="muted small">{{ i.paidDate | brDate }} · {{ i.paymentMethod }} · {{ i.markedByName }}</div> }
                    @if (i.cancelReason) { <div class="muted small">{{ i.cancelReason }}</div> }</td>
                  <td class="actions">
                    <button mat-icon-button [matMenuTriggerFor]="m" aria-label="Ações da fatura"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #m="matMenu" xPosition="before">
                      <button mat-menu-item (click)="pdf(i)"><mat-icon>picture_as_pdf</mat-icon>Baixar PDF</button>
                      @if (i.status === 'ABERTA' || i.status === 'VENCIDA') {
                        <button mat-menu-item (click)="pay(i)"><mat-icon>check_circle</mat-icon>Registrar pagamento</button>
                        <button mat-menu-item class="danger" (click)="cancel(i)"><mat-icon>cancel</mat-icon>Cancelar fatura</button>
                      }
                    </mat-menu>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: `
    .toolbar { display: flex; gap: 12px; align-items: center; padding: 14px 16px; flex-wrap: wrap; border-bottom: 1px solid var(--app-border); }
    .small { font-size: 12px; }
    .actions { text-align: right; width: 56px; }
    .danger { color: var(--app-danger); }
  `,
})
export class PlatformInvoicesComponent {
  private readonly service = inject(PlatformService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly labels = INVOICE_LABELS;
  readonly cycleLabels = CYCLE_LABELS as Record<string, string>;
  readonly items = signal<SubscriptionInvoice[]>([]);
  readonly status = signal<InvoiceStatus | null>(null);
  readonly busy = signal(false);

  constructor() {
    this.load();
  }

  load(): void {
    this.service.invoices(null, this.status()).subscribe((l) => this.items.set(l));
  }

  run(): void {
    this.busy.set(true);
    this.service.runBilling().subscribe({
      next: (r) => { this.busy.set(false); this.toast.success(`Cobrança executada: ${r.actions} ação(ões).`); this.load(); },
      error: () => this.busy.set(false),
    });
  }

  pdf(i: SubscriptionInvoice): void {
    this.service.invoicePdf(i.id).subscribe((r) => saveBlob(r, `${i.number}.pdf`));
  }

  pay(i: SubscriptionInvoice): void {
    this.dialog.open(PaymentDialogComponent, { data: i, width: '620px', maxWidth: '96vw' }).afterClosed().subscribe((r) => {
      if (r) { this.toast.success('Pagamento registrado.'); this.load(); }
    });
  }

  cancel(i: SubscriptionInvoice): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Cancelar fatura', message: `Cancelar a fatura ${i.number} de ${i.officeName}?`, confirmLabel: 'Cancelar fatura', danger: true,
        reasonLabel: 'Motivo', reasonRequired: true },
      width: '460px',
    }).afterClosed().subscribe((reason?: string) => {
      if (typeof reason === 'string' && reason) {
        this.service.cancelInvoice(i.id, reason).subscribe(() => { this.toast.success('Fatura cancelada.'); this.load(); });
      }
    });
  }
}

// ===================================================================== aba: planos

@Component({
  selector: 'app-platform-plans',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSlideToggleModule],
  template: `
    <p class="muted intro">Defina preços, limites e módulos de cada plano. Planos sem preço não geram faturas. Mudanças valem para todos os escritórios do plano
      (exceto limites e preços negociados individualmente na assinatura).</p>
    <div class="plans">
      @for (p of drafts; track p.code) {
        <section class="card plan">
          <div class="grid">
            <mat-form-field><mat-label>Código</mat-label><input matInput [value]="p.code" disabled /></mat-form-field>
            <mat-form-field><mat-label>Nome</mat-label><input matInput [(ngModel)]="p.name" maxlength="60" /></mat-form-field>
            <mat-form-field class="c2"><mat-label>Descrição</mat-label><input matInput [(ngModel)]="p.description" maxlength="300" /></mat-form-field>
            <mat-form-field><mat-label>Preço mensal (R$)</mat-label><input matInput [(ngModel)]="money[p.code].m" inputmode="decimal" placeholder="sem preço" /></mat-form-field>
            <mat-form-field><mat-label>Preço anual (R$)</mat-label><input matInput [(ngModel)]="money[p.code].y" inputmode="decimal" placeholder="sem preço" /></mat-form-field>
            <mat-form-field><mat-label>Usuários (vazio = ilimitado)</mat-label><input matInput type="number" min="1" [(ngModel)]="p.maxUsers" /></mat-form-field>
            <mat-form-field><mat-label>Armazenamento (MB)</mat-label><input matInput type="number" min="100" [(ngModel)]="p.maxStorageMb" /></mat-form-field>
            <mat-form-field><mat-label>Dias de avaliação</mat-label><input matInput type="number" min="0" max="90" [(ngModel)]="p.trialDays" /></mat-form-field>
            <mat-form-field><mat-label>Ordem</mat-label><input matInput type="number" [(ngModel)]="p.sortOrder" /></mat-form-field>
          </div>
          <div class="mods">
            @for (m of moduleCodes; track m) {
              <mat-checkbox [checked]="p.modules.includes(m)" (change)="toggle(p, m, $event.checked)">{{ moduleLabels[m] }}</mat-checkbox>
            }
          </div>
          <div class="bar">
            <mat-slide-toggle [(ngModel)]="p.active">Disponível</mat-slide-toggle>
            <mat-slide-toggle [(ngModel)]="p.highlight">Recomendado</mat-slide-toggle>
            <span class="spacer"></span>
            <button mat-flat-button (click)="save(p)" [disabled]="busy()"><mat-icon>save</mat-icon>Salvar {{ p.name }}</button>
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .intro { margin: 0 0 12px; }
    .plans { display: flex; flex-direction: column; gap: 16px; }
    .plan { padding: 16px 20px; }
    .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0 12px; }
    .c2 { grid-column: span 2; }
    .mods { display: flex; flex-wrap: wrap; gap: 4px 16px; margin-bottom: 8px; }
    .bar { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; }
    @media (max-width: 900px) { .grid { grid-template-columns: minmax(0, 1fr); } .c2 { grid-column: auto; } }
  `,
})
export class PlatformPlansComponent {
  private readonly service = inject(PlatformService);
  private readonly toast = inject(ToastService);
  readonly moduleLabels = MODULE_LABELS;
  readonly moduleCodes = Object.keys(MODULE_LABELS);
  readonly busy = signal(false);
  drafts: PlanInfo[] = [];
  money: Record<string, { m: string; y: string }> = {};

  constructor() {
    this.load();
  }

  load(): void {
    this.service.plans().subscribe((list) => {
      this.drafts = list.map((p) => ({ ...p, modules: [...p.modules] }));
      this.money = Object.fromEntries(list.map((p) => [p.code, { m: centsToInput(p.monthlyPriceCents), y: centsToInput(p.yearlyPriceCents) }]));
    });
  }

  toggle(p: PlanInfo, m: string, on: boolean): void {
    p.modules = on ? [...p.modules, m] : p.modules.filter((x) => x !== m);
  }

  save(p: PlanInfo): void {
    const m = this.money[p.code];
    this.busy.set(true);
    const { id: _id, ...rest } = p;
    this.service.savePlan({
      ...rest,
      monthlyPriceCents: m.m.trim() ? parseMoneyToCents(m.m) : null,
      yearlyPriceCents: m.y.trim() ? parseMoneyToCents(m.y) : null,
      maxUsers: p.maxUsers || null,
      maxStorageMb: p.maxStorageMb || null,
    }).subscribe({
      next: () => { this.busy.set(false); this.toast.success(`Plano ${p.name} salvo.`); this.load(); },
      error: () => this.busy.set(false),
    });
  }
}

// ===================================================================== aba: dados de cobrança

@Component({
  selector: 'app-platform-billing-settings',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  template: `
    <section class="card pad">
      <p class="muted">Dados que aparecem nas faturas e na página “Plano e Assinatura” dos escritórios. Não informe senhas nem dados de acesso bancário.</p>
      @if (s(); as f) {
        <div class="grid">
          <mat-form-field><mat-label>Favorecido (razão social)</mat-label><input matInput [(ngModel)]="f.issuerName" maxlength="160" /></mat-form-field>
          <mat-form-field><mat-label>CNPJ/CPF do favorecido</mat-label><input matInput [(ngModel)]="f.issuerDocument" maxlength="20" /></mat-form-field>
          <mat-form-field><mat-label>Chave Pix</mat-label><input matInput [(ngModel)]="f.pixKey" maxlength="140" /></mat-form-field>
          <mat-form-field><mat-label>E-mail para comprovantes</mat-label><input matInput type="email" [(ngModel)]="f.contactEmail" maxlength="160" /></mat-form-field>
          <mat-form-field class="c2"><mat-label>Dados bancários (banco, agência, conta)</mat-label><textarea matInput rows="3" [(ngModel)]="f.bankInfo" maxlength="600"></textarea></mat-form-field>
          <mat-form-field class="c2"><mat-label>Instruções de pagamento</mat-label><textarea matInput rows="3" [(ngModel)]="f.instructions" maxlength="1500"></textarea></mat-form-field>
        </div>
        <div class="bar"><span class="spacer"></span><button mat-flat-button (click)="save(f)" [disabled]="busy()"><mat-icon>save</mat-icon>Salvar</button></div>
      }
    </section>
  `,
  styles: `
    .pad { padding: 16px 20px; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
    .c2 { grid-column: 1 / -1; }
    .bar { display: flex; }
    @media (max-width: 700px) { .grid { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class PlatformBillingSettingsComponent {
  private readonly service = inject(PlatformService);
  private readonly toast = inject(ToastService);
  readonly s = signal<BillingSettings | null>(null);
  readonly busy = signal(false);

  constructor() {
    this.service.billingSettings().subscribe((s) => this.s.set({ ...s }));
  }

  save(f: BillingSettings): void {
    this.busy.set(true);
    this.service.saveBillingSettings(f).subscribe({
      next: (s) => { this.s.set({ ...s }); this.busy.set(false); this.toast.success('Dados de cobrança salvos.'); },
      error: () => this.busy.set(false),
    });
  }
}

// ===================================================================== aba: suporte

@Component({
  selector: 'app-platform-support',
  imports: [FormsModule, MatButtonToggleModule, EmptyStateComponent, BrDatePipe, TicketThreadComponent],
  template: `
    <div class="layout">
      <section class="card list">
        <div class="toolbar">
          <mat-button-toggle-group [ngModel]="status()" (ngModelChange)="status.set($event); load()" aria-label="Situação">
            <mat-button-toggle [value]="null">Todos</mat-button-toggle>
            <mat-button-toggle value="ABERTO">Abertos</mat-button-toggle>
            <mat-button-toggle value="RESOLVIDO">Resolvidos</mat-button-toggle>
          </mat-button-toggle-group>
        </div>
        @for (t of items(); track t.id) {
          <button class="item" [class.active]="selected()?.id === t.id" (click)="selected.set(t)">
            <div class="row"><span class="mono small muted">{{ t.number }}</span>
              @if (t.awaitingPlatform && t.status !== 'RESOLVIDO') { <span class="badge danger">Aguardando resposta</span> }
              @else { <span class="badge" [class.success]="t.status === 'RESOLVIDO'">{{ labels[t.status] }}</span> }</div>
            <strong>{{ t.subject }}</strong>
            <span class="muted small">{{ t.officeName }} · {{ t.lastMessageAt | brDate: true }}</span>
          </button>
        } @empty {
          <app-empty-state icon="support_agent" title="Nenhum chamado" />
        }
      </section>
      <section class="card">
        @if (selected(); as t) {
          <app-ticket-thread [ticket]="t" [platform]="true" [busy]="busy()" (reply)="send(t, $event)" />
        } @else {
          <app-empty-state icon="forum" title="Selecione um chamado" />
        }
      </section>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: minmax(260px, 360px) minmax(0, 1fr); gap: 16px; align-items: start; }
    .list { display: flex; flex-direction: column; max-height: 72vh; overflow: auto; }
    .toolbar { padding: 12px; border-bottom: 1px solid var(--app-border); }
    .item { text-align: left; border: 0; border-bottom: 1px solid var(--app-border); background: transparent; color: inherit; font: inherit;
      padding: 12px 14px; display: flex; flex-direction: column; gap: 3px; cursor: pointer; }
    .item:hover, .item.active { background: var(--app-surface-2); }
    .row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .small { font-size: 12px; }
    @media (max-width: 900px) { .layout { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class PlatformSupportComponent {
  private readonly service = inject(PlatformService);
  readonly labels = TICKET_LABELS;
  readonly items = signal<SupportTicket[]>([]);
  readonly selected = signal<SupportTicket | null>(null);
  readonly status = signal<TicketStatus | null>(null);
  readonly busy = signal(false);

  constructor() {
    this.load();
  }

  load(): void {
    this.service.tickets(this.status()).subscribe((l) => {
      this.items.set(l);
      const id = this.selected()?.id;
      if (id) this.selected.set(l.find((t) => t.id === id) ?? null);
    });
  }

  send(t: SupportTicket, r: { message: string | null; status: TicketStatus | null }): void {
    this.busy.set(true);
    this.service.reply(t.id, r.message, r.status).subscribe({
      next: (u) => { this.busy.set(false); this.selected.set(u); this.load(); },
      error: () => this.busy.set(false),
    });
  }
}

// ===================================================================== aba: monitoramento

@Component({
  selector: 'app-platform-monitoring',
  imports: [MatButtonModule, MatIconModule, StatCardComponent, EmptyStateComponent, BrDatePipe, CentsPipe],
  template: `
    @if (m(); as d) {
      <div class="stats">
        <app-stat-card label="Receita recorrente (MRR)" [value]="d.mrrCents | cents" icon="trending_up" [accent]="true" hint="assinaturas ativas e em atraso" />
        <app-stat-card label="Faturas em aberto" [value]="d.openInvoicesCents | cents" icon="receipt" />
        <app-stat-card label="Faturas vencidas" [value]="d.overdueInvoices" icon="warning" [tone]="d.overdueInvoices ? 'warning' : ''"
          [hint]="(d.overdueInvoicesCents | cents)" />
        <app-stat-card label="Escritórios" [value]="d.offices" icon="domain" [hint]="(d.byStatus['SUSPENDED'] || 0) + ' suspensos'" />
        <app-stat-card label="Usuários ativos" [value]="d.users" icon="group" [hint]="d.activeUsers7d + ' acessaram em 7 dias'" />
        <app-stat-card label="Armazenamento total" [value]="bytes(d.storageBytes)" icon="cloud" />
        <app-stat-card label="Chamados aguardando" [value]="d.ticketsAwaiting" icon="support_agent" [tone]="d.ticketsAwaiting ? 'warning' : ''" />
        <app-stat-card label="Saúde do sistema" [value]="d.health === 'UP' ? 'Operacional' : d.health" icon="monitor_heart"
          [tone]="d.health === 'UP' ? '' : 'warning'" [hint]="'no ar há ' + uptime(d.uptimeMinutes)" />
      </div>
      <div class="two">
        <section class="card pad">
          <h3>Assinaturas</h3>
          @for (e of entries(d.bySubscription); track e[0]) { <div class="kv"><span>{{ subLabels[e[0]] || 'Sem assinatura (legado)' }}</span><strong>{{ e[1] }}</strong></div> }
          <h3>Planos</h3>
          @for (e of entries(d.byPlan); track e[0]) { <div class="kv"><span>{{ e[0] }}</span><strong>{{ e[1] }}</strong></div> }
        </section>
        <section class="card pad">
          <div class="head"><h3>Alertas</h3><button mat-button (click)="load()"><mat-icon>refresh</mat-icon>Atualizar</button></div>
          @for (a of d.alerts; track $index) {
            <div class="alert"><mat-icon>{{ a.kind === 'STORAGE' ? 'cloud' : a.kind === 'TRIAL' ? 'hourglass_bottom' : 'sell' }}</mat-icon>
              <div><strong>{{ a.officeName }}</strong><div class="muted small">{{ a.message }}</div></div></div>
          } @empty {
            <app-empty-state icon="check_circle" title="Nenhum alerta" />
          }
          <p class="muted small">Gerado em {{ d.generatedAt | brDate: true }}</p>
        </section>
      </div>
    }
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 16px; }
    .two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 16px; align-items: start; }
    .pad { padding: 16px 20px; }
    h3 { margin: 8px 0; font-size: 15px; }
    .head { display: flex; justify-content: space-between; align-items: center; }
    .kv { display: flex; justify-content: space-between; padding: 6px 0; border-bottom: 1px solid var(--app-border); }
    .alert { display: flex; gap: 10px; align-items: flex-start; padding: 8px 0; border-bottom: 1px solid var(--app-border); }
    .alert mat-icon { color: var(--app-gold); }
    .small { font-size: 12px; }
    @media (max-width: 900px) { .two { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class PlatformMonitoringComponent {
  private readonly service = inject(PlatformService);
  readonly subLabels = SUBSCRIPTION_LABELS as Record<string, string>;
  readonly m = signal<PlatformMetrics | null>(null);
  readonly bytes = formatBytes;
  readonly mbFmt = formatMb;

  constructor() {
    this.load();
  }

  load(): void {
    this.service.monitoring().subscribe((d) => this.m.set(d));
  }

  entries(o: Record<string, number>): [string, number][] {
    return Object.entries(o);
  }

  uptime(min: number): string {
    if (min < 60) return `${min} min`;
    if (min < 1440) return `${Math.floor(min / 60)} h`;
    return `${Math.floor(min / 1440)} dia(s)`;
  }
}

/** Itens de navegação das abas do painel master. */
export const PLATFORM_TABS = ['escritorios', 'faturas', 'planos', 'suporte', 'monitoramento', 'cobranca'];
export const platformTabIndex = (aba: string | null) => Math.max(0, PLATFORM_TABS.indexOf(aba ?? ''));
