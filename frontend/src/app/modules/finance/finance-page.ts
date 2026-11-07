import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink } from '@angular/router';
import { ChartConfiguration } from 'chart.js';
import { AuthService } from '../../core/auth/auth.service';
import { CHART_COLORS, ChartComponent } from '../../shared/components/chart';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { DelinquencyReport, FinanceScope, FinanceSummary } from './finance.models';
import { FinanceService } from './finance.service';
import { TransactionsTableComponent } from './transactions-table';

type Period = 'mes' | '3m' | '6m' | '12m' | 'ano';
const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
export const fmtMonth = (ym: string): string => {
  const [y, m] = ym.split('-').map(Number);
  return `${MONTHS[m - 1]}/${String(y).slice(2)}`;
};
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 0 });
const money = (cents: number) => BRL.format(cents / 100);

export function periodRange(p: Period, today = new Date()): { from: string; to: string } {
  const endOfMonth = new Date(today.getFullYear(), today.getMonth() + 1, 0);
  const back = { mes: 0, '3m': 2, '6m': 5, '12m': 11, ano: today.getMonth() }[p];
  return { from: iso(new Date(today.getFullYear(), today.getMonth() - back, 1)), to: iso(endOfMonth) };
}

@Component({
  selector: 'app-finance-page',
  imports: [
    RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressBarModule, MatTabsModule,
    PageHeaderComponent, StatCardComponent, EmptyStateComponent, ChartComponent, TransactionsTableComponent, CentsPipe, BrDatePipe,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão financeira" title="Financeiro" subtitle="Contas a receber e a pagar, fluxo de caixa, inadimplência e DRE gerencial.">
        <mat-button-toggle-group [value]="period()" (change)="setPeriod($event.value)" hideSingleSelectionIndicator aria-label="Período">
          <mat-button-toggle value="mes">Mês</mat-button-toggle>
          <mat-button-toggle value="3m">3 meses</mat-button-toggle>
          <mat-button-toggle value="6m">6 meses</mat-button-toggle>
          <mat-button-toggle value="12m">12 meses</mat-button-toggle>
          <mat-button-toggle value="ano">Ano</mat-button-toggle>
        </mat-button-toggle-group>
      </app-page-header>

      <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false" (selectedTabChange)="onTab($event.tab.textLabel)">
        <mat-tab label="Visão geral">
          @if (loading() && !summary()) { <mat-progress-bar mode="indeterminate" /> }
          @if (summary(); as s) {
            <section class="kpis">
              <app-stat-card label="Recebido no período" [value]="money(s.receivedCents)" icon="call_received" [accent]="true"
                [hint]="'Honorários: ' + money(s.feesReceivedCents)" />
              <app-stat-card label="Pago no período" [value]="money(s.paidCents)" icon="call_made" [hint]="'A pagar no período: ' + money(s.payableCents)" />
              <app-stat-card label="Saldo do período" [value]="money(s.balanceCents)" icon="account_balance"
                [tone]="s.balanceCents >= 0 ? 'success' : 'danger'" [hint]="s.balanceCents >= 0 ? 'Resultado positivo' : 'Resultado negativo'" />
              <app-stat-card label="A receber em atraso" [value]="money(s.overdueReceivableCents)" icon="report"
                [tone]="s.overdueReceivableCount ? 'danger' : 'success'" [hint]="s.overdueReceivableCount + ' título(s) vencido(s)'" />
            </section>
            <section class="charts">
              <div class="card card-pad span-2">
                <div class="card-title"><mat-icon>bar_chart</mat-icon>Receitas e despesas realizadas</div>
                <app-chart [config]="flowChart()" [height]="280" />
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>show_chart</mat-icon>Evolução do resultado acumulado</div>
                <app-chart [config]="cumulativeChart()" [height]="280" />
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>donut_small</mat-icon>Receitas por categoria</div>
                @if (s.incomeByCategory.length) { <app-chart [config]="categoryChart('income')" [height]="260" /> }
                @else { <app-empty-state icon="savings" title="Sem receitas no período" /> }
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>donut_small</mat-icon>Despesas por categoria</div>
                @if (s.expenseByCategory.length) { <app-chart [config]="categoryChart('expense')" [height]="260" /> }
                @else { <app-empty-state icon="receipt_long" title="Sem despesas no período" /> }
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>insights</mat-icon>Projeção (próximos 6 meses)</div>
                <app-chart [config]="projectionChart()" [height]="260" />
                <p class="muted small">Baseada nas contas em aberto já lançadas (recebimentos e pagamentos previstos).</p>
              </div>
            </section>
          }
        </mat-tab>

        <mat-tab label="Contas a receber">
          <div class="tab"><app-transactions-table direction="RECEITA" [refreshKey]="refresh()" /></div>
        </mat-tab>
        <mat-tab label="Contas a pagar">
          <div class="tab"><app-transactions-table direction="DESPESA" [refreshKey]="refresh()" /></div>
        </mat-tab>

        <mat-tab label="Inadimplência">
          <div class="tab">
            @if (delinquency(); as d) {
              <section class="kpis">
                <app-stat-card label="Total em atraso" [value]="money(d.totalCents)" icon="report" [tone]="d.totalCents ? 'danger' : 'success'" [hint]="d.count + ' título(s) · ' + d.clients.length + ' cliente(s)'" />
                @for (a of d.aging; track a.label) {
                  <app-stat-card [label]="a.label" [value]="money(a.amountCents)" icon="hourglass_bottom" [hint]="a.count + ' título(s)'" />
                }
              </section>
              <div class="card">
                @if (!d.clients.length) {
                  <app-empty-state icon="verified" title="Nenhum cliente inadimplente" text="Todas as contas a receber estão em dia." />
                } @else {
                  <div class="table-wrap">
                    <table class="data">
                      <thead><tr><th>Cliente</th><th class="r">Em atraso</th><th>Títulos</th><th>Vencimento mais antigo</th><th>Maior atraso</th></tr></thead>
                      <tbody>
                        @for (c of d.clients; track c.clientName) {
                          <tr>
                            <td>@if (c.clientId) { <a [routerLink]="['/clientes', c.clientId]">{{ c.clientName }}</a> } @else { {{ c.clientName }} }</td>
                            <td class="r mono strong">{{ c.totalCents | cents }}</td>
                            <td>{{ c.count }}</td>
                            <td>{{ c.oldestDueDate | brDate }}</td>
                            <td><span class="badge" [class.danger]="c.maxDaysOverdue > 30" [class.warning]="c.maxDaysOverdue <= 30">{{ c.maxDaysOverdue }} dias</span></td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </div>
              <p class="muted small">Clientes com contas vencidas são marcados automaticamente como inadimplentes no CRM; a marcação é removida ao regularizar.</p>
            } @else { <mat-progress-bar mode="indeterminate" /> }
          </div>
        </mat-tab>

        <mat-tab label="DRE gerencial">
          <div class="tab">
            @if (summary(); as s) {
              <div class="card dre">
                <div class="dre-head">
                  <div><div class="card-title">Demonstração do resultado — regime de caixa</div>
                    <div class="muted small">{{ s.from | brDate }} a {{ s.to | brDate }}</div></div>
                  @if (s.dre.marginPercent !== null) { <span class="badge" [class.success]="s.dre.resultCents >= 0" [class.danger]="s.dre.resultCents < 0">Margem {{ s.dre.marginPercent }}%</span> }
                </div>
                <table class="data">
                  <tbody>
                    @for (l of s.dre.lines; track $index) {
                      <tr [class]="'lvl' + l.level + ' ' + l.kind">
                        <td>{{ l.label }}</td>
                        <td class="r mono" [class.neg]="l.amountCents < 0">{{ l.amountCents | cents }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </mat-tab>

        @if (isPartner()) {
          <mat-tab label="Pessoal (sócio)">
            <div class="tab">
              <div class="notice"><mat-icon>lock</mat-icon>Finanças pessoais: visíveis apenas para você e nunca incluídas nos relatórios do escritório.</div>
              <h3 class="sub">Recebimentos pessoais</h3>
              <app-transactions-table direction="RECEITA" scope="PESSOAL" />
              <h3 class="sub">Despesas pessoais</h3>
              <app-transactions-table direction="DESPESA" scope="PESSOAL" />
            </div>
          </mat-tab>
        }
      </mat-tab-group>
    </div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; margin: 16px 0; }
    .charts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .span-2 { grid-column: span 2; }
    @media (max-width: 1100px) { .charts { grid-template-columns: 1fr; } .span-2 { grid-column: auto; } }
    .tab { margin-top: 16px; display: flex; flex-direction: column; gap: 14px; }
    .small { font-size: 12px; }
    .strong { font-weight: 600; }
    .r { text-align: right; }
    .dre { padding: 8px 0; max-width: 820px; }
    .dre-head { display: flex; justify-content: space-between; align-items: flex-start; padding: 12px 20px; }
    tr.lvl0 td { font-weight: 700; background: var(--app-surface-2); }
    tr.lvl1 td:first-child { padding-left: 32px; font-weight: 500; }
    tr.lvl2 td:first-child { padding-left: 56px; color: var(--app-text-muted); font-size: 13px; }
    tr.result td { background: var(--app-gold-soft); font-size: 15px; }
    .neg { color: var(--app-danger); }
    .notice { display: flex; gap: 8px; align-items: center; padding: 12px 16px; border-radius: 10px; background: var(--app-gold-soft); }
    .sub { font-size: 15px; margin-top: 6px; }
  `,
})
export class FinancePageComponent {
  private readonly service = inject(FinanceService);
  private readonly auth = inject(AuthService);
  readonly money = money;

  readonly period = signal<Period>('6m');
  readonly summary = signal<FinanceSummary | null>(null);
  readonly delinquency = signal<DelinquencyReport | null>(null);
  readonly loading = signal(false);
  readonly refresh = signal(0);
  readonly isPartner = computed(() => ['SOCIO_ADMIN', 'MASTER'].includes(this.auth.me()?.role ?? ''));

  readonly flowChart = computed<ChartConfiguration>(() => {
    const m = this.summary()?.monthly ?? [];
    return {
      type: 'bar',
      data: {
        labels: m.map((x) => fmtMonth(x.month)),
        datasets: [
          { label: 'Receitas', data: m.map((x) => x.incomeCents / 100), backgroundColor: '#2f855a', borderRadius: 6 },
          { label: 'Despesas', data: m.map((x) => x.expenseCents / 100), backgroundColor: '#b42318', borderRadius: 6 },
          { label: 'A receber (previsto)', data: m.map((x) => x.forecastIncomeCents / 100), backgroundColor: 'rgba(47,133,90,.25)', borderRadius: 6 },
          { label: 'A pagar (previsto)', data: m.map((x) => x.forecastExpenseCents / 100), backgroundColor: 'rgba(180,35,24,.22)', borderRadius: 6 },
        ],
      },
      options: { scales: { y: { beginAtZero: true, ticks: { callback: (v) => BRL.format(Number(v)) } }, x: { grid: { display: false } } },
        plugins: { tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${BRL.format(Number(c.raw))}` } } } },
    };
  });

  readonly cumulativeChart = computed<ChartConfiguration>(() => {
    const m = this.summary()?.monthly ?? [];
    return {
      type: 'line',
      data: {
        labels: m.map((x) => fmtMonth(x.month)),
        datasets: [{ label: 'Resultado acumulado', data: m.map((x) => x.cumulativeCents / 100), borderColor: CHART_COLORS[0],
          backgroundColor: 'rgba(197,164,109,.18)', fill: true, tension: 0.3, pointRadius: 3 }],
      },
      options: { plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => BRL.format(Number(c.raw)) } } },
        scales: { y: { ticks: { callback: (v) => BRL.format(Number(v)) } }, x: { grid: { display: false } } } },
    };
  });

  readonly projectionChart = computed<ChartConfiguration>(() => {
    const p = this.summary()?.projection ?? [];
    return {
      type: 'bar',
      data: {
        labels: p.map((x) => fmtMonth(x.month)),
        datasets: [
          { label: 'A receber', data: p.map((x) => x.forecastIncomeCents / 100), backgroundColor: '#2f855a', borderRadius: 6 },
          { label: 'A pagar', data: p.map((x) => x.forecastExpenseCents / 100), backgroundColor: '#b42318', borderRadius: 6 },
        ],
      },
      options: { scales: { y: { beginAtZero: true, ticks: { callback: (v) => BRL.format(Number(v)) } }, x: { grid: { display: false } } },
        plugins: { tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${BRL.format(Number(c.raw))}` } } } },
    };
  });

  constructor() {
    this.load();
  }

  categoryChart(kind: 'income' | 'expense'): ChartConfiguration {
    const s = this.summary();
    const items = kind === 'income' ? s?.incomeByCategory ?? [] : s?.expenseByCategory ?? [];
    return {
      type: 'doughnut',
      data: { labels: items.map((i) => i.label), datasets: [{ data: items.map((i) => i.amountCents / 100), backgroundColor: CHART_COLORS, borderWidth: 0 }] },
      options: { cutout: '64%', plugins: { legend: { position: 'bottom' }, tooltip: { callbacks: { label: (c) => `${c.label}: ${BRL.format(Number(c.raw))}` } } } } as ChartConfiguration['options'],
    };
  }

  setPeriod(p: Period): void {
    this.period.set(p);
    this.load();
  }

  onTab(label: string): void {
    if (label === 'Inadimplência') this.service.delinquency().subscribe((d) => this.delinquency.set(d));
    if (label === 'Visão geral' || label === 'DRE gerencial') this.load();
  }

  load(): void {
    const { from, to } = periodRange(this.period());
    this.loading.set(true);
    this.service.summary('ESCRITORIO' as FinanceScope, from, to).subscribe({
      next: (s) => { this.summary.set(s); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}
