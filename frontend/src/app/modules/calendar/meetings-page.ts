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
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { Appointment, EmployeeOption, MeetingPlatform } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { AppointmentDialogComponent } from './appointment-dialog';
import { CalendarService } from './calendar.service';
import { MEETING_PLATFORMS, openMeetingWindow, platformInfo } from './meeting-platforms';
import { MeetingRoomComponent } from './meeting-room';

type Period = 'today' | 'next7' | 'next30' | 'last30';
const PREF_KEY = 'erpadv.meetingPlatform';

/** Reuniões e atendimentos: presenciais ou por Google Meet, Zoom e Teams, com sala ao lado do sistema e ata. */
@Component({
  selector: 'app-meetings-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatSelectModule,
    MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe, MeetingRoomComponent],
  template: `
    <div class="page">
      @if (room(); as r) {
        <app-meeting-room [meeting]="r" (closed)="room.set(null); load()" (updated)="onUpdated($event)" />
      } @else {
        <app-page-header eyebrow="Escritório" title="Reuniões" subtitle="Reuniões e atendimentos presenciais ou por videoconferência — você escolhe Google Meet, Zoom ou Teams.">
          @if (canWrite) {
            <button mat-flat-button [matMenuTriggerFor]="newMenu"><mat-icon>video_call</mat-icon>Nova reunião</button>
            <mat-menu #newMenu="matMenu" xPosition="before">
              @for (p of platforms; track p.key) {
                <button mat-menu-item (click)="create(p.key)"><mat-icon>{{ p.icon }}</mat-icon>{{ p.label }}@if (preferred() === p.key) { <span class="pref">· preferida</span> }</button>
              }
            </mat-menu>
          }
        </app-page-header>

        <div class="card">
          <div class="toolbar">
            <mat-button-toggle-group [value]="period()" (change)="period.set($event.value); load()" aria-label="Período">
              <mat-button-toggle value="today">Hoje</mat-button-toggle>
              <mat-button-toggle value="next7">7 dias</mat-button-toggle>
              <mat-button-toggle value="next30">30 dias</mat-button-toggle>
              <mat-button-toggle value="last30">Realizadas (30 dias)</mat-button-toggle>
            </mat-button-toggle-group>
            <mat-select class="sel" [ngModel]="lawyer()" (ngModelChange)="lawyer.set($event); load()" aria-label="Advogado">
              <mat-option value="">Toda a equipe</mat-option>
              <mat-option [value]="me">Somente as minhas</mat-option>
              @for (s of staff(); track s.uid) { @if (s.uid !== me) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> } }
            </mat-select>
            <mat-select class="sel" [ngModel]="platformFilter()" (ngModelChange)="platformFilter.set($event)" aria-label="Plataforma">
              <mat-option value="">Todas as plataformas</mat-option>
              @for (p of platforms; track p.key) { <mat-option [value]="p.key">{{ p.label }}</mat-option> }
            </mat-select>
          </div>
          @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
          @if (!loading() && filtered().length === 0) {
            <app-empty-state icon="video_call" title="Nenhuma reunião no período" text="Agende reuniões com clientes e equipe — presenciais ou por videoconferência." />
          } @else {
            <ul class="list">
              @for (m of filtered(); track m.id) {
                <li [class.live]="isLive(m)" [class.done]="m.status === 'REALIZADO'">
                  <div class="when">
                    <strong>{{ m.start | brDate: false }}</strong>
                    <span>{{ time(m.start) }}–{{ time(m.end) }}</span>
                    @if (isLive(m)) { <span class="badge live-badge">agora</span> }
                  </div>
                  <div class="info">
                    <div class="title">{{ m.title }}</div>
                    <div class="meta">
                      <span class="plat"><mat-icon>{{ icon(m) }}</mat-icon>{{ m.meetingPlatformLabel || (m.location ? 'Presencial' : 'Sem local') }}</span>
                      @if (m.clientName) { · {{ m.clientName }} }
                      @if (m.caseNumber) { · <span class="mono">{{ m.caseNumber }}</span> }
                      · {{ m.responsibleNames.join(', ') }}
                      @if (m.minutes) { · <span class="ok"><mat-icon>description</mat-icon>ata</span> }
                    </div>
                  </div>
                  <div class="actions">
                    @if (m.meetingUrl) {
                      <button mat-flat-button (click)="join(m)" [matTooltip]="'Abre o ' + (m.meetingPlatformLabel || 'link') + ' ao lado e a sala da reunião aqui'"><mat-icon>login</mat-icon>Entrar</button>
                    } @else {
                      <button mat-stroked-button (click)="room.set(m)"><mat-icon>edit_note</mat-icon>Abrir</button>
                    }
                    <button mat-icon-button [matMenuTriggerFor]="mm" aria-label="Mais ações"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #mm="matMenu" xPosition="before">
                      <button mat-menu-item (click)="room.set(m)"><mat-icon>edit_note</mat-icon>Sala da reunião e ata</button>
                      @if (m.meetingUrl) { <button mat-menu-item (click)="copy(m.meetingUrl)"><mat-icon>content_copy</mat-icon>Copiar link</button> }
                      @if (canWrite) { <button mat-menu-item (click)="edit(m)"><mat-icon>edit</mat-icon>Editar / remarcar</button> }
                      @if (m.clientId) { <a mat-menu-item [routerLink]="['/clientes', m.clientId]"><mat-icon>person</mat-icon>Abrir cliente</a> }
                    </mat-menu>
                  </div>
                </li>
              }
            </ul>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .sel { width: 200px; border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; background: var(--app-surface); }
    .pref { color: var(--app-text-muted); font-size: 12px; margin-left: 4px; }
    .list { list-style: none; margin: 0; padding: 0; }
    .list li { display: flex; gap: 14px; align-items: center; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .list li.live { background: var(--app-gold-soft); }
    .list li.done .title { color: var(--app-text-muted); }
    .when { width: 110px; flex: none; display: flex; flex-direction: column; font-size: 13px; }
    .live-badge { align-self: flex-start; background: var(--app-danger); color: #fff; }
    .info { flex: 1; min-width: 0; }
    .title { font-weight: 600; }
    .meta { font-size: 12px; color: var(--app-text-muted); }
    .plat, .ok { display: inline-flex; align-items: center; gap: 3px; }
    .plat mat-icon, .ok mat-icon { font-size: 15px; width: 15px; height: 15px; }
    .ok { color: var(--app-success); }
    .actions { display: flex; align-items: center; gap: 4px; }
    @media (max-width: 760px) {
      .sel { width: 100%; }
      .list li { flex-wrap: wrap; gap: 8px; }
      .when { width: auto; flex-direction: row; gap: 8px; align-items: center; }
      .info { flex-basis: 100%; }
      .actions { margin-left: auto; }
    }
  `,
})
export class MeetingsPageComponent {
  private readonly service = inject(CalendarService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  readonly canWrite = this.auth.has('CALENDAR_WRITE');
  readonly me = this.auth.me()?.uid ?? '';
  readonly platforms = MEETING_PLATFORMS;
  readonly staff = toSignal(inject(ApiService).get<EmployeeOption[]>('/employees/options', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly period = signal<Period>('next7');
  readonly lawyer = signal('');
  readonly platformFilter = signal<MeetingPlatform | ''>('');
  readonly items = signal<Appointment[]>([]);
  readonly loading = signal(false);
  readonly room = signal<Appointment | null>(null);
  readonly preferred = signal<MeetingPlatform | null>(readPref());
  readonly filtered = computed(() => {
    const p = this.platformFilter();
    const list = this.items().filter((m) => !p || m.meetingPlatform === p || (p === 'PRESENCIAL' && !m.meetingUrl && !m.meetingPlatform));
    return this.period() === 'last30' ? [...list].reverse() : list;
  });

  constructor() {
    this.load();
  }

  load(): void {
    const now = new Date();
    const startOfDay = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    let from: Date;
    let to: Date;
    switch (this.period()) {
      case 'today': from = startOfDay; to = new Date(startOfDay.getTime() + 86_400_000); break;
      case 'next30': from = startOfDay; to = new Date(startOfDay.getTime() + 31 * 86_400_000); break;
      case 'last30': from = new Date(startOfDay.getTime() - 30 * 86_400_000); to = now; break;
      default: from = startOfDay; to = new Date(startOfDay.getTime() + 8 * 86_400_000);
    }
    this.loading.set(true);
    this.service.meetings(from, to, this.lawyer() || null).subscribe({
      next: (list) => { this.items.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  time(iso: string): string {
    return new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  }

  icon(m: Appointment): string {
    return platformInfo(m.meetingPlatform)?.icon ?? (m.location ? 'meeting_room' : 'event');
  }

  isLive(m: Appointment): boolean {
    const now = Date.now();
    return m.status !== 'CANCELADO' && new Date(m.start).getTime() - 10 * 60_000 <= now && new Date(m.end).getTime() >= now;
  }

  join(m: Appointment): void {
    openMeetingWindow(m.meetingUrl!);
    this.room.set(m);
  }

  copy(url: string): void {
    navigator.clipboard?.writeText(url).then(() => this.toast.success('Link copiado.'), () => this.toast.error('Não foi possível copiar.'));
  }

  create(platform: MeetingPlatform): void {
    try { localStorage.setItem(PREF_KEY, platform); } catch { /* preferência é opcional */ }
    this.preferred.set(platform);
    this.dialog.open(AppointmentDialogComponent, {
      data: { date: new Date(), type: 'REUNIAO', platform }, width: '820px', maxWidth: '96vw',
    }).afterClosed().subscribe((r) => { if (r && r !== 'deleted') { this.toast.success('Reunião agendada.'); this.load(); } });
  }

  edit(m: Appointment): void {
    this.dialog.open(AppointmentDialogComponent, { data: { appointment: m }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) this.load(); });
  }

  onUpdated(m: Appointment): void {
    this.room.set(m);
    this.items.update((list) => list.map((x) => (x.id === m.id ? m : x)));
  }
}

function readPref(): MeetingPlatform | null {
  try {
    return (localStorage.getItem(PREF_KEY) as MeetingPlatform | null) ?? null;
  } catch {
    return null;
  }
}
