import { Component, DestroyRef, ElementRef, computed, effect, inject, signal, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Appointment, CalendarEvent } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { PageHeaderComponent } from '../../shared/components/ui';
import { EVENT_COLORS } from '../../shared/labels';
import { AppointmentDialogComponent } from './appointment-dialog';
import { CalendarService } from './calendar.service';
import {
  CalendarView,
  HOUR_PX,
  monthGrid,
  occursOn,
  rangeFor,
  sameDay,
  shift,
  startOfDay,
  timeBox,
  weekDays,
} from './calendar-utils';

const WEEKDAYS = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'];
const MONTH_FMT = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' });
const DAY_FMT = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: '2-digit', month: 'long', year: 'numeric' });
const TIME_FMT = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' });

@Component({
  selector: 'app-calendar-page',
  imports: [MatButtonModule, MatButtonToggleModule, MatIconModule, MatProgressBarModule, MatSlideToggleModule, MatTooltipModule, PageHeaderComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Agenda jurídica" title="Agenda" subtitle="Audiências, julgamentos, reuniões, compromissos e prazos.">
        <mat-slide-toggle [checked]="mine()" (change)="mine.set($event.checked)">Somente meus</mat-slide-toggle>
        @if (auth.has('DEADLINES_READ')) {
          <mat-slide-toggle [checked]="showDeadlines()" (change)="showDeadlines.set($event.checked)">Prazos</mat-slide-toggle>
        }
        @if (auth.has('CALENDAR_WRITE')) {
          <button mat-flat-button (click)="create(ref())"><mat-icon>event</mat-icon>Novo compromisso</button>
        }
      </app-page-header>

      <div class="card">
        <div class="toolbar">
          <button mat-stroked-button (click)="today()">Hoje</button>
          <button mat-icon-button (click)="move(-1)" aria-label="Anterior"><mat-icon>chevron_left</mat-icon></button>
          <button mat-icon-button (click)="move(1)" aria-label="Próximo"><mat-icon>chevron_right</mat-icon></button>
          <h2 class="title">{{ title() }}</h2>
          <span class="spacer"></span>
          <mat-button-toggle-group [value]="view()" (change)="view.set($event.value)" hideSingleSelectionIndicator aria-label="Visualização">
            <mat-button-toggle value="day">Dia</mat-button-toggle>
            <mat-button-toggle value="week">Semana</mat-button-toggle>
            <mat-button-toggle value="month">Mês</mat-button-toggle>
          </mat-button-toggle-group>
        </div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @if (view() === 'month') {
          <div class="month">
            @for (w of weekdays; track w) { <div class="wd">{{ w }}</div> }
            @for (day of grid(); track day.getTime()) {
              <div class="cell" [class.out]="day.getMonth() !== ref().getMonth()" [class.today]="isToday(day)"
                   (dblclick)="create(day)" (click)="openDay(day, $event)" tabindex="0" (keydown.enter)="openDay(day)">
                <span class="num">{{ day.getDate() }}</span>
                @for (e of dayEvents(day).slice(0, 3); track e.kind + e.id) {
                  <button class="chip" [style.--c]="color(e)" (click)="open(e, $event)" [matTooltip]="tooltip(e)">
                    @if (!e.allDay) { <span class="t">{{ time(e.start) }}</span> }
                    {{ e.title }}
                  </button>
                }
                @if (dayEvents(day).length > 3) {
                  <span class="more-chip">+{{ dayEvents(day).length - 3 }} mais</span>
                }
              </div>
            }
          </div>
        } @else {
          <div class="tg-head" [style.grid-template-columns]="columnsTemplate()">
            <div></div>
            @for (day of columns(); track day.getTime()) {
              <div class="tg-day" [class.today]="isToday(day)">
                <span class="wdn">{{ weekdays[day.getDay()] }}</span><span class="dn">{{ day.getDate() }}</span>
              </div>
            }
          </div>
          <div class="tg-allday" [style.grid-template-columns]="columnsTemplate()">
            <div class="lbl">Dia todo</div>
            @for (day of columns(); track day.getTime()) {
              <div class="ad-cell">
                @for (e of allDayEvents(day); track e.kind + e.id) {
                  <button class="chip" [style.--c]="color(e)" (click)="open(e, $event)" [matTooltip]="tooltip(e)">{{ e.title }}</button>
                }
              </div>
            }
          </div>
          <div class="tg-body" #scroller>
            <div class="tg-grid" [style.grid-template-columns]="columnsTemplate()" [style.height.px]="hourPx * 24">
              <div class="hours">
                @for (h of hours; track h) { <div class="hour" [style.height.px]="hourPx">{{ h }}:00</div> }
              </div>
              @for (day of columns(); track day.getTime()) {
                <div class="col" (dblclick)="createAt(day, $event)">
                  @for (h of hours; track h) { <div class="slot" [style.height.px]="hourPx"></div> }
                  @if (isToday(day)) { <div class="now" [style.top.px]="nowTop()"></div> }
                  @for (e of timedEvents(day); track e.id) {
                    <button class="ev" [style.--c]="color(e)" [style.top.px]="box(e, day).top" [style.height.px]="box(e, day).height"
                            (click)="open(e, $event)" [matTooltip]="tooltip(e)">
                      <strong>{{ time(e.start) }}</strong> {{ e.title }}
                      @if (e.location) { <span class="loc">{{ e.location }}</span> }
                    </button>
                  }
                </div>
              }
            </div>
          </div>
        }
      </div>

      <div class="legend">
        @for (l of legend; track l.key) { <span><i [style.background]="l.color"></i>{{ l.label }}</span> }
      </div>
    </div>
  `,
  styles: `
    .toolbar { display: flex; align-items: center; gap: 6px; padding: 12px 16px; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .title { font-size: 18px; font-weight: 600; margin-left: 8px; }
    .month { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); }
    .wd { padding: 8px 10px; font-size: 12px; font-weight: 600; color: var(--app-text-muted); text-transform: uppercase; border-bottom: 1px solid var(--app-border); }
    .cell { min-height: 112px; border-right: 1px solid var(--app-border); border-bottom: 1px solid var(--app-border); padding: 6px; display: flex; flex-direction: column; gap: 3px; cursor: pointer; min-width: 0; }
    .cell:nth-child(7n) { border-right: 0; }
    .cell:hover { background: var(--app-surface-2); }
    .cell.out { background: color-mix(in srgb, var(--app-surface-2) 60%, transparent); }
    .cell.out .num { opacity: .45; }
    .num { font-size: 12px; font-weight: 600; width: 24px; height: 24px; display: grid; place-items: center; border-radius: 50%; }
    .cell.today .num { background: var(--app-navy); color: var(--app-gold); }
    .chip { all: unset; cursor: pointer; font-size: 12px; line-height: 18px; padding: 1px 6px; border-radius: 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
      background: color-mix(in srgb, var(--c) 14%, transparent); color: var(--app-text); border-left: 3px solid var(--c); }
    .chip:hover { background: color-mix(in srgb, var(--c) 24%, transparent); }
    .chip .t { font-weight: 600; margin-right: 2px; }
    .more-chip { font-size: 11px; color: var(--app-text-muted); padding-left: 4px; }

    .tg-head, .tg-allday, .tg-grid { display: grid; }
    .tg-head { border-bottom: 1px solid var(--app-border); }
    .tg-day { padding: 8px; text-align: center; display: flex; flex-direction: column; }
    .tg-day .wdn { font-size: 11px; text-transform: uppercase; color: var(--app-text-muted); font-weight: 600; }
    .tg-day .dn { font-size: 20px; font-weight: 600; }
    .tg-day.today .dn { color: var(--app-gold); }
    .tg-allday { border-bottom: 1px solid var(--app-border); min-height: 36px; }
    .tg-allday .lbl { font-size: 11px; color: var(--app-text-muted); padding: 8px 6px; text-align: right; }
    .ad-cell { padding: 4px; display: flex; flex-direction: column; gap: 3px; border-left: 1px solid var(--app-border); min-width: 0; }
    .tg-body { max-height: 620px; overflow-y: auto; }
    .hours .hour { font-size: 11px; color: var(--app-text-muted); text-align: right; padding-right: 8px; transform: translateY(-7px); }
    .col { position: relative; border-left: 1px solid var(--app-border); }
    .slot { border-bottom: 1px solid color-mix(in srgb, var(--app-border) 70%, transparent); }
    .ev { all: unset; cursor: pointer; position: absolute; left: 3px; right: 3px; box-sizing: border-box; padding: 3px 6px; border-radius: 8px; font-size: 12px; overflow: hidden;
      background: color-mix(in srgb, var(--c) 18%, var(--app-surface)); border-left: 3px solid var(--c); color: var(--app-text); line-height: 1.3; box-shadow: var(--app-shadow); }
    .ev:hover { background: color-mix(in srgb, var(--c) 28%, var(--app-surface)); z-index: 2; }
    .ev .loc { display: block; color: var(--app-text-muted); font-size: 11px; }
    .now { position: absolute; left: 0; right: 0; height: 2px; background: #d92d20; z-index: 3; }
    .now::before { content: ''; position: absolute; left: -5px; top: -4px; width: 10px; height: 10px; border-radius: 50%; background: #d92d20; }
    .legend { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 12px; font-size: 12px; color: var(--app-text-muted); }
    .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; vertical-align: middle; }
    @media (max-width: 700px) { .cell { min-height: 76px; } .chip .t { display: none; } }
  `,
})
export class CalendarPageComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(CalendarService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');

  readonly weekdays = WEEKDAYS;
  readonly hours = Array.from({ length: 24 }, (_, i) => i);
  readonly hourPx = HOUR_PX;
  readonly legend = [
    { key: 'AUDIENCIA', label: 'Audiência', color: EVENT_COLORS['AUDIENCIA'] },
    { key: 'JULGAMENTO', label: 'Julgamento', color: EVENT_COLORS['JULGAMENTO'] },
    { key: 'SUSTENTACAO_ORAL', label: 'Sustentação oral', color: EVENT_COLORS['SUSTENTACAO_ORAL'] },
    { key: 'REUNIAO', label: 'Reunião', color: EVENT_COLORS['REUNIAO'] },
    { key: 'CONSULTA', label: 'Consulta', color: EVENT_COLORS['CONSULTA'] },
    { key: 'COMPROMISSO', label: 'Compromisso / interno', color: EVENT_COLORS['COMPROMISSO'] },
    { key: 'DEADLINE', label: 'Prazo', color: EVENT_COLORS['DEADLINE'] },
  ];

  readonly view = signal<CalendarView>(window.innerWidth < 700 ? 'day' : 'month');
  readonly ref = signal(startOfDay(new Date()));
  readonly mine = signal(false);
  readonly showDeadlines = signal(true);
  readonly events = signal<CalendarEvent[]>([]);
  readonly loading = signal(false);
  readonly nowTop = signal(this.computeNowTop());

  readonly grid = computed(() => monthGrid(this.ref()));
  readonly columns = computed(() => (this.view() === 'day' ? [this.ref()] : weekDays(this.ref())));
  readonly columnsTemplate = computed(() => `56px repeat(${this.columns().length}, minmax(0, 1fr))`);
  readonly title = computed(() => {
    const text = this.rawTitle();
    return text.charAt(0).toUpperCase() + text.slice(1);
  });
  private readonly rawTitle = computed(() => {
    const r = this.ref();
    if (this.view() === 'day') return DAY_FMT.format(r);
    if (this.view() === 'week') {
      const days = weekDays(r);
      return `${days[0].getDate()} a ${days[6].getDate()} de ${MONTH_FMT.format(days[6])}`;
    }
    return MONTH_FMT.format(r);
  });

  constructor() {
    effect(() => this.load());
    effect(() => {
      // Ao abrir semana/dia, rola até 07:00.
      const el = this.scroller();
      if (el && this.view() !== 'month') {
        queueMicrotask(() => (el.nativeElement.scrollTop = HOUR_PX * 7));
      }
    });
    const timer = setInterval(() => this.nowTop.set(this.computeNowTop()), 60_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    const eventId = this.route.snapshot.queryParamMap.get('evento');
    if (eventId) {
      this.service.get(eventId).subscribe((a) => {
        this.ref.set(startOfDay(new Date(a.start)));
        this.view.set('day');
        this.openAppointment(a);
      });
    }
  }

  load(): void {
    const { from, to } = rangeFor(this.view(), this.ref());
    const mine = this.mine();
    const deadlines = this.showDeadlines() && this.auth.has('DEADLINES_READ');
    this.loading.set(true);
    this.service.events(from, to, mine, deadlines).subscribe({
      next: (e) => { this.events.set(e); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  today(): void {
    this.ref.set(startOfDay(new Date()));
  }

  move(direction: 1 | -1): void {
    this.ref.set(shift(this.view(), this.ref(), direction));
  }

  isToday(day: Date): boolean {
    return sameDay(day, new Date());
  }

  dayEvents(day: Date): CalendarEvent[] {
    return occursOn(this.events(), day);
  }

  allDayEvents(day: Date): CalendarEvent[] {
    return this.dayEvents(day).filter((e) => e.allDay);
  }

  timedEvents(day: Date): CalendarEvent[] {
    return this.dayEvents(day).filter((e) => !e.allDay);
  }

  box(e: CalendarEvent, day: Date) {
    return timeBox(e.start, e.end, day);
  }

  color(e: CalendarEvent): string {
    return e.kind === 'DEADLINE' ? EVENT_COLORS['DEADLINE'] : EVENT_COLORS[e.type] ?? '#4b5563';
  }

  time(iso: string): string {
    return TIME_FMT.format(new Date(iso));
  }

  tooltip(e: CalendarEvent): string {
    const parts = [e.typeLabel, e.title];
    if (e.caseNumber) parts.push(e.caseNumber);
    if (e.clientName) parts.push(e.clientName);
    if (e.responsibleNames?.length) parts.push(e.responsibleNames.join(', '));
    if (e.kind === 'DEADLINE' && e.validated === false) parts.push('contagem não validada');
    return parts.join(' · ');
  }

  openDay(day: Date, event?: Event): void {
    if (event && (event.target as HTMLElement).closest('.chip')) return;
    this.ref.set(startOfDay(day));
    this.view.set('day');
  }

  open(e: CalendarEvent, event: Event): void {
    event.stopPropagation();
    if (e.kind === 'DEADLINE') {
      void this.router.navigate(['/prazos'], { queryParams: { id: e.id } });
      return;
    }
    this.service.get(e.id).subscribe((a) => this.openAppointment(a));
  }

  create(day: Date): void {
    if (!this.auth.has('CALENDAR_WRITE')) return;
    this.openDialog({ date: day });
  }

  createAt(day: Date, event: MouseEvent): void {
    if (!this.auth.has('CALENDAR_WRITE')) return;
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    const hour = Math.max(0, Math.min(23, Math.floor((event.clientY - rect.top) / HOUR_PX)));
    const date = new Date(day);
    date.setHours(hour, 0, 0, 0);
    this.openDialog({ date });
  }

  private openAppointment(a: Appointment): void {
    this.openDialog({ appointment: a });
  }

  private openDialog(data: { appointment?: Appointment; date?: Date }): void {
    this.dialog
      .open(AppointmentDialogComponent, { data, width: '820px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((result?: Appointment | 'deleted') => {
        void this.router.navigate([], { queryParams: { evento: null }, replaceUrl: true });
        if (result === 'deleted') {
          this.toast.success('Compromisso excluído.');
          this.load();
        } else if (result) {
          this.toast.success('Compromisso salvo.');
          this.load();
        }
      });
  }

  private computeNowTop(): number {
    const now = new Date();
    return (now.getHours() + now.getMinutes() / 60) * HOUR_PX;
  }
}
