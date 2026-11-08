import { Component, OnDestroy, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Appointment } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { CalendarService } from './calendar.service';
import { openMeetingWindow, platformInfo } from './meeting-platforms';

/**
 * Sala da reunião dentro do sistema: a chamada (Google Meet/Zoom/Teams) roda numa janela ao lado — essas
 * plataformas não permitem ser exibidas dentro de outro site — e aqui ficam cronômetro, participantes,
 * vínculos e a ata, salva no compromisso.
 */
@Component({
  selector: 'app-meeting-room',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, BrDatePipe],
  template: `
    <div class="room">
      <div class="head">
        <button mat-icon-button (click)="close()" aria-label="Voltar para Reuniões"><mat-icon>arrow_back</mat-icon></button>
        <div class="ttl">
          <div class="eyebrow">{{ platformLabel() }} · {{ meeting().start | brDate: true }}</div>
          <h1>{{ meeting().title }}</h1>
        </div>
        <span class="clock mono" [class.running]="running()">{{ clock() }}</span>
      </div>

      <div class="grid">
        <section class="card call">
          <mat-icon class="big">{{ icon() }}</mat-icon>
          @if (meeting().meetingUrl) {
            <h2>A chamada abre ao lado do sistema</h2>
            <p class="muted">O {{ platformLabel() }} não permite exibir a videochamada dentro de outro site. Ela abre numa janela à direita,
              e esta tela fica com você para acompanhar o cliente, o processo e registrar a ata.</p>
            <div class="row">
              <button mat-flat-button (click)="open()"><mat-icon>open_in_new</mat-icon>Abrir {{ platformLabel() }}</button>
              <button mat-stroked-button (click)="copy()"><mat-icon>content_copy</mat-icon>Copiar link</button>
            </div>
            <p class="small muted link">{{ meeting().meetingUrl }}</p>
          } @else {
            <h2>Reunião {{ meeting().location ? 'presencial' : 'sem link' }}</h2>
            <p class="muted">{{ meeting().location || 'Defina o local ou um link de videoconferência em “Editar”.' }}</p>
          }
          @if (canWrite && meeting().status !== 'REALIZADO') {
            <button mat-button class="finish" (click)="finish()"><mat-icon>task_alt</mat-icon>Marcar como realizada</button>
          } @else if (meeting().status === 'REALIZADO') {
            <span class="badge ok">Realizada</span>
          }
        </section>

        <section class="card side">
          <h3>Participantes</h3>
          <ul>
            @for (n of meeting().responsibleNames; track n) { <li><mat-icon>badge</mat-icon>{{ n }}</li> }
            @for (g of meeting().guests ?? []; track g.name) { <li><mat-icon>person</mat-icon>{{ g.name }}@if (g.email) { <span class="muted small"> · {{ g.email }}</span> }</li> }
          </ul>
          @if (meeting().clientId || meeting().caseId) {
            <h3>Vínculos</h3>
            @if (meeting().clientId) { <a mat-stroked-button [routerLink]="['/clientes', meeting().clientId]" target="_blank"><mat-icon>person</mat-icon>{{ meeting().clientName }}</a> }
            @if (meeting().caseId) { <a mat-stroked-button [routerLink]="['/processos', meeting().caseId]" target="_blank"><mat-icon>gavel</mat-icon>{{ meeting().caseNumber || 'Processo' }}</a> }
          }
          @if (meeting().description) { <h3>Pauta</h3><p class="pre">{{ meeting().description }}</p> }
        </section>

        <section class="card minutes">
          <div class="mhead">
            <h3><mat-icon>description</mat-icon>Ata da reunião</h3>
            <span class="muted small">
              @if (saving()) { Salvando… } @else if (dirty()) { Alterações não salvas }
              @else if (meeting().minutesUpdatedAt) { Salva por {{ meeting().minutesByName }} em {{ meeting().minutesUpdatedAt | brDate: true }} }
            </span>
          </div>
          <textarea [ngModel]="minutes()" (ngModelChange)="minutes.set($event); dirty.set(true)" [readonly]="!canWrite" maxlength="20000"
            placeholder="Assuntos tratados, decisões, encaminhamentos, prazos combinados…" aria-label="Ata da reunião"></textarea>
          @if (canWrite) {
            <div class="row end"><button mat-flat-button (click)="saveMinutes()" [disabled]="!dirty() || saving()"><mat-icon>save</mat-icon>Salvar ata</button></div>
          }
        </section>
      </div>
    </div>
  `,
  styles: `
    .head { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
    .ttl { flex: 1; min-width: 0; }
    .eyebrow { font-size: 12px; text-transform: uppercase; letter-spacing: .06em; color: var(--app-gold); font-weight: 700; }
    h1 { margin: 2px 0 0; font-size: 22px; }
    .clock { font-size: 20px; padding: 6px 12px; border-radius: 10px; background: var(--app-surface-2); }
    .clock.running { background: var(--app-danger-bg); color: var(--app-danger); }
    .grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 16px; }
    .card { padding: 18px; }
    .call { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 8px; }
    .big { font-size: 56px; width: 56px; height: 56px; color: var(--app-gold); }
    h2 { margin: 4px 0; font-size: 18px; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
    .row.end { justify-content: flex-end; margin-top: 8px; }
    .link { word-break: break-all; }
    .finish { margin-top: 8px; }
    .badge.ok { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .side h3, .minutes h3 { font-size: 13px; text-transform: uppercase; letter-spacing: .05em; color: var(--app-text-muted); margin: 8px 0; display: flex; align-items: center; gap: 6px; }
    .side ul { list-style: none; margin: 0 0 8px; padding: 0; }
    .side li { display: flex; align-items: center; gap: 6px; padding: 4px 0; }
    .side li mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--app-gold); }
    .side a { margin: 0 6px 6px 0; }
    .pre { white-space: pre-wrap; margin: 0; }
    .minutes { grid-column: 1 / -1; }
    .mhead { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
    textarea { width: 100%; min-height: 260px; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px; padding: 12px;
      font: 14px/1.6 inherit; background: var(--app-surface-2); color: var(--app-text); resize: vertical; }
    .small { font-size: 12px; }
    @media (max-width: 860px) { .grid { grid-template-columns: minmax(0, 1fr); } h1 { font-size: 18px; } }
  `,
})
export class MeetingRoomComponent implements OnDestroy {
  readonly meeting = input.required<Appointment>();
  readonly closed = output<void>();
  readonly updated = output<Appointment>();
  private readonly service = inject(CalendarService);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('CALENDAR_WRITE');
  readonly minutes = signal('');
  readonly dirty = signal(false);
  readonly saving = signal(false);
  private readonly now = signal(Date.now());
  private readonly timer = setInterval(() => this.now.set(Date.now()), 1000);
  readonly platformLabel = computed(() => this.meeting().meetingPlatformLabel ?? (this.meeting().location ? 'Presencial' : 'Reunião'));
  readonly icon = computed(() => platformInfo(this.meeting().meetingPlatform)?.icon ?? 'meeting_room');
  readonly running = computed(() => {
    const m = this.meeting();
    return this.now() >= new Date(m.start).getTime() && this.now() <= new Date(m.end).getTime() && m.status !== 'REALIZADO';
  });
  /** Contagem regressiva até o início ou tempo decorrido. */
  readonly clock = computed(() => {
    const start = new Date(this.meeting().start).getTime();
    const diff = Math.round((this.now() - start) / 1000);
    const abs = Math.abs(diff);
    if (abs >= 86_400) return diff < 0 ? `em ${Math.ceil(abs / 86_400)} dia(s)` : 'encerrada';
    const h = Math.floor(abs / 3600);
    const m = Math.floor((abs % 3600) / 60);
    const s = abs % 60;
    const txt = `${h ? h + ':' : ''}${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return diff < 0 ? `começa em ${txt}` : txt;
  });
  private initialized = false;

  constructor() {
    queueMicrotask(() => this.init());
  }

  private init(): void {
    if (this.initialized) return;
    this.initialized = true;
    this.minutes.set(this.meeting().minutes ?? '');
  }

  open(): void {
    openMeetingWindow(this.meeting().meetingUrl!);
  }

  copy(): void {
    navigator.clipboard?.writeText(this.meeting().meetingUrl!).then(() => this.toast.success('Link copiado.'), () => undefined);
  }

  saveMinutes(): void {
    this.saving.set(true);
    this.service.saveMinutes(this.meeting().id, this.minutes().trim() || null).subscribe({
      next: (m) => { this.saving.set(false); this.dirty.set(false); this.updated.emit(m); this.toast.success('Ata salva.'); },
      error: () => this.saving.set(false),
    });
  }

  finish(): void {
    const after = () => this.service.setStatus(this.meeting().id, 'REALIZADO', null).subscribe((m) => {
      this.updated.emit(m);
      this.toast.success('Reunião marcada como realizada.');
    });
    if (this.dirty()) {
      this.service.saveMinutes(this.meeting().id, this.minutes().trim() || null).subscribe(() => { this.dirty.set(false); after(); });
    } else {
      after();
    }
  }

  close(): void {
    if (this.dirty() && !confirm('A ata tem alterações não salvas. Sair mesmo assim?')) return;
    this.closed.emit();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}
