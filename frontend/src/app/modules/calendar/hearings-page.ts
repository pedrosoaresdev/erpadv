import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Appointment, AppointmentStatus } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { APPOINTMENT_STATUS_LABELS } from '../../shared/labels';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { DeadlinesService } from '../deadlines/deadlines.service';
import { saveBlob } from '../finance/finance.service';
import { AppointmentDialogComponent } from './appointment-dialog';
import { CalendarService } from './calendar.service';

type Period = 'next30' | 'next90' | 'last30' | 'last90';

/** Módulo Audiências: audiências, sessões de julgamento, sustentações orais e perícias, com situação e resultado. */
@Component({
  selector: 'app-hearings-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule,
    MatSelectModule, PageHeaderComponent, StatCardComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Agenda jurídica" title="Audiências" subtitle="Audiências, sessões de julgamento, sustentações orais e perícias — com situação e resultado.">
        @if (auth.has('REPORTS_VIEW')) {
          <button mat-stroked-button [matMenuTriggerFor]="reportMenu"><mat-icon>summarize</mat-icon>Relatório</button>
          <mat-menu #reportMenu="matMenu" xPosition="before">
            <button mat-menu-item (click)="report('pdf')"><mat-icon>picture_as_pdf</mat-icon>Período selecionado (PDF)</button>
            <button mat-menu-item (click)="report('xlsx')"><mat-icon>table_view</mat-icon>Período selecionado (Excel)</button>
          </mat-menu>
        }
        @if (auth.has('CALENDAR_WRITE')) {
          <button mat-flat-button (click)="create()"><mat-icon>record_voice_over</mat-icon>Nova audiência</button>
        }
      </app-page-header>

      <section class="kpis">
        <app-stat-card label="No período" [value]="items().length" icon="gavel" [accent]="true" />
        <app-stat-card label="Agendadas" [value]="count('AGENDADO')" icon="event" />
        <app-stat-card label="Realizadas" [value]="count('REALIZADO')" icon="task_alt" tone="success" />
        <app-stat-card label="Redesignadas/adiadas" [value]="count('REDESIGNADO') + count('ADIADO')" icon="event_repeat" tone="warning" />
        <app-stat-card label="Sem resultado registrado" [value]="pendingOutcome()" icon="pending_actions"
          [tone]="pendingOutcome() ? 'danger' : 'success'" hint="Já passaram e seguem como agendadas" />
      </section>

      <div class="card">
        <div class="toolbar">
          <mat-button-toggle-group [value]="period()" (change)="setPeriod($event.value)" aria-label="Período">
            <mat-button-toggle value="next30">Próximos 30 dias</mat-button-toggle>
            <mat-button-toggle value="next90">Próximos 90 dias</mat-button-toggle>
            <mat-button-toggle value="last30">Últimos 30 dias</mat-button-toggle>
            <mat-button-toggle value="last90">Últimos 90 dias</mat-button-toggle>
          </mat-button-toggle-group>
          <mat-select class="fsel" [ngModel]="lawyer()" (ngModelChange)="setLawyer($event)" placeholder="Advogado" aria-label="Advogado">
            <mat-option value="">Todos os advogados</mat-option>
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          <mat-select class="fsel" [ngModel]="status()" (ngModelChange)="status.set($event)" placeholder="Situação" aria-label="Situação">
            <mat-option value="">Todas as situações</mat-option>
            @for (s of statusKeys; track s) { <mat-option [value]="s">{{ statusLabels[s] }}</mat-option> }
          </mat-select>
        </div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && filtered().length === 0) {
          <app-empty-state icon="record_voice_over" title="Nenhuma audiência no período" text="Agende pela página do processo ou pelo botão Nova audiência." />
        } @else {
          <div class="table-wrap">
            <table class="data stack">
              <thead><tr><th>Data</th><th>Tipo</th><th>Audiência</th><th>Processo / cliente</th><th>Responsáveis</th><th>Situação</th><th></th></tr></thead>
              <tbody>
                @for (a of filtered(); track a.id) {
                  <tr [class.late]="isPendingOutcome(a)">
                    <td class="mono" data-label="Data">{{ a.start | brDate: true }}</td>
                    <td data-label="Tipo"><span class="badge">{{ a.typeLabel }}</span></td>
                    <td data-label="Audiência"><strong>{{ a.title }}</strong>
                      @if (a.location) { <div class="muted small"><mat-icon inline>place</mat-icon>{{ a.location }}</div> }
                      @if (a.meetingUrl) { <div class="small"><a [href]="a.meetingUrl" target="_blank" rel="noopener noreferrer"><mat-icon inline>videocam</mat-icon>Sala virtual</a></div> }
                    </td>
                    <td data-label="Processo">
                      @if (a.caseId) { <a [routerLink]="['/processos', a.caseId]" class="mono">{{ a.caseNumber || 'Sem CNJ' }}</a> }
                      <div class="muted small">{{ a.clientName || '—' }}</div>
                    </td>
                    <td data-label="Responsáveis" class="small">{{ a.responsibleNames.join(', ') }}</td>
                    <td data-label="Situação">
                      <span class="badge" [class.success]="a.status === 'REALIZADO'" [class.warning]="a.status === 'REDESIGNADO' || a.status === 'ADIADO'"
                        [class.danger]="a.status === 'CANCELADO'">{{ a.statusLabel }}</span>
                      @if (a.outcome) { <div class="muted small outcome">{{ a.outcome }}</div> }
                      @if (isPendingOutcome(a)) { <div class="small bad">registrar resultado</div> }
                    </td>
                    <td class="actions">
                      <button mat-icon-button [matMenuTriggerFor]="m" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                      <mat-menu #m="matMenu" xPosition="before">
                        <button mat-menu-item (click)="open(a)"><mat-icon>visibility</mat-icon>Detalhes e participantes</button>
                        @if (auth.has('CALENDAR_WRITE')) {
                          <button mat-menu-item (click)="setStatus(a, 'REALIZADO')"><mat-icon>task_alt</mat-icon>Realizada (registrar resultado)</button>
                          <button mat-menu-item (click)="setStatus(a, 'REDESIGNADO')"><mat-icon>event_repeat</mat-icon>Redesignada</button>
                          <button mat-menu-item (click)="setStatus(a, 'ADIADO')"><mat-icon>schedule</mat-icon>Adiada</button>
                          <button mat-menu-item (click)="setStatus(a, 'CANCELADO')"><mat-icon>event_busy</mat-icon>Cancelada</button>
                        }
                      </mat-menu>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    </div>
  `,
  styles: `
    .kpis { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
    @media (max-width: 1100px) { .kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .fsel { width: 200px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; }
    .small { font-size: 12px; }
    .outcome { white-space: pre-wrap; max-width: 280px; }
    .bad { color: var(--app-danger); font-weight: 600; }
    tr.late td { background: color-mix(in srgb, var(--app-danger-bg) 45%, transparent); }
    .actions { text-align: right; width: 56px; }
  `,
})
export class HearingsPageComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(CalendarService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly staff = toSignal(inject(DeadlinesService).staff(), { initialValue: [] });
  readonly statusLabels = APPOINTMENT_STATUS_LABELS;
  readonly statusKeys = Object.keys(APPOINTMENT_STATUS_LABELS) as AppointmentStatus[];
  readonly period = signal<Period>('next30');
  readonly lawyer = signal('');
  readonly status = signal('');
  readonly items = signal<Appointment[]>([]);
  readonly loading = signal(false);
  readonly filtered = computed(() => this.items().filter((a) => !this.status() || a.status === this.status()));
  readonly pendingOutcome = computed(() => this.items().filter((a) => this.isPendingOutcome(a)).length);

  constructor() {
    this.load();
  }

  private range(): { from: Date; to: Date } {
    const now = new Date();
    const day = 86_400_000;
    switch (this.period()) {
      case 'next90': return { from: new Date(now.setHours(0, 0, 0, 0)), to: new Date(Date.now() + 90 * day) };
      case 'last30': return { from: new Date(Date.now() - 30 * day), to: new Date() };
      case 'last90': return { from: new Date(Date.now() - 90 * day), to: new Date() };
      default: return { from: new Date(now.setHours(0, 0, 0, 0)), to: new Date(Date.now() + 30 * day) };
    }
  }

  load(): void {
    const { from, to } = this.range();
    this.loading.set(true);
    this.service.hearings(from, to, this.lawyer() || null).subscribe({
      next: (list) => {
        const past = this.period().startsWith('last');
        this.items.set(past ? [...list].reverse() : list);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  setPeriod(p: Period): void {
    this.period.set(p);
    this.load();
  }

  setLawyer(uid: string): void {
    this.lawyer.set(uid);
    this.load();
  }

  count(s: AppointmentStatus): number {
    return this.items().filter((a) => a.status === s).length;
  }

  /** Já ocorreu e continua "agendada": falta registrar o que aconteceu. */
  isPendingOutcome(a: Appointment): boolean {
    return a.status === 'AGENDADO' && new Date(a.end).getTime() < Date.now();
  }

  create(): void {
    this.dialog.open(AppointmentDialogComponent, { data: { date: new Date() }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r && r !== 'deleted') { this.toast.success('Audiência agendada.'); this.load(); } });
  }

  open(a: Appointment): void {
    this.dialog.open(AppointmentDialogComponent, { data: { appointment: a }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) this.load(); });
  }

  setStatus(a: Appointment, status: AppointmentStatus): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: APPOINTMENT_STATUS_LABELS[status], message: a.title, confirmLabel: 'Salvar',
        reasonLabel: status === 'REALIZADO' ? 'Resultado / ata (ex.: acordo, instrução encerrada, sentença em audiência)' : 'Observação (ex.: nova data, motivo)',
        reasonRequired: status === 'REALIZADO' },
      width: '540px',
    }).afterClosed().subscribe((text) => {
      if (typeof text !== 'string' && text !== true) return;
      this.service.setStatus(a.id, status, typeof text === 'string' ? text || null : null).subscribe(() => {
        this.toast.success('Situação registrada.');
        this.load();
      });
    });
  }

  report(format: 'pdf' | 'xlsx'): void {
    const { from, to } = this.range();
    this.service.report({ from: from.toISOString(), to: to.toISOString(), responsibleUid: this.lawyer() || null, hearings: true, format })
      .subscribe({ next: (r) => saveBlob(r, `audiencias.${format}`), error: () => this.toast.error('Não foi possível gerar o relatório.') });
  }
}
