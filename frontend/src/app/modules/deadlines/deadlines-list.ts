import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatSelectModule } from '@angular/material/select';
import { HistoryDialogComponent } from '../../shared/components/history-dialog';
import { saveBlob } from '../finance/finance.service';
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
    MatSlideToggleModule, MatTabsModule, MatTooltipModule, MatSelectModule, FormsModule, PageHeaderComponent, StatCardComponent,
    EmptyStateComponent, BrDatePipe, CourtCalendarComponent,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Controle de prazos" title="Prazos" subtitle="Alertas automáticos 10, 5, 3 e 1 dia antes e no dia do vencimento.">
        <mat-slide-toggle [checked]="mine()" (change)="toggleMine($event.checked)">Somente meus</mat-slide-toggle>
        <a mat-stroked-button routerLink="/agenda" matTooltip="A agenda mostra os prazos no calendário (dia, semana e mês)"><mat-icon>calendar_month</mat-icon>Calendário</a>
        @if (auth.has('REPORTS_VIEW')) {
          <button mat-stroked-button [matMenuTriggerFor]="reportMenu"><mat-icon>summarize</mat-icon>Relatório</button>
          <mat-menu #reportMenu="matMenu" xPosition="before">
            <button mat-menu-item (click)="report('pdf', 0)"><mat-icon>picture_as_pdf</mat-icon>Mês atual por responsável (PDF)</button>
            <button mat-menu-item (click)="report('xlsx', 0)"><mat-icon>table_view</mat-icon>Mês atual por responsável (Excel)</button>
            <button mat-menu-item (click)="report('pdf', 1)"><mat-icon>picture_as_pdf</mat-icon>Próximo mês (PDF)</button>
            <button mat-menu-item (click)="report('pdf', -1)"><mat-icon>picture_as_pdf</mat-icon>Mês anterior (PDF)</button>
          </mat-menu>
        }
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
              <div class="filters">
                <mat-select class="fsel" [ngModel]="responsible()" (ngModelChange)="setResponsible($event)" placeholder="Responsável" aria-label="Responsável">
                  <mat-option value="">Todos os responsáveis</mat-option>
                  @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
                </mat-select>
                @if (clientFilter(); as c) {
                  <span class="chip on">Cliente: {{ c.name }} <button type="button" (click)="setClient(null)" aria-label="Remover filtro de cliente"><mat-icon>close</mat-icon></button></span>
                }
              </div>
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
                          <div class="muted small">{{ d.typeLabel }} · {{ d.countingMode === 'MANUAL' ? 'data manual' : d.days + ' dias ' + (d.countingMode === 'UTEIS' ? 'úteis' : 'corridos') }}{{ d.originLabel ? ' · ' + d.originLabel : '' }}</div>
                          @if (d.publicationDate || d.awarenessDate) {
                            <div class="muted small">{{ d.publicationDate ? 'publicado em ' + (d.publicationDate | brDate) : 'ciência em ' + (d.awarenessDate | brDate) }}</div>
                          }
                          @if (d.receiptName) { <div class="small ok"><mat-icon inline>attach_file</mat-icon>comprovante anexado</div> }
                        </td>
                        <td>
                          @if (d.caseId) { <a [routerLink]="['/processos', d.caseId]" class="mono">{{ d.caseNumber || 'Sem CNJ' }}</a> }
                          @if (d.clientId) { <button type="button" class="link small" (click)="setClient({ id: d.clientId, name: d.clientName || 'cliente' })" matTooltip="Filtrar por este cliente">{{ d.clientName }}</button> } @else { <div class="muted small">—</div> }
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
                          <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações do prazo"><mat-icon>more_vert</mat-icon></button>
                          <mat-menu #menu="matMenu" xPosition="before">
                            <button mat-menu-item (click)="history(d)"><mat-icon>history</mat-icon>Histórico de alterações</button>
                            @if (d.receiptName) {
                              <button mat-menu-item (click)="downloadReceipt(d)"><mat-icon>download</mat-icon>Baixar comprovante</button>
                            }
                            @if (auth.has('DEADLINES_WRITE')) {
                              <button mat-menu-item (click)="pickReceipt(d)"><mat-icon>attach_file</mat-icon>{{ d.receiptName ? 'Substituir comprovante' : 'Anexar comprovante de cumprimento' }}</button>
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
                            }
                          </mat-menu>
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
        <mat-tab label="Calendário forense (feriados e suspensões)">
          <app-court-calendar />
        </mat-tab>
      </mat-tab-group>
      <input #receiptInput type="file" accept="application/pdf,image/png,image/jpeg" hidden (change)="uploadReceipt($event)" />
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
    .toolbar { display: flex; flex-direction: column; gap: 10px; }
    .filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .fsel { width: 230px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; }
    .chip { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--app-gold); background: var(--app-gold-soft); border-radius: 999px; padding: 4px 6px 4px 12px; font-size: 13px; }
    .chip button { border: 0; background: none; cursor: pointer; display: grid; place-items: center; color: inherit; }
    .chip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .link { border: 0; background: none; padding: 0; color: var(--app-info); cursor: pointer; font: inherit; text-align: left; }
    .ok { color: var(--app-success); }
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
  readonly responsible = signal('');
  readonly clientFilter = signal<{ id: string; name: string } | null>(null);
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  private readonly receiptInput = viewChild.required<ElementRef<HTMLInputElement>>('receiptInput');
  private receiptTarget: Deadline | null = null;
  private readonly responsibleFilter = computed(() => (this.mine() ? this.auth.me()?.uid : this.responsible() || undefined));

  setResponsible(uid: string): void {
    this.responsible.set(uid);
    if (uid) this.mine.set(false);
    this.refresh();
  }

  setClient(c: { id: string; name: string } | null): void {
    this.clientFilter.set(c);
    this.load(false);
  }

  history(d: Deadline): void {
    this.dialog.open(HistoryDialogComponent, {
      data: { title: 'Histórico · ' + d.description, load: () => this.service.history(d.id) }, maxWidth: '96vw',
    });
  }

  pickReceipt(d: Deadline): void {
    this.receiptTarget = d;
    this.receiptInput().nativeElement.value = '';
    this.receiptInput().nativeElement.click();
  }

  uploadReceipt(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    const d = this.receiptTarget;
    if (!file || !d) return;
    this.service.uploadReceipt(d.id, file).subscribe(() => { this.toast.success('Comprovante anexado.'); this.load(false); });
  }

  downloadReceipt(d: Deadline): void {
    this.service.receipt(d.id).subscribe((r) => saveBlob(r, d.receiptName ?? 'comprovante'));
  }

  /** Relatório por responsável do mês (offset: 0 atual, 1 próximo, -1 anterior). */
  report(format: 'pdf' | 'xlsx', offset: number): void {
    const now = new Date();
    const first = new Date(now.getFullYear(), now.getMonth() + offset, 1);
    const last = new Date(now.getFullYear(), now.getMonth() + offset + 1, 0);
    const iso = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
    this.service.report({ from: iso(first), to: iso(last), responsibleUid: this.responsibleFilter(), format }).subscribe({
      next: (r) => saveBlob(r, `prazos.${format}`),
      error: () => this.toast.error('Não foi possível gerar o relatório.'),
    });
  }

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
    this.service.list(this.view(), { responsibleUid: this.responsibleFilter(), clientId: this.clientFilter()?.id }, more ? this.cursor() : null).subscribe({
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
          this.service.complete(d.id, note || null).subscribe(() => { this.toast.success('Prazo concluído. Anexe o comprovante de protocolo pelo menu do prazo.'); this.refresh(); });
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
