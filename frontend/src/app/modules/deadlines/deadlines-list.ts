import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Deadline, DeadlineStats, DeadlineView } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { PRIORITY_LABELS, SITUATION_LABELS, SITUATION_TONE } from '../../shared/labels';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { CourtCalendarComponent } from './court-calendar';
import { DeadlineFormDialogComponent } from './deadline-form-dialog';
import { DeadlinesService } from './deadlines.service';

export function countdownLabel(d: Pick<Deadline, 'situation' | 'daysLeft'>): string {
  if (d.situation === 'CONCLUIDO' || d.situation === 'CANCELADO') return '';
  if (d.daysLeft < 0) return `vencido há ${-d.daysLeft} dia${d.daysLeft === -1 ? '' : 's'}`;
  if (d.daysLeft === 0) return 'vence hoje';
  if (d.daysLeft === 1) return 'vence amanhã';
  return `em ${d.daysLeft} dias`;
}

@Component({
  selector: 'app-deadlines-list',
  imports: [
    RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule,
    MatSlideToggleModule, MatTabsModule, MatTooltipModule, PageHeaderComponent, StatCardComponent, EmptyStateComponent,
    BrDatePipe, CourtCalendarComponent,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Controle de prazos" title="Prazos" subtitle="Alertas automáticos 10, 5, 3 e 1 dia antes e no dia do vencimento.">
        <mat-slide-toggle [checked]="mine()" (change)="toggleMine($event.checked)">Somente meus</mat-slide-toggle>
        @if (auth.has('DEADLINES_WRITE')) {
          <button mat-flat-button (click)="create()"><mat-icon>alarm_add</mat-icon>Novo prazo</button>
        }
      </app-page-header>

      @if (stats(); as s) {
        <section class="kpis">
          <button class="kpi-btn" (click)="setView('HOJE')"><app-stat-card label="Vencem hoje" [value]="s.dueToday" icon="today" [accent]="s.dueToday > 0" [hint]="s.dueToday ? 'Prioridade máxima' : 'Nenhum hoje'" [tone]="s.dueToday ? 'danger' : 'success'" /></button>
          <button class="kpi-btn" (click)="setView('URGENTES')"><app-stat-card label="Urgentes (até 3 dias)" [value]="s.urgent" icon="priority_high" tone="warning" [hint]="s.urgent ? 'Requer atenção' : 'Sem urgências'" /></button>
          <button class="kpi-btn" (click)="setView('FUTUROS')"><app-stat-card label="Futuros" [value]="s.upcoming" icon="event_upcoming" hint="Mais de 3 dias" /></button>
          <button class="kpi-btn" (click)="setView('VENCIDOS')"><app-stat-card label="Vencidos" [value]="s.overdue" icon="event_busy" [tone]="s.overdue ? 'danger' : 'success'" [hint]="s.overdue ? 'Pendentes após o vencimento' : 'Nenhum vencido'" /></button>
          <button class="kpi-btn" (click)="setView('CONCLUIDOS')"><app-stat-card label="Concluídos" [value]="s.completed" icon="task_alt" tone="success" [hint]="s.unvalidated + ' pendente(s) de validação'" /></button>
        </section>
      }

      <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false">
        <mat-tab label="Prazos">
          <div class="card tab-card">
            <div class="toolbar">
              <mat-button-toggle-group [value]="view()" (change)="setView($event.value)" hideSingleSelectionIndicator aria-label="Situação">
                <mat-button-toggle value="PENDENTES">Pendentes</mat-button-toggle>
                <mat-button-toggle value="HOJE">Hoje</mat-button-toggle>
                <mat-button-toggle value="URGENTES">Urgentes</mat-button-toggle>
                <mat-button-toggle value="FUTUROS">Futuros</mat-button-toggle>
                <mat-button-toggle value="VENCIDOS">Vencidos</mat-button-toggle>
                <mat-button-toggle value="CONCLUIDOS">Concluídos</mat-button-toggle>
                <mat-button-toggle value="CANCELADOS">Cancelados</mat-button-toggle>
              </mat-button-toggle-group>
            </div>
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (!loading() && items().length === 0) {
              <app-empty-state icon="alarm_on" title="Nenhum prazo nesta visão" text="Cadastre prazos a partir daqui ou da página do processo." />
            } @else {
              <div class="table-wrap">
                <table class="data">
                  <thead><tr><th>Vencimento</th><th>Prazo</th><th>Processo / cliente</th><th>Responsável</th><th>Prioridade</th><th>Situação</th><th></th></tr></thead>
                  <tbody>
                    @for (d of items(); track d.id) {
                      <tr [class.highlight]="d.id === highlight()">
                        <td class="nowrap">
                          <div class="strong mono">{{ d.dueDate | brDate }}</div>
                          <div class="small" [class.danger]="d.daysLeft <= 0 && d.status === 'PENDENTE'">{{ countdown(d) }}</div>
                        </td>
                        <td>
                          <div class="strong">{{ d.description }}</div>
                          <div class="muted small">{{ d.typeLabel }} · {{ d.countingMode === 'MANUAL' ? 'data manual' : d.days + ' dias ' + (d.countingMode === 'UTEIS' ? 'úteis' : 'corridos') }}</div>
                        </td>
                        <td>
                          @if (d.caseId) { <a [routerLink]="['/processos', d.caseId]" class="mono">{{ d.caseNumber || 'Sem CNJ' }}</a> }
                          <div class="muted small">{{ d.clientName || '—' }}</div>
                        </td>
                        <td>{{ d.responsibleName }}</td>
                        <td><span class="prio" [attr.data-p]="d.priority">{{ priority[d.priority] }}</span></td>
                        <td>
                          <span class="badge" [class]="tone[d.situation]">{{ situation[d.situation] }}</span>
                          @if (d.status === 'PENDENTE') {
                            @if (d.validated) {
                              <mat-icon class="val ok" [matTooltip]="'Contagem validada por ' + d.validatedByName">verified</mat-icon>
                            } @else {
                              <mat-icon class="val pend" matTooltip="Contagem ainda não validada pelo responsável">pending</mat-icon>
                            }
                          }
                        </td>
                        <td class="actions">
                          @if (auth.has('DEADLINES_WRITE')) {
                            <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações do prazo"><mat-icon>more_vert</mat-icon></button>
                            <mat-menu #menu="matMenu" xPosition="before">
                              @if (d.status === 'PENDENTE') {
                                @if (!d.validated && canValidate(d)) {
                                  <button mat-menu-item (click)="validate(d)"><mat-icon>verified</mat-icon>Validar contagem</button>
                                }
                                <button mat-menu-item (click)="complete(d)"><mat-icon>task_alt</mat-icon>Marcar como cumprido</button>
                                <button mat-menu-item (click)="edit(d)"><mat-icon>edit</mat-icon>Editar</button>
                                <button mat-menu-item (click)="cancel(d)"><mat-icon>block</mat-icon>Cancelar prazo</button>
                              } @else {
                                <button mat-menu-item (click)="reopen(d)"><mat-icon>restart_alt</mat-icon>Reabrir</button>
                              }
                            </mat-menu>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
            }
          </div>
        </mat-tab>
        <mat-tab label="Calendário forense">
          <app-court-calendar />
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
    .kpi-btn { all: unset; cursor: pointer; display: block; border-radius: var(--app-radius); }
    .kpi-btn:focus-visible { outline: 2px solid var(--app-gold); outline-offset: 2px; }
    @media (max-width: 1200px) { .kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
    @media (max-width: 700px) { .kpis { grid-template-columns: 1fr 1fr; } }
    .tab-card { margin-top: 16px; }
    .toolbar { padding: 14px 16px; border-bottom: 1px solid var(--app-border); overflow-x: auto; }
    .strong { font-weight: 600; }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .small.danger { color: var(--app-danger); font-weight: 600; }
    .nowrap { white-space: nowrap; }
    .prio { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 6px; background: var(--app-surface-2); }
    .prio[data-p='ALTA'] { color: var(--app-warning); background: var(--app-warning-bg); }
    .prio[data-p='CRITICA'] { color: var(--app-danger); background: var(--app-danger-bg); }
    .val { font-size: 18px; width: 18px; height: 18px; vertical-align: middle; margin-left: 6px; }
    .val.ok { color: var(--app-success); }
    .val.pend { color: var(--app-warning); }
    .actions { text-align: right; width: 56px; }
    tr.highlight td { background: var(--app-gold-soft); }
    .more { display: flex; justify-content: center; padding: 16px; }
  `,
})
export class DeadlinesListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(DeadlinesService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly situation = SITUATION_LABELS;
  readonly tone = SITUATION_TONE;
  readonly priority = PRIORITY_LABELS;
  readonly countdown = countdownLabel;

  readonly view = signal<DeadlineView>('PENDENTES');
  readonly mine = signal(false);
  readonly items = signal<Deadline[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly stats = signal<DeadlineStats | null>(null);
  readonly highlight = signal<string | null>(this.route.snapshot.queryParamMap.get('id'));
  private readonly responsibleFilter = computed(() => (this.mine() ? this.auth.me()?.uid : undefined));

  constructor() {
    this.refresh();
    const id = this.highlight();
    if (id) {
      this.service.get(id).subscribe((d) => {
        if (d.status !== 'PENDENTE') this.setView(d.status === 'CONCLUIDO' ? 'CONCLUIDOS' : 'CANCELADOS');
        if (d.status === 'PENDENTE' && this.auth.has('DEADLINES_WRITE')) this.edit(d);
      });
    }
  }

  canValidate(d: Deadline): boolean {
    const role = this.auth.me()?.role;
    return d.responsibleUid === this.auth.me()?.uid || role === 'SOCIO_ADMIN' || role === 'MASTER';
  }

  toggleMine(value: boolean): void {
    this.mine.set(value);
    this.refresh();
  }

  setView(view: DeadlineView): void {
    this.view.set(view);
    this.load(false);
  }

  refresh(): void {
    this.load(false);
    this.service.stats(this.responsibleFilter()).subscribe((s) => this.stats.set(s));
  }

  load(more: boolean): void {
    this.loading.set(true);
    this.service.list(this.view(), { responsibleUid: this.responsibleFilter() }, more ? this.cursor() : null).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  create(): void {
    this.dialog.open(DeadlineFormDialogComponent, { data: {}, width: '880px', maxWidth: '96vw' }).afterClosed().subscribe((d?: Deadline) => {
      if (d) {
        this.toast.success('Prazo cadastrado. Lembre-se de validar a contagem.');
        this.refresh();
      }
    });
  }

  edit(d: Deadline): void {
    this.dialog.open(DeadlineFormDialogComponent, { data: { deadline: d }, width: '880px', maxWidth: '96vw' }).afterClosed().subscribe((u?: Deadline) => {
      void this.router.navigate([], { queryParams: { id: null }, replaceUrl: true });
      if (u) {
        this.toast.success('Prazo atualizado.');
        this.refresh();
      }
    });
  }

  validate(d: Deadline): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: {
          title: 'Validar contagem',
          message: `Confirmo que conferi a contagem e que o vencimento em ${new Date(d.dueDate + 'T12:00').toLocaleDateString('pt-BR')} está correto (intimação, feriados, suspensões e prazos especiais).`,
          confirmLabel: 'Validar',
        },
        width: '520px',
      })
      .afterClosed()
      .subscribe((ok) => {
        if (ok) this.service.validate(d.id).subscribe(() => { this.toast.success('Contagem validada.'); this.refresh(); });
      });
  }

  complete(d: Deadline): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Prazo cumprido', message: d.description, confirmLabel: 'Marcar como cumprido', reasonLabel: 'Observação (ex.: protocolo nº)', reasonRequired: false },
        width: '480px',
      })
      .afterClosed()
      .subscribe((note) => {
        if (typeof note === 'string') {
          this.service.complete(d.id, note || null).subscribe(() => { this.toast.success('Prazo concluído.'); this.refresh(); });
        }
      });
  }

  cancel(d: Deadline): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Cancelar prazo', message: d.description, confirmLabel: 'Cancelar prazo', danger: true, reasonLabel: 'Motivo', reasonRequired: true },
        width: '480px',
      })
      .afterClosed()
      .subscribe((reason) => {
        if (typeof reason === 'string' && reason) {
          this.service.cancel(d.id, reason).subscribe(() => { this.toast.success('Prazo cancelado.'); this.refresh(); });
        }
      });
  }

  reopen(d: Deadline): void {
    this.service.reopen(d.id).subscribe(() => { this.toast.success('Prazo reaberto.'); this.setView('PENDENTES'); this.refresh(); });
  }
}
