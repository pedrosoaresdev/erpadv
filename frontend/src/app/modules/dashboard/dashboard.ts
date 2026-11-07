import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { EmployeeOption } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { saveBlob } from '../finance/finance.service';
import { ClientFormDialogComponent } from '../clients/client-form-dialog';
import { CaseFormDialogComponent } from '../cases/case-form-dialog';
import { AppointmentDialogComponent } from '../calendar/appointment-dialog';
import { catchError, of } from 'rxjs';
import { RouterLink } from '@angular/router';
import { ChartConfiguration } from 'chart.js';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { Dashboard } from '../../core/models/models';
import { DelinquencyReport, FinanceSummary } from '../finance/finance.models';
import { FinanceService } from '../finance/finance.service';

const BRL0 = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 0 });
import { CHART_COLORS, ChartComponent } from '../../shared/components/chart';
import { EmptyStateComponent, LoadingComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';

type PeriodKey = '3m' | '6m' | '12m' | 'ytd';

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
const fmtMonth = (ym: string) => {
  const [y, m] = ym.split('-').map(Number);
  return `${MONTHS[m - 1]}/${String(y).slice(2)}`;
};
const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

@Component({
  selector: 'app-dashboard',
  imports: [
    RouterLink, FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatSelectModule, MatTooltipModule,
    PageHeaderComponent, StatCardComponent, ChartComponent, LoadingComponent, EmptyStateComponent,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Visão executiva" [title]="'Olá, ' + firstName()" subtitle="Indicadores do escritório no período selecionado.">
        <mat-button-toggle-group [value]="period()" (change)="setPeriod($event.value)" aria-label="Período" hideSingleSelectionIndicator>
          <mat-button-toggle value="3m">3 meses</mat-button-toggle>
          <mat-button-toggle value="6m">6 meses</mat-button-toggle>
          <mat-button-toggle value="12m">12 meses</mat-button-toggle>
          <mat-button-toggle value="ytd">Ano atual</mat-button-toggle>
        </mat-button-toggle-group>
        @if (lawyers().length) {
          <mat-select class="lawyer" [ngModel]="lawyer()" (ngModelChange)="setLawyer($event)" aria-label="Filtrar por advogado" placeholder="Advogado">
            <mat-option value="">Todo o escritório</mat-option>
            @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }
          </mat-select>
        }
        <button mat-icon-button (click)="load()" aria-label="Atualizar" matTooltip="Atualizar"><mat-icon>refresh</mat-icon></button>
        <button mat-stroked-button [matMenuTriggerFor]="exportMenu" [disabled]="exporting()"><mat-icon>download</mat-icon>Exportar</button>
        <mat-menu #exportMenu="matMenu" xPosition="before">
          <button mat-menu-item (click)="export('pdf')"><mat-icon>picture_as_pdf</mat-icon>PDF</button>
          <button mat-menu-item (click)="export('xlsx')"><mat-icon>table_view</mat-icon>Excel</button>
        </mat-menu>
      </app-page-header>

      <nav class="quick" aria-label="Atalhos">
        @if (auth.has('CLIENTS_WRITE')) { <button class="qa" (click)="newClient()"><mat-icon>person_add</mat-icon>Novo cliente</button> }
        @if (auth.has('CASES_WRITE')) { <button class="qa" (click)="newCase()"><mat-icon>post_add</mat-icon>Novo processo</button> }
        @if (auth.has('BOARDS_USE')) { <a class="qa" routerLink="/quadros"><mat-icon>add_task</mat-icon>Nova tarefa</a> }
        @if (auth.has('CALENDAR_WRITE')) { <button class="qa" (click)="newAppointment()"><mat-icon>event</mat-icon>Novo compromisso</button> }
        @if (auth.has('DEADLINES_READ')) { <a class="qa" routerLink="/prazos"><mat-icon>alarm</mat-icon>Prazos</a> }
      </nav>

      @if (loading() && !data()) {
        <app-loading />
      } @else if (data(); as d) {
        <section class="kpis">
          <app-stat-card label="Clientes" [value]="d.clients.total" icon="groups" [accent]="true"
            [hint]="d.clients.active + ' ativos · ' + d.clients.inactive + ' inativos · ' + d.clients.newInPeriod + ' novos ' + delta(d.clients.newInPeriod, d.previous.newClients)" />
          <app-stat-card label="Processos" [value]="d.cases.total" icon="gavel"
            [hint]="d.cases.newInPeriod + ' novos ' + delta(d.cases.newInPeriod, d.previous.newCases)" tone="success" />
          <app-stat-card label="Processos ativos" [value]="d.cases.inProgress" icon="pending_actions"
            [hint]="d.caseExtra.suspended + ' suspensos · ' + d.cases.closed + ' encerrados · ' + d.cases.archived + ' arquivados'" />
          <app-stat-card label="Clientes inadimplentes" [value]="d.clients.defaulting" icon="report"
            [hint]="d.clients.defaulting ? 'Requer atenção do financeiro' : 'Nenhuma pendência'" [tone]="d.clients.defaulting ? 'danger' : 'success'" />
        </section>

        <section class="kpis second">
          <a class="kpi-link" routerLink="/prazos"><app-stat-card label="Prazos que vencem hoje" [value]="d.deadlines.dueToday" icon="today"
            [tone]="d.deadlines.dueToday ? 'danger' : 'success'" [hint]="d.deadlines.urgent + ' urgentes nos próximos 3 dias'" /></a>
          <a class="kpi-link" routerLink="/prazos"><app-stat-card label="Prazos vencidos" [value]="d.deadlines.overdue" icon="event_busy"
            [tone]="d.deadlines.overdue ? 'danger' : 'success'" [hint]="d.deadlines.overdue ? 'Pendentes após o vencimento' : 'Nenhum prazo vencido'" /></a>
          <a class="kpi-link" routerLink="/prazos"><app-stat-card label="Prazos futuros" [value]="d.deadlines.upcoming" icon="event_upcoming"
            [hint]="d.deadlines.unvalidated + ' aguardando validação · ' + d.deadlines.completedInPeriod + ' cumpridos no período'" /></a>
          <a class="kpi-link" routerLink="/agenda"><app-stat-card label="Audiências (30 dias)" [value]="d.hearings.next30Days" icon="gavel"
            hint="Próximas audiências agendadas" /></a>
          <a class="kpi-link" routerLink="/contratos"><app-stat-card label="Contratos ativos" [value]="d.contracts.active" icon="handshake"
            [hint]="d.contracts.expiring30Days + ' terminando em 30 dias · ' + d.contracts.drafts + ' rascunho(s)'" [tone]="d.contracts.expiring30Days ? 'warning' : ''" /></a>
        </section>

        <section class="kpis second">
          <a class="kpi-link" routerLink="/prazos"><app-stat-card label="Prazos nos próximos dias" [value]="d.deadlineWindows.next7Days" icon="date_range"
            [hint]="'3 dias: ' + d.deadlineWindows.next3Days + ' · 15 dias: ' + d.deadlineWindows.next15Days" /></a>
          <a class="kpi-link" routerLink="/agenda"><app-stat-card label="Compromissos (7 dias)" [value]="d.agendaNext7Days" icon="calendar_month"
            hint="Agenda da equipe nos próximos 7 dias" /></a>
          <a class="kpi-link" routerLink="/processos"><app-stat-card label="Aguardando movimentação" [value]="d.caseExtra.awaitingMovement" icon="hourglass_empty"
            [hint]="'Ativos sem movimentação há ' + d.caseExtra.staleDays + '+ dias'" [tone]="d.caseExtra.awaitingMovement ? 'warning' : 'success'" /></a>
          <a class="kpi-link" routerLink="/prazos"><app-stat-card label="Prazos cumpridos no período" [value]="d.deadlines.completedInPeriod" icon="task_alt"
            [hint]="'Período anterior: ' + d.previous.deadlinesCompleted + ' ' + delta(d.deadlines.completedInPeriod, d.previous.deadlinesCompleted)" tone="success" /></a>
        </section>

        @if (finance(); as f) {
          <section class="kpis second">
            <a class="kpi-link" routerLink="/financeiro"><app-stat-card label="Faturamento do mês" [value]="money(f.currentMonthIncomeCents)" icon="payments" [accent]="true"
              [hint]="'Recebido no período: ' + money(f.receivedCents)" /></a>
            <a class="kpi-link" routerLink="/financeiro"><app-stat-card label="Honorários recebidos" [value]="money(f.feesReceivedCents)" icon="gavel"
              [hint]="'Pendentes: ' + money(f.feesPendingCents)" tone="success" /></a>
            <a class="kpi-link" routerLink="/financeiro"><app-stat-card label="Despesas pagas" [value]="money(f.paidCents)" icon="receipt_long"
              [hint]="'Rentabilidade: ' + margin(f) + '% · resultado ' + money(f.balanceCents) + (prevFinance() ? ' ' + delta(f.receivedCents, prevFinance()!.receivedCents) + ' na receita' : '')"
              [tone]="f.balanceCents >= 0 ? 'success' : 'danger'" /></a>
            <a class="kpi-link" routerLink="/financeiro"><app-stat-card label="Honorários/receitas em atraso" [value]="money(f.overdueReceivableCents)" icon="report"
              [hint]="f.overdueReceivableCount + ' título(s) vencido(s)'" [tone]="f.overdueReceivableCount ? 'danger' : 'success'" /></a>
          </section>
        }

        <section class="pending">
          @for (p of pendingList(); track p.key) {
            <div class="pending-item"><mat-icon>schedule</mat-icon><span>{{ p.label }}</span></div>
          }
        </section>

        <section class="charts">
          <div class="card card-pad span-2">
            <div class="card-title"><mat-icon>trending_up</mat-icon>Crescimento de clientes</div>
            <app-chart [config]="growthChart()" [height]="280" />
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>donut_large</mat-icon>Processos por área</div>
            @if (d.casesByArea.length) {
              <app-chart [config]="areaChart()" [height]="280" />
            } @else {
              <app-empty-state icon="gavel" title="Sem processos" text="Cadastre processos para ver a distribuição." />
            }
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>stacked_bar_chart</mat-icon>Processos por situação</div>
            <app-chart [config]="statusChart()" [height]="260" />
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>bar_chart</mat-icon>Novos processos por mês</div>
            <app-chart [config]="newCasesChart()" [height]="260" />
          </div>
          @if (finance()) {
            <div class="card card-pad span-2">
              <div class="card-title"><mat-icon>account_balance</mat-icon>Receitas, despesas e evolução financeira</div>
              <app-chart [config]="financeChart()" [height]="280" />
            </div>
            <div class="card card-pad">
              <div class="card-title"><mat-icon>hourglass_bottom</mat-icon>Honorários em atraso</div>
              @if (agingChart(); as a) { <app-chart [config]="a" [height]="280" /> }
            </div>
          }
          <div class="card card-pad">
            <div class="card-title"><mat-icon>event</mat-icon>Audiências por mês</div>
            <app-chart [config]="hearingsChart()" [height]="260" />
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>fact_check</mat-icon>Prazos cumpridos × não cumpridos</div>
            <app-chart [config]="deadlineTrendChart()" [height]="260" />
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>account_balance</mat-icon>Processos por tribunal</div>
            @if (d.casesByCourt.length) {
              <app-chart [config]="courtChart()" [height]="260" />
            } @else {
              <app-empty-state icon="account_balance" title="Sem processos" />
            }
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>hub</mat-icon>Origem dos novos clientes</div>
            @if (d.clientsBySource.length) {
              <app-chart [config]="sourceChart()" [height]="260" />
            } @else {
              <app-empty-state icon="hub" title="Nenhum cliente novo no período" />
            }
          </div>
          <div class="card card-pad span-2">
            <div class="card-title"><mat-icon>groups</mat-icon>Desempenho da equipe</div>
            @if (d.team.length) {
              <div class="table-wrap">
                <table class="data compact">
                  <thead><tr><th>Advogado</th><th>Processos ativos</th><th>Prazos cumpridos</th><th>Prazos vencidos</th><th>Cumprimento</th></tr></thead>
                  <tbody>
                    @for (t of d.team; track t.uid) {
                      <tr>
                        <td>{{ t.name }}</td><td class="mono">{{ t.activeCases }}</td><td class="mono">{{ t.deadlinesCompleted }}</td>
                        <td class="mono" [class.bad]="t.deadlinesOverdue">{{ t.deadlinesOverdue }}</td>
                        <td><span class="bar"><span [style.width.%]="rate(t)"></span></span> <span class="mono small">{{ rate(t) }}%</span></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <app-empty-state icon="badge" title="Sem advogados ativos" />
            }
          </div>
          <div class="card card-pad">
            <div class="card-title"><mat-icon>workspace_premium</mat-icon>Carteira ativa por advogado</div>
            @if (d.productivity.length) {
              <app-chart [config]="productivityChart()" [height]="260" />
            } @else {
              <app-empty-state icon="badge" title="Sem advogados ativos" />
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
    .kpis.second { margin-top: 16px; }
    .kpi-link { text-decoration: none; color: inherit; display: block; border-radius: var(--app-radius); }
    .pending { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 8px; }
    .pending-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text-muted);
      border: 1px dashed var(--app-border); padding: 6px 10px; border-radius: 8px; }
    .pending-item mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--app-gold); }
    .charts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 8px; }
    .span-2 { grid-column: span 2; }
    .lawyer { width: 210px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 9px 12px; box-sizing: border-box; }
    .quick { display: flex; gap: 10px; flex-wrap: wrap; margin: -6px 0 18px; }
    .qa { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 999px; border: 1px solid var(--app-border);
      background: var(--app-surface); color: var(--app-text); font: inherit; font-size: 13px; font-weight: 600; cursor: pointer; text-decoration: none; }
    .qa mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--app-gold); }
    .qa:hover, .qa:focus-visible { border-color: var(--app-gold); background: var(--app-gold-soft); outline: none; }
    .compact td, .compact th { padding: 9px 12px; }
    .bad { color: var(--app-danger); font-weight: 700; }
    .bar { display: inline-block; width: 90px; height: 6px; border-radius: 4px; background: var(--app-border); overflow: hidden; vertical-align: middle; }
    .bar span { display: block; height: 100%; background: var(--app-success); }
    .small { font-size: 12px; }
    @media (max-width: 1200px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } .charts { grid-template-columns: 1fr 1fr; } }
    @media (max-width: 760px) { .kpis, .charts { grid-template-columns: 1fr; } .span-2 { grid-column: auto; } }
  `,
})
export class DashboardComponent {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);
  private readonly financeApi = inject(FinanceService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly lawyers = toSignal(this.api.get<EmployeeOption[]>('/employees/lawyers', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly lawyer = signal('');
  readonly exporting = signal(false);
  readonly prevFinance = signal<FinanceSummary | null>(null);
  private range: { from: string; to: string } = { from: '', to: '' };

  /** Variação percentual em relação ao período anterior (ex.: "▲ 20%"). */
  delta(current: number, previous: number): string {
    if (!previous) return current ? '(novo)' : '';
    const pct = Math.round(((current - previous) / previous) * 100);
    return pct === 0 ? '(= anterior)' : `(${pct > 0 ? '▲' : '▼'} ${Math.abs(pct)}%)`;
  }

  margin(f: FinanceSummary): number {
    return f.receivedCents ? Math.round((f.balanceCents / f.receivedCents) * 100) : 0;
  }

  rate(t: { deadlinesCompleted: number; deadlinesOverdue: number }): number {
    const total = t.deadlinesCompleted + t.deadlinesOverdue;
    return total ? Math.round((t.deadlinesCompleted / total) * 100) : 100;
  }

  setLawyer(uid: string): void {
    this.lawyer.set(uid);
    this.load();
  }

  export(format: 'pdf' | 'xlsx'): void {
    this.exporting.set(true);
    this.api.getBlob('/dashboard/export', { ...this.range, lawyerUid: this.lawyer() || null, format }).subscribe({
      next: (r) => { saveBlob(r, `dashboard.${format}`); this.exporting.set(false); },
      error: () => { this.exporting.set(false); this.toast.error('Não foi possível exportar o painel.'); },
    });
  }

  newClient(): void {
    this.dialog.open(ClientFormDialogComponent, { data: null, width: '860px', maxWidth: '96vw' }).afterClosed()
      .subscribe((c?: { id: string }) => { if (c?.id) void this.router.navigate(['/clientes', c.id]); });
  }

  newCase(): void {
    this.dialog.open(CaseFormDialogComponent, { data: {}, width: '900px', maxWidth: '96vw' }).afterClosed()
      .subscribe((c?: { id: string }) => { if (c?.id) void this.router.navigate(['/processos', c.id]); });
  }

  newAppointment(): void {
    this.dialog.open(AppointmentDialogComponent, { data: { date: new Date() }, width: '820px', maxWidth: '96vw' }).afterClosed()
      .subscribe((r) => { if (r && r !== 'deleted') { this.toast.success('Compromisso agendado.'); this.load(); } });
  }

  readonly deadlineTrendChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.deadlineTrend ?? [];
    return {
      type: 'bar',
      data: {
        labels: items.map((p) => fmtMonth(p.month)),
        datasets: [
          { label: 'Cumpridos', data: items.map((p) => p.completed), backgroundColor: '#2f855a', borderRadius: 6 },
          { label: 'Não cumpridos', data: items.map((p) => p.missed), backgroundColor: '#b42318', borderRadius: 6 },
        ],
      },
      options: { plugins: { legend: { position: 'bottom' } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } }, x: { grid: { display: false } } } },
    };
  });

  readonly courtChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.casesByCourt ?? [];
    return {
      type: 'bar',
      data: { labels: items.map((i) => i.label), datasets: [{ label: 'Processos', data: items.map((i) => i.value), backgroundColor: CHART_COLORS[0], borderRadius: 6 }] },
      options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { grid: { display: false } } } },
    };
  });

  readonly sourceChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.clientsBySource ?? [];
    return {
      type: 'doughnut',
      data: { labels: items.map((i) => i.label), datasets: [{ data: items.map((i) => i.value), backgroundColor: CHART_COLORS, borderWidth: 0 }] },
      options: { cutout: '62%', plugins: { legend: { position: 'bottom' } } } as ChartConfiguration['options'],
    };
  });

  readonly period = signal<PeriodKey>('12m');
  readonly data = signal<Dashboard | null>(null);
  readonly loading = signal(false);
  readonly firstName = computed(
    () =>
      this.auth
        .displayName()
        .split(/\s+/)
        .find((w) => w && !/^(dr|dra|sr|sra|prof|profa)\.?$/i.test(w)) ?? 'bem-vindo(a)',
  );
  readonly pendingList = computed(() =>
    Object.entries(this.data()?.pendingModules ?? {}).map(([key, label]) => ({ key, label: `${label}: indicadores em implantação` })),
  );

  readonly growthChart = computed<ChartConfiguration>(() => {
    const d = this.data();
    const points = d?.clientGrowth ?? [];
    return {
      type: 'line',
      data: {
        labels: points.map((p) => fmtMonth(p.month)),
        datasets: [
          { label: 'Total de clientes', data: points.map((p) => p.cumulative ?? 0), borderColor: CHART_COLORS[0],
            backgroundColor: 'rgba(18,48,90,.10)', fill: true, tension: 0.35, pointRadius: 3 },
          { label: 'Novos no mês', data: points.map((p) => p.value), borderColor: CHART_COLORS[1],
            backgroundColor: CHART_COLORS[1], tension: 0.35, pointRadius: 3 },
        ],
      },
      options: { scales: { y: { beginAtZero: true, ticks: { precision: 0 } }, x: { grid: { display: false } } } },
    };
  });

  readonly areaChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.casesByArea ?? [];
    return {
      type: 'doughnut',
      data: {
        labels: items.map((i) => i.label),
        datasets: [{ data: items.map((i) => i.value), backgroundColor: CHART_COLORS, borderWidth: 0 }],
      },
      options: { cutout: '68%', plugins: { legend: { position: 'bottom' } } } as ChartConfiguration['options'],
    };
  });

  readonly statusChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.casesByStatus ?? [];
    return {
      type: 'bar',
      data: {
        labels: items.map((i) => i.label),
        datasets: [{ label: 'Processos', data: items.map((i) => i.value), backgroundColor: CHART_COLORS[2], borderRadius: 6 }],
      },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } }, x: { grid: { display: false } } } },
    };
  });

  readonly newCasesChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.newCasesByMonth ?? [];
    return {
      type: 'bar',
      data: {
        labels: items.map((p) => fmtMonth(p.month)),
        datasets: [{ label: 'Novos processos', data: items.map((p) => p.value), backgroundColor: CHART_COLORS[0], borderRadius: 6 }],
      },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } }, x: { grid: { display: false } } } },
    };
  });

  readonly finance = signal<FinanceSummary | null>(null);
  readonly delinquency = signal<DelinquencyReport | null>(null);
  readonly money = (cents: number) => BRL0.format(cents / 100);

  readonly financeChart = computed<ChartConfiguration>(() => {
    const m = this.finance()?.monthly ?? [];
    return {
      type: 'bar',
      data: {
        labels: m.map((p) => fmtMonth(p.month)),
        datasets: [
          { type: 'bar', label: 'Receitas', data: m.map((p) => p.incomeCents / 100), backgroundColor: '#2f855a', borderRadius: 6 },
          { type: 'bar', label: 'Despesas', data: m.map((p) => p.expenseCents / 100), backgroundColor: '#b42318', borderRadius: 6 },
          { type: 'line', label: 'Resultado acumulado', data: m.map((p) => p.cumulativeCents / 100), borderColor: '#c5a46d', backgroundColor: '#c5a46d', tension: 0.3 },
        ],
      } as ChartConfiguration['data'],
      options: { scales: { y: { ticks: { callback: (v) => BRL0.format(Number(v)) } }, x: { grid: { display: false } } } },
    };
  });

  readonly agingChart = computed<ChartConfiguration | null>(() => {
    const d = this.delinquency();
    if (!d) return null;
    return {
      type: 'bar',
      data: { labels: d.aging.map((a) => a.label), datasets: [{ label: 'Em atraso', data: d.aging.map((a) => a.amountCents / 100), backgroundColor: ['#f0b55b', '#e58a3a', '#c2410c', '#b42318'], borderRadius: 6 }] },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { callback: (v) => BRL0.format(Number(v)) } }, x: { grid: { display: false } } } },
    };
  });

  readonly hearingsChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.hearings.byMonth ?? [];
    return {
      type: 'bar',
      data: {
        labels: items.map((p) => fmtMonth(p.month)),
        datasets: [{ label: 'Audiências', data: items.map((p) => p.value), backgroundColor: '#b42318', borderRadius: 6 }],
      },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } }, x: { grid: { display: false } } } },
    };
  });

  readonly productivityChart = computed<ChartConfiguration>(() => {
    const items = this.data()?.productivity ?? [];
    return {
      type: 'bar',
      data: {
        labels: items.map((i) => i.label),
        datasets: [{ label: 'Processos ativos', data: items.map((i) => i.value), backgroundColor: CHART_COLORS[1], borderRadius: 6 }],
      },
      options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } }, y: { grid: { display: false } } } },
    };
  });

  constructor() {
    this.load();
  }

  setPeriod(value: PeriodKey): void {
    this.period.set(value);
    this.load();
  }

  load(): void {
    const today = new Date();
    let from: Date;
    switch (this.period()) {
      case '3m': from = new Date(today.getFullYear(), today.getMonth() - 2, 1); break;
      case '6m': from = new Date(today.getFullYear(), today.getMonth() - 5, 1); break;
      case 'ytd': from = new Date(today.getFullYear(), 0, 1); break;
      default: from = new Date(today.getFullYear(), today.getMonth() - 11, 1);
    }
    this.loading.set(true);
    this.range = { from: iso(from), to: iso(today) };
    if (this.auth.has('FINANCE_DASHBOARD_VIEW')) {
      const end = new Date(today.getFullYear(), today.getMonth() + 1, 0);
      this.financeApi.dashboard(iso(from), iso(end)).subscribe({ next: (f) => this.finance.set(f), error: () => this.finance.set(null) });
      // Mesmo intervalo imediatamente anterior, para comparação de receita.
      const span = end.getTime() - from.getTime();
      const prevEnd = new Date(from.getTime() - 86_400_000);
      const prevFrom = new Date(prevEnd.getTime() - span);
      this.financeApi.dashboard(iso(prevFrom), iso(prevEnd)).subscribe({ next: (f) => this.prevFinance.set(f), error: () => this.prevFinance.set(null) });
      if (this.auth.has('FINANCE_READ')) this.financeApi.delinquency().subscribe((d) => this.delinquency.set(d));
    }
    this.api.get<Dashboard>('/dashboard', { ...this.range, lawyerUid: this.lawyer() || null }).subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}

