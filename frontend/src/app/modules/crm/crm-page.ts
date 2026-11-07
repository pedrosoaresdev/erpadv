import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragPlaceholder, CdkDropList, CdkDropListGroup } from '@angular/cdk/drag-drop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ChartConfiguration } from 'chart.js';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeOption } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ChartComponent } from '../../shared/components/chart';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { CrmMetrics, CrmService, Lead, LeadStage, STAGES } from './crm.service';
import { LeadConvertDialogComponent, LeadDetailDialogComponent, LeadFormDialogComponent } from './lead-dialogs';

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 0 });

@Component({
  selector: 'app-crm-page',
  imports: [FormsModule, CdkDropListGroup, CdkDropList, CdkDrag, CdkDragPlaceholder, MatButtonModule, MatIconModule, MatProgressBarModule,
    MatSelectModule, MatTabsModule, MatTooltipModule, PageHeaderComponent, StatCardComponent, EmptyStateComponent, ChartComponent,
    CentsPipe, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Comercial" title="CRM" subtitle="Funil de captação: do primeiro contato ao contrato, com pontuação automática e próximas ações.">
        <button mat-flat-button (click)="create()"><mat-icon>person_add</mat-icon>Nova oportunidade</button>
      </app-page-header>

      @if (metrics(); as m) {
        <div class="stats">
          <app-stat-card label="Pipeline em aberto" [value]="money(m.pipelineValueCents)" icon="filter_alt" [accent]="true" [hint]="m.openLeads + ' oportunidades'" />
          <app-stat-card label="Pipeline ponderado" [value]="money(m.weightedPipelineCents)" icon="balance" hint="valor × chance da etapa" />
          <app-stat-card label="Taxa de conversão" [value]="m.conversionRate + '%'" icon="trending_up" [hint]="m.wonCount + ' ganhas · ' + m.lostCount + ' perdidas'" />
          <app-stat-card label="Leads quentes" [value]="m.hotLeads" icon="local_fire_department" [tone]="m.overdueActions ? 'danger' : ''"
            [hint]="m.overdueActions ? m.overdueActions + ' com ação atrasada' : 'ações em dia'" />
        </div>
      }

      <div class="filters">
        <div class="search">
          <mat-icon>search</mat-icon>
          <input type="search" placeholder="Nome, empresa, e-mail ou telefone" [ngModel]="term()" (ngModelChange)="term.set($event)" aria-label="Buscar oportunidades" />
        </div>
        <mat-select class="fsel" [ngModel]="owner()" (ngModelChange)="owner.set($event)" aria-label="Responsável" placeholder="Responsável">
          <mat-option value="">Todos os responsáveis</mat-option>
          <mat-option value="__me">Somente minhas</mat-option>
          @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
        </mat-select>
        <mat-select class="fsel" [ngModel]="temp()" (ngModelChange)="temp.set($event)" aria-label="Temperatura" placeholder="Temperatura">
          <mat-option value="">Todas as temperaturas</mat-option>
          <mat-option value="QUENTE">Quentes</mat-option>
          <mat-option value="MORNO">Mornas</mat-option>
          <mat-option value="FRIO">Frias</mat-option>
        </mat-select>
        <button class="chip" [class.on]="onlyOverdue()" (click)="onlyOverdue.set(!onlyOverdue())"><mat-icon>alarm</mat-icon>Ação atrasada</button>
      </div>

      <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false">
        <mat-tab label="Funil">
          @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
          <div class="pipeline" cdkDropListGroup>
            @for (s of stages; track s.key) {
              <section class="stage" [class.closed]="!s.open" [style.--sc]="s.color">
                <header>
                  <span class="sname">{{ s.label }}</span>
                  <span class="count">{{ inStage(s.key).length }}</span>
                  <span class="spacer"></span>
                  <span class="sum mono">{{ money(stageValue(s.key)) }}</span>
                </header>
                <div class="leads" cdkDropList [cdkDropListData]="s.key" (cdkDropListDropped)="drop($event)">
                  @for (l of inStage(s.key); track l.id) {
                    <article class="lead" cdkDrag [cdkDragData]="l" [cdkDragDisabled]="!!l.convertedClientId" (click)="open(l)" tabindex="0" (keydown.enter)="open(l)">
                      <div class="ph" *cdkDragPlaceholder></div>
                      <div class="row top">
                        <strong class="lname">{{ l.name }}</strong>
                        <span class="temp" [class]="l.temperature" [matTooltip]="'Pontuação ' + l.score + '/100'">{{ l.score }}</span>
                      </div>
                      @if (l.subject || l.company) { <div class="muted small ell">{{ l.company ? l.company + ' · ' : '' }}{{ l.subject }}</div> }
                      <div class="lmeta">
                        <span class="mono">{{ l.estimatedValueCents | cents }}</span>
                        <span class="badge">{{ l.sourceLabel }}</span>
                        @if (l.convertedClientId) { <span class="badge success">cliente</span> }
                      </div>
                      @if (l.nextAction && s.open) {
                        <div class="next" [class.late]="l.nextActionOverdue"><mat-icon>event_upcoming</mat-icon>
                          {{ l.nextAction }}@if (l.nextActionDate) { · {{ l.nextActionDate | brDate }} }</div>
                      }
                      <div class="foot muted small"><span>{{ l.ownerName }}</span><span class="spacer"></span><span>{{ l.daysInStage }}d na etapa</span></div>
                    </article>
                  } @empty {
                    <div class="empty muted small">{{ s.open ? 'Arraste oportunidades para cá' : 'Solte aqui para ' + (s.key === 'GANHO' ? 'converter' : 'marcar como perdida') }}</div>
                  }
                </div>
              </section>
            }
          </div>
        </mat-tab>

        <mat-tab label="Lista">
          <div class="card tab-card">
            @if (filtered().length === 0) {
              <app-empty-state icon="person_search" title="Nenhuma oportunidade" text="Cadastre contatos interessados e acompanhe até a contratação." />
            } @else {
              <div class="table-wrap">
                <table class="data">
                  <thead><tr><th>Oportunidade</th><th>Etapa</th><th>Valor</th><th>Pontuação</th><th>Próxima ação</th><th>Responsável</th></tr></thead>
                  <tbody>
                    @for (l of sortedList(); track l.id) {
                      <tr class="clickable" (click)="open(l)">
                        <td><strong>{{ l.name }}</strong><div class="muted small">{{ l.subject || l.sourceLabel }}</div></td>
                        <td><span class="badge" [style.background]="stageColor(l.stage)" style="color:#fff">{{ l.stageLabel }}</span></td>
                        <td class="mono">{{ l.estimatedValueCents | cents }}</td>
                        <td><span class="temp" [class]="l.temperature">{{ l.score }}</span></td>
                        <td [class.late]="l.nextActionOverdue">{{ l.nextAction || '—' }}@if (l.nextActionDate) { <div class="small">{{ l.nextActionDate | brDate }}</div> }</td>
                        <td>{{ l.ownerName }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </mat-tab>

        <mat-tab label="Métricas">
          @if (metrics(); as m) {
            <div class="metrics">
              <div class="card card-pad">
                <div class="card-title"><mat-icon>filter_alt</mat-icon>Funil por etapa</div>
                <app-chart [config]="funnelChart()" [height]="260" />
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>show_chart</mat-icon>Entradas × ganhos por mês</div>
                <app-chart [config]="monthsChart()" [height]="260" />
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>hub</mat-icon>Origens que mais convertem</div>
                <table class="data compact">
                  <thead><tr><th>Origem</th><th>Leads</th><th>Ganhos</th><th>Conversão</th></tr></thead>
                  <tbody>@for (s of m.sources; track s.source) { <tr><td>{{ s.label }}</td><td>{{ s.leads }}</td><td>{{ s.won }}</td><td>{{ s.conversionRate }}%</td></tr> }</tbody>
                </table>
              </div>
              <div class="card card-pad">
                <div class="card-title"><mat-icon>emoji_events</mat-icon>Desempenho por responsável</div>
                <table class="data compact">
                  <thead><tr><th>Responsável</th><th>Em aberto</th><th>Ganhos</th><th>Valor ganho</th></tr></thead>
                  <tbody>@for (o of m.owners; track o.uid) { <tr><td>{{ o.name }}</td><td>{{ o.open }}</td><td>{{ o.won }}</td><td class="mono">{{ o.wonValueCents | cents }}</td></tr> }</tbody>
                </table>
                <p class="muted small">Tempo médio até o ganho: <strong>{{ m.avgDaysToWin }} dias</strong></p>
                @if (m.lostReasons.length) {
                  <div class="card-title lost"><mat-icon>report</mat-icon>Principais motivos de perda</div>
                  @for (r of m.lostReasons; track r) { <div class="reason">{{ r }}</div> }
                }
              </div>
            </div>
          }
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 16px; margin-bottom: 16px; }
    .filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
    .search { flex: 1 1 260px; display: flex; align-items: center; gap: 8px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 0 12px; height: 38px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .fsel { width: 200px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; }
    .chip { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--app-border); background: var(--app-surface); color: var(--app-text); border-radius: 999px; padding: 6px 12px; cursor: pointer; font: inherit; font-size: 13px; }
    .chip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .chip.on { background: var(--app-gold-soft); border-color: var(--app-gold); }
    .pipeline { display: flex; gap: 12px; overflow-x: auto; padding: 16px 0 12px; align-items: flex-start; }
    .stage { flex: 0 0 260px; background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 14px; display: flex; flex-direction: column; max-height: calc(100vh - 340px); }
    .stage.closed { flex-basis: 220px; opacity: .95; }
    .stage header { display: flex; align-items: center; gap: 6px; padding: 10px 12px; border-top: 4px solid var(--sc); border-radius: 14px 14px 0 0; }
    .sname { font-weight: 700; font-size: 13px; }
    .count { font-size: 11px; padding: 1px 7px; border-radius: 999px; background: var(--app-surface); border: 1px solid var(--app-border); }
    .sum { font-size: 12px; color: var(--app-text-muted); }
    .leads { padding: 4px 10px 12px; display: flex; flex-direction: column; gap: 8px; overflow-y: auto; min-height: 70px; flex: 1; }
    .lead { background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; cursor: pointer; box-shadow: var(--app-shadow); border-left: 3px solid var(--sc); }
    .lead:hover, .lead:focus-visible { box-shadow: var(--app-shadow-lg); outline: none; }
    .ph { min-height: 70px; border: 2px dashed var(--app-gold); border-radius: 10px; background: var(--app-gold-soft); }
    .top { gap: 6px; }
    .lname { flex: 1; min-width: 0; font-size: 14px; }
    .ell { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
    .lmeta { display: flex; gap: 6px; align-items: center; margin-top: 8px; font-size: 13px; flex-wrap: wrap; }
    .next { display: flex; gap: 4px; align-items: center; font-size: 12px; margin-top: 6px; color: var(--app-text-muted); }
    .next mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .next.late, .late { color: var(--app-danger); }
    .foot { display: flex; margin-top: 8px; }
    .temp { font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 999px; }
    .temp.QUENTE { background: var(--app-danger-bg); color: var(--app-danger); }
    .temp.MORNO { background: var(--app-warning-bg); color: var(--app-warning); }
    .temp.FRIO { background: var(--app-info-bg); color: var(--app-info); }
    .empty { text-align: center; padding: 18px 6px; border: 1px dashed var(--app-border); border-radius: 10px; }
    .small { font-size: 12px; }
    .tab-card { margin-top: 16px; }
    .clickable { cursor: pointer; }
    .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: 16px; margin-top: 16px; }
    @media (max-width: 520px) { .metrics { grid-template-columns: 1fr; } }
    .compact td, .compact th { padding: 8px 10px; }
    .lost { margin-top: 12px; }
    .reason { font-size: 13px; padding: 4px 0; border-bottom: 1px solid var(--app-border); text-transform: capitalize; }
  `,
})
export class CrmPageComponent {
  private readonly service = inject(CrmService);
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  readonly stages = STAGES;
  readonly staff = toSignal(this.api.get<EmployeeOption[]>('/employees/options'), { initialValue: [] });
  readonly leads = signal<Lead[]>([]);
  readonly metrics = signal<CrmMetrics | null>(null);
  readonly loading = signal(false);
  readonly term = signal('');
  readonly owner = signal('');
  readonly temp = signal('');
  readonly onlyOverdue = signal(false);

  readonly filtered = computed(() => {
    const t = this.term().trim().toLowerCase();
    const digits = t.replace(/\D/g, '');
    const me = this.auth.me()?.uid;
    return this.leads().filter((l) => {
      if (t && !([l.name, l.company, l.email, l.subject].some((x) => (x ?? '').toLowerCase().includes(t))
        || (digits.length >= 4 && (l.phone ?? '').replace(/\D/g, '').includes(digits)))) return false;
      if (this.owner() === '__me' && l.ownerUid !== me) return false;
      if (this.owner() && this.owner() !== '__me' && l.ownerUid !== this.owner()) return false;
      if (this.temp() && (l.temperature !== this.temp() || !STAGES.find((s) => s.key === l.stage)?.open)) return false;
      if (this.onlyOverdue() && !l.nextActionOverdue) return false;
      return true;
    });
  });

  readonly byStage = computed(() => {
    const map = new Map<LeadStage, Lead[]>();
    for (const l of this.filtered()) map.set(l.stage, [...(map.get(l.stage) ?? []), l]);
    // Dentro da etapa: mais quentes primeiro.
    map.forEach((list) => list.sort((a, b) => b.score - a.score));
    return map;
  });

  readonly sortedList = computed(() => [...this.filtered()].sort((a, b) => b.score - a.score));

  readonly funnelChart = computed<ChartConfiguration>(() => {
    const f = this.metrics()?.funnel ?? [];
    return {
      type: 'bar',
      data: {
        labels: f.map((x) => x.label),
        datasets: [{ label: 'Oportunidades', data: f.map((x) => x.count), backgroundColor: f.map((x) => this.stageColor(x.stage)), borderRadius: 6 }],
      },
      options: { indexAxis: 'y', plugins: { legend: { display: false } }, scales: { x: { beginAtZero: true, ticks: { precision: 0 } } } },
    };
  });

  readonly monthsChart = computed<ChartConfiguration>(() => {
    const months = this.metrics()?.months ?? [];
    const label = (ym: string) => { const [y, m] = ym.split('-'); return `${m}/${y.slice(2)}`; };
    return {
      type: 'bar',
      data: {
        labels: months.map((m) => label(m.month)),
        datasets: [
          { label: 'Novas oportunidades', data: months.map((m) => m.created), backgroundColor: '#7a8ca8', borderRadius: 6 },
          { label: 'Ganhas', data: months.map((m) => m.won), backgroundColor: '#2f855a', borderRadius: 6 },
        ],
      },
      options: { plugins: { legend: { position: 'bottom' } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } },
    };
  });

  constructor() {
    this.load();
    const leadId = this.route.snapshot.queryParamMap.get('lead');
    if (leadId) this.openId(leadId);
  }

  load(): void {
    this.loading.set(true);
    this.service.list().subscribe({
      next: (l) => { this.leads.set(l); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
    this.service.metrics(6).subscribe((m) => this.metrics.set(m));
  }

  money(cents: number): string {
    return BRL.format((cents ?? 0) / 100);
  }

  inStage(stage: LeadStage): Lead[] {
    return this.byStage().get(stage) ?? [];
  }

  stageValue(stage: LeadStage): number {
    return this.inStage(stage).reduce((sum, l) => sum + l.estimatedValueCents, 0);
  }

  stageColor(stage: LeadStage): string {
    return STAGES.find((s) => s.key === stage)?.color ?? '#7a8ca8';
  }

  create(): void {
    this.dialog.open(LeadFormDialogComponent, { data: null, width: '760px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((lead?: Lead) => {
        if (lead) {
          this.toast.success('Oportunidade cadastrada.');
          this.load();
        }
      });
  }

  open(l: Lead): void {
    this.openId(l.id);
  }

  private openId(id: string): void {
    this.dialog.open(LeadDetailDialogComponent, { data: id, maxWidth: '96vw' }).afterClosed().subscribe(() => this.load());
  }

  drop(event: CdkDragDrop<LeadStage>): void {
    const lead = event.item.data as Lead;
    const target = event.container.data;
    if (lead.stage === target) return;
    if (target === 'GANHO') {
      if (!this.auth.has('CLIENTS_WRITE')) {
        this.service.stage(lead.id, target).subscribe(() => this.load());
        return;
      }
      this.dialog.open(LeadConvertDialogComponent, { data: lead, width: '600px', maxWidth: '96vw' })
        .afterClosed().subscribe((r) => { if (r) { this.toast.success(`${lead.name} agora é cliente do escritório.`); this.load(); } });
      return;
    }
    if (target === 'PERDIDO') {
      this.dialog.open(ConfirmDialogComponent, {
        data: { title: 'Marcar como perdida', message: `Informe o motivo da perda de ${lead.name}.`, confirmLabel: 'Marcar perdida', danger: true,
          reasonLabel: 'Motivo (ex.: preço, escolheu outro escritório, sem viabilidade)', reasonRequired: true },
        width: '480px',
      }).afterClosed().subscribe((reason?: string) => {
        if (typeof reason === 'string' && reason) this.service.stage(lead.id, target, reason).subscribe(() => this.load());
      });
      return;
    }
    // Otimista: move já na tela e recarrega com a resposta do servidor.
    this.leads.set(this.leads().map((l) => (l.id === lead.id ? { ...l, stage: target } : l)));
    this.service.stage(lead.id, target).subscribe({ next: () => this.load(), error: () => this.load() });
  }
}
