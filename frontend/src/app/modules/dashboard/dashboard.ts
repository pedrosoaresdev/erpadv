import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ChartConfiguration } from 'chart.js';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { Dashboard } from '../../core/models/models';
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
    MatButtonModule, MatButtonToggleModule, MatIconModule, PageHeaderComponent, StatCardComponent,
    ChartComponent, LoadingComponent, EmptyStateComponent,
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
        <button mat-icon-button (click)="load()" aria-label="Atualizar"><mat-icon>refresh</mat-icon></button>
      </app-page-header>

      @if (loading() && !data()) {
        <app-loading />
      } @else if (data(); as d) {
        <section class="kpis">
          <app-stat-card label="Clientes" [value]="d.clients.total" icon="groups" [accent]="true"
            [hint]="d.clients.active + ' ativos · ' + d.clients.newInPeriod + ' novos no período'" />
          <app-stat-card label="Processos" [value]="d.cases.total" icon="gavel"
            [hint]="d.cases.newInPeriod + ' novos no período'" tone="success" />
          <app-stat-card label="Em andamento" [value]="d.cases.inProgress" icon="pending_actions"
            [hint]="d.cases.closed + ' encerrados · ' + d.cases.archived + ' arquivados'" />
          <app-stat-card label="Clientes inadimplentes" [value]="d.clients.defaulting" icon="report"
            [hint]="d.clients.defaulting ? 'Requer atenção do financeiro' : 'Nenhuma pendência'" [tone]="d.clients.defaulting ? 'danger' : 'success'" />
        </section>

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
    .pending { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 8px; }
    .pending-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text-muted);
      border: 1px dashed var(--app-border); padding: 6px 10px; border-radius: 8px; }
    .pending-item mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--app-gold); }
    .charts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-top: 8px; }
    .span-2 { grid-column: span 2; }
    @media (max-width: 1200px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } .charts { grid-template-columns: 1fr 1fr; } }
    @media (max-width: 760px) { .kpis, .charts { grid-template-columns: 1fr; } .span-2 { grid-column: auto; } }
  `,
})
export class DashboardComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);

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
    this.api.get<Dashboard>('/dashboard', { from: iso(from), to: iso(today) }).subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }
}

