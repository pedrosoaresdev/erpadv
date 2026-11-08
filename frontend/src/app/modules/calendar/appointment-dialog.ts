import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Appointment, AppointmentRequest, AppointmentStatus, AppointmentType, LegalCase, MeetingPlatform } from '../../core/models/models';
import { MEETING_PLATFORMS, linkMatches, platformInfo } from './meeting-platforms';
import { MatMenuModule } from '@angular/material/menu';
import { ToastService } from '../../core/services/toast.service';
import { HistoryDialogComponent } from '../../shared/components/history-dialog';
import { saveBlob } from '../finance/finance.service';
import { APPOINTMENT_LABELS, APPOINTMENT_STATUS_LABELS, REMINDER_OPTIONS, RSVP_LABELS, enumOptions } from '../../shared/labels';
import { CasesService } from '../cases/cases.service';
import { DeadlinesService } from '../deadlines/deadlines.service';
import { CalendarService } from './calendar.service';
import { addDays, fromLocal, toLocalDateInput, toLocalTimeInput } from './calendar-utils';

export interface AppointmentDialogData {
  appointment?: Appointment | null;
  date?: Date;
  legalCase?: { id: string; cnjNumber: string | null; clientName: string } | null;
  /** Nova reunião: tipo e plataforma pré-selecionados. */
  type?: AppointmentType;
  platform?: MeetingPlatform;
}

/** Resultado: compromisso salvo ou 'deleted'. */
@Component({
  selector: 'app-appointment-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatAutocompleteModule, MatSlideToggleModule, MatMenuModule,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ a ? 'edit_calendar' : 'event' }}</mat-icon>{{ a ? 'Editar compromisso' : 'Novo compromisso' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="apptForm" (ngSubmit)="save()">
        <mat-form-field class="c4">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">
            @for (o of types; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="c8">
          <mat-label>Título</mat-label>
          <input matInput formControlName="title" maxlength="200" />
          @if (form.controls.title.invalid) { <mat-error>Informe o título</mat-error> }
        </mat-form-field>

        <mat-form-field class="c4">
          <mat-label>Data</mat-label>
          <input matInput type="date" formControlName="date" />
        </mat-form-field>
        @if (!form.controls.allDay.value) {
          <mat-form-field class="c3"><mat-label>Início</mat-label><input matInput type="time" formControlName="startTime" /></mat-form-field>
          <mat-form-field class="c3"><mat-label>Término</mat-label><input matInput type="time" formControlName="endTime" /></mat-form-field>
        }
        <div class="toggle c2"><mat-slide-toggle formControlName="allDay">Dia inteiro</mat-slide-toggle></div>
        @if (periodError()) { <div class="err">O término deve ser posterior ao início.</div> }
        @if (!a) {
          <mat-form-field class="c6">
            <mat-label>Repetir</mat-label>
            <mat-select formControlName="recurrence">
              <mat-option value="">Não repetir</mat-option>
              <mat-option value="DIARIA">Diariamente</mat-option>
              <mat-option value="SEMANAL">Semanalmente</mat-option>
              <mat-option value="QUINZENAL">A cada 2 semanas</mat-option>
              <mat-option value="MENSAL">Mensalmente</mat-option>
            </mat-select>
          </mat-form-field>
          @if (form.controls.recurrence.value) {
            <mat-form-field class="c6">
              <mat-label>Número de ocorrências</mat-label>
              <input matInput type="number" min="2" max="52" formControlName="occurrences" />
              <mat-hint>Máximo de 52</mat-hint>
            </mat-form-field>
          }
        } @else if (a.seriesId) {
          <p class="series c12"><mat-icon inline>repeat</mat-icon> Ocorrência {{ a.seriesIndex }} de uma série recorrente.
            <label><input type="checkbox" [checked]="applySeries()" (change)="applySeries.set(!applySeries())" /> aplicar alterações às próximas ocorrências</label></p>
        }

        <mat-form-field class="c6">
          <mat-label>Local</mat-label>
          <input matInput formControlName="location" placeholder="Fórum, sala, endereço" />
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Plataforma</mat-label>
          <mat-select formControlName="meetingPlatform">
            <mat-option value="">Sem videoconferência</mat-option>
            @for (p of platforms; track p.key) { <mat-option [value]="p.key"><mat-icon>{{ p.icon }}</mat-icon>{{ p.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        @if (form.controls.meetingPlatform.value && form.controls.meetingPlatform.value !== 'PRESENCIAL') {
          <mat-form-field class="c6">
            <mat-label>Link da sala ({{ platformLabel() }})</mat-label>
            <input matInput formControlName="meetingUrl" placeholder="https://" />
            @if (form.controls.meetingUrl.hasError('pattern')) { <mat-error>Use um link https://</mat-error> }
            @else if (form.controls.meetingUrl.hasError('platform')) { <mat-error>O link não é do {{ platformLabel() }}</mat-error> }
          </mat-form-field>
          @if (createInfo(); as c) {
            <p class="c6 platform-help muted small">
              <button mat-stroked-button type="button" (click)="openCreate(c.createUrl!)"><mat-icon>open_in_new</mat-icon>{{ c.createLabel }}</button>
              Crie a sala na sua conta e cole aqui o link gerado.
            </p>
          }
        }

        <mat-form-field class="c6">
          <mat-label>Responsáveis</mat-label>
          <mat-select formControlName="responsibleUids" multiple>
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          @if (form.controls.responsibleUids.invalid) { <mat-error>Selecione ao menos um responsável</mat-error> }
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Lembretes</mat-label>
          <mat-select formControlName="reminders" multiple>
            @for (r of reminderOptions; track r.value) { <mat-option [value]="r.value">{{ r.label }}</mat-option> }
          </mat-select>
          <mat-hint>Enviados como notificação aos responsáveis</mat-hint>
        </mat-form-field>

        <mat-form-field>
          <mat-label>Processo vinculado (opcional)</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ, cliente ou parte" />
          <mat-icon matSuffix>gavel</mat-icon>
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="displayCase" (optionSelected)="caseId = $event.option.value.id">
            @for (c of caseOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
          </mat-autocomplete>
        </mat-form-field>

        <mat-form-field>
          <mat-label>Descrição / pauta</mat-label>
          <textarea matInput rows="3" formControlName="description" maxlength="4000"></textarea>
        </mat-form-field>

        <div class="form-section-title row c12">Convidados externos (cliente, perito, testemunha)
          <span class="spacer"></span>
          <button mat-button type="button" (click)="addGuest()" [disabled]="guests().length >= 20"><mat-icon>person_add</mat-icon>Adicionar</button></div>
        @for (g of guests(); track $index; let i = $index) {
          <div class="guest c12">
            <input class="gi" placeholder="Nome" [value]="g.name" (input)="setGuest(i, 'name', $event)" aria-label="Nome do convidado" />
            <input class="gi" placeholder="E-mail (opcional)" [value]="g.email" (input)="setGuest(i, 'email', $event)" aria-label="E-mail do convidado" />
            <button mat-icon-button type="button" (click)="removeGuest(i)" aria-label="Remover convidado"><mat-icon>close</mat-icon></button>
          </div>
        }
        @if (guests().length) { <p class="muted small c12">Os convidados recebem o arquivo .ics pelo botão "Exportar"; o envio automático por e-mail chega com a integração de e-mail (Fase 4).</p> }
      </form>

      @if (a) {
        <div class="form-section-title">Participantes e situação</div>
        <div class="att">
          @for (uid of a.responsibleUids; track uid; let i = $index) {
            <span class="badge" [class.success]="attendance(uid) === 'CONFIRMADO'" [class.danger]="attendance(uid) === 'RECUSADO'">
              {{ a.responsibleNames[i] }} · {{ rsvpLabels[attendance(uid)] }}
            </span>
          }
        </div>
        @if (a.responsibleUids.includes(auth.me()?.uid ?? '')) {
          <div class="row rsvp">
            <span class="muted small">Sua resposta:</span>
            <button mat-stroked-button type="button" (click)="rsvp('CONFIRMADO')"><mat-icon>check</mat-icon>Confirmar</button>
            <button mat-stroked-button type="button" (click)="rsvp('TALVEZ')">Talvez</button>
            <button mat-stroked-button type="button" (click)="rsvp('RECUSADO')"><mat-icon>close</mat-icon>Recusar</button>
          </div>
        }
        <p class="status"><span class="badge info">{{ a.statusLabel }}</span> @if (a.outcome) { <span class="pre">{{ a.outcome }}</span> }</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (a && auth.has('CALENDAR_WRITE')) {
        <button mat-button class="danger" type="button" (click)="remove()" [disabled]="saving()"><mat-icon>delete</mat-icon>Excluir</button>
      }
      @if (a) {
        <button mat-button type="button" [matMenuTriggerFor]="moreMenu"><mat-icon>more_horiz</mat-icon>Mais</button>
        <mat-menu #moreMenu="matMenu">
          @if (auth.has('CALENDAR_WRITE')) {
            <button mat-menu-item (click)="changeStatus('REALIZADO')"><mat-icon>task_alt</mat-icon>Marcar como realizado (registrar resultado)</button>
            <button mat-menu-item (click)="changeStatus('REDESIGNADO')"><mat-icon>event_repeat</mat-icon>Redesignado</button>
            <button mat-menu-item (click)="changeStatus('ADIADO')"><mat-icon>schedule</mat-icon>Adiado</button>
            <button mat-menu-item (click)="changeStatus('CANCELADO')"><mat-icon>event_busy</mat-icon>Cancelado</button>
            <button mat-menu-item (click)="changeStatus('AGENDADO')"><mat-icon>event</mat-icon>Voltar para agendado</button>
          }
          <button mat-menu-item (click)="exportIcs()"><mat-icon>event_available</mat-icon>Exportar .ics (Google Agenda/Outlook)</button>
          <button mat-menu-item (click)="showHistory()"><mat-icon>history</mat-icon>Histórico de alterações</button>
        </mat-menu>
      }
      <span class="spacer"></span>
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      @if (auth.has('CALENDAR_WRITE')) {
        <button mat-flat-button type="submit" form="apptForm" [disabled]="saving()"><mat-icon>save</mat-icon>Salvar</button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 74vh; }
    .toggle { display: flex; align-items: center; height: 56px; }
    .platform-help { grid-column: span 12; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: -6px 0 12px; font-size: 12px; }
    .err { grid-column: span 12; color: var(--app-danger); font-size: 13px; margin: -8px 0 12px; }
    .danger { color: var(--app-danger); }
    .c12 { grid-column: span 12; }
    .series { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--app-text-muted); margin: -4px 0 12px; }
    .guest { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
    .gi { flex: 1; min-width: 0; border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .small { font-size: 12px; }
    .att { display: flex; gap: 6px; flex-wrap: wrap; }
    .rsvp { margin-top: 10px; flex-wrap: wrap; }
    .status { margin-top: 12px; display: flex; gap: 8px; align-items: flex-start; }
    .pre { white-space: pre-wrap; font-size: 13px; }
    mat-dialog-actions { display: flex; flex-wrap: wrap; gap: 6px; }
  `,
})
export class AppointmentDialogComponent {
  private readonly data = inject<AppointmentDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<AppointmentDialogComponent>);
  private readonly service = inject(CalendarService);
  private readonly cases = inject(CasesService);
  private readonly dialog = inject(MatDialog);
  readonly auth = inject(AuthService);

  readonly a = this.data?.appointment ?? null;
  readonly types = enumOptions(APPOINTMENT_LABELS);
  readonly reminderOptions = REMINDER_OPTIONS;
  readonly staff = toSignal(inject(DeadlinesService).staff(), { initialValue: [] });
  readonly caseOptions = signal<{ id: string; label: string }[]>([]);
  readonly saving = signal(false);
  readonly periodError = signal(false);

  caseId: string | null = this.a?.caseId ?? this.data?.legalCase?.id ?? null;
  private readonly initialCaseLabel = this.a?.caseId
    ? `${this.a.caseNumber ?? 'Sem CNJ'} · ${this.a.clientName ?? ''}`
    : this.data?.legalCase
      ? `${this.data.legalCase.cnjNumber ?? 'Sem CNJ'} · ${this.data.legalCase.clientName}`
      : '';
  readonly caseSearch = new FormControl<{ id: string; label: string } | string>(
    this.caseId ? { id: this.caseId, label: this.initialCaseLabel } : '',
  );

  private readonly startRef = this.a ? new Date(this.a.start) : this.defaultStart();
  private readonly endRef = this.a ? new Date(this.a.end) : new Date(this.startRef.getTime() + 3_600_000);

  readonly form = inject(FormBuilder).nonNullable.group({
    type: [this.a?.type ?? this.data?.type ?? ((this.data?.legalCase ? 'AUDIENCIA' : 'REUNIAO') as AppointmentType), Validators.required],
    title: [this.a?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    date: [toLocalDateInput(this.startRef), Validators.required],
    startTime: [toLocalTimeInput(this.startRef)],
    endTime: [toLocalTimeInput(this.endRef)],
    allDay: [this.a?.allDay ?? false],
    location: [this.a?.location ?? ''],
    meetingUrl: [this.a?.meetingUrl ?? '', Validators.pattern(/^$|^https:\/\/.+/)],
    meetingPlatform: [(this.a?.meetingPlatform ?? this.data?.platform ?? '') as MeetingPlatform | ''],
    responsibleUids: [this.a?.responsibleUids ?? [this.auth.me()?.uid ?? ''].filter(Boolean), Validators.required],
    reminders: [this.a?.reminders ?? [60, 1440]],
    description: [this.a?.description ?? ''],
    recurrence: [''],
    occurrences: [4, [Validators.min(2), Validators.max(52)]],
  });
  readonly guests = signal<{ name: string; email: string }[]>((this.a?.guests ?? []).map((g) => ({ name: g.name, email: g.email ?? '' })));
  readonly applySeries = signal(false);
  readonly rsvpLabels = RSVP_LABELS;
  private readonly toast = inject(ToastService);

  attendance(uid: string): string {
    return this.a?.attendance?.[uid] ?? 'PENDENTE';
  }

  addGuest(): void {
    this.guests.set([...this.guests(), { name: '', email: '' }]);
  }

  setGuest(i: number, field: 'name' | 'email', event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.guests.set(this.guests().map((g, idx) => (idx === i ? { ...g, [field]: value } : g)));
  }

  removeGuest(i: number): void {
    this.guests.set(this.guests().filter((_, idx) => idx !== i));
  }

  rsvp(status: 'CONFIRMADO' | 'RECUSADO' | 'TALVEZ'): void {
    if (!this.a) return;
    this.service.rsvp(this.a.id, status).subscribe((updated) => {
      this.toast.success('Resposta registrada.');
      this.ref.close(updated);
    });
  }

  changeStatus(status: AppointmentStatus): void {
    const a = this.a;
    if (!a) return;
    const needsText = status === 'REALIZADO' || status === 'REDESIGNADO' || status === 'ADIADO' || status === 'CANCELADO';
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: APPOINTMENT_STATUS_LABELS[status], message: a.title, confirmLabel: 'Salvar',
        reasonLabel: status === 'REALIZADO' ? 'Resultado / ata (ex.: acordo, instrução encerrada)' : 'Observação', reasonRequired: false },
      width: '520px',
    }).afterClosed().subscribe((text) => {
      if (typeof text !== 'string' && text !== true) return;
      this.service.setStatus(a.id, status, needsText && typeof text === 'string' ? text || null : null).subscribe((u) => {
        this.toast.success('Situação atualizada.');
        this.ref.close(u);
      });
    });
  }

  exportIcs(): void {
    if (!this.a) return;
    this.service.ics(this.a.id).subscribe((r) => saveBlob(r, 'compromisso.ics'));
  }

  showHistory(): void {
    const a = this.a;
    if (!a) return;
    this.dialog.open(HistoryDialogComponent, { data: { title: 'Histórico · ' + a.title, load: () => this.service.history(a.id) }, maxWidth: '96vw' });
  }

  constructor() {
    this.caseSearch.valueChanges
      .pipe(
        debounceTime(250),
        filter((v): v is string => typeof v === 'string'),
        switchMap((term) => {
          this.caseId = null;
          return term.trim().length >= 2
            ? this.cases.list({ search: term.trim() }, null, 8).pipe(catchError(() => of({ items: [] as LegalCase[], nextCursor: null })))
            : of({ items: [] as LegalCase[], nextCursor: null });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.caseOptions.set(page.items.map((c) => ({ id: c.id, label: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}` }))));
    if (!this.auth.has('CALENDAR_WRITE')) {
      this.form.disable();
    }
    // Valida o domínio do link conforme a plataforma escolhida.
    const check = () => {
      const c = this.form.controls.meetingUrl;
      const errors = { ...(c.errors ?? {}) };
      delete errors['platform'];
      if (c.value && !errors['pattern'] && !linkMatches(this.form.controls.meetingPlatform.value || null, c.value.trim())) errors['platform'] = true;
      c.setErrors(Object.keys(errors).length ? errors : null);
      this.platformKey.set(this.form.controls.meetingPlatform.value);
    };
    this.form.controls.meetingUrl.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => setTimeout(check));
    this.form.controls.meetingPlatform.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => setTimeout(check));
    this.platformKey.set(this.form.controls.meetingPlatform.value);
  }

  readonly platforms = MEETING_PLATFORMS;
  private readonly platformKey = signal<MeetingPlatform | ''>('');
  readonly platformLabel = computed(() => platformInfo(this.platformKey() || null)?.label ?? 'link');
  readonly createInfo = computed(() => {
    const info = platformInfo(this.platformKey() || null);
    return info?.createUrl ? info : null;
  });

  openCreate(url: string): void {
    window.open(url, '_blank', 'noopener');
  }

  displayCase = (v: { id: string; label: string } | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  private defaultStart(): Date {
    const base = this.data?.date ? new Date(this.data.date) : new Date();
    if (!this.data?.date || (base.getHours() === 0 && base.getMinutes() === 0)) {
      const now = new Date();
      base.setHours(Math.min(Math.max(now.getHours() + 1, 8), 18), 0, 0, 0);
    }
    return base;
  }

  save(): void {
    this.periodError.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const start = v.allDay ? fromLocal(v.date, '00:00') : fromLocal(v.date, v.startTime);
    const end = v.allDay ? addDays(fromLocal(v.date, '00:00'), 1) : fromLocal(v.date, v.endTime);
    if (end.getTime() <= start.getTime()) {
      this.periodError.set(true);
      return;
    }
    const body: AppointmentRequest = {
      type: v.type,
      title: v.title.trim(),
      start: start.toISOString(),
      end: end.toISOString(),
      allDay: v.allDay,
      location: v.location.trim() || null,
      meetingUrl: v.meetingPlatform && v.meetingPlatform !== 'PRESENCIAL' ? v.meetingUrl.trim() || null : null,
      meetingPlatform: v.meetingPlatform || null,
      description: v.description.trim() || null,
      responsibleUids: v.responsibleUids,
      caseId: this.caseId,
      reminders: v.reminders,
      guests: this.guests().filter((g) => g.name.trim()).map((g) => ({ name: g.name.trim(), email: g.email.trim() || null })),
      recurrence: this.a ? null : v.recurrence || null,
      occurrences: this.a || !v.recurrence ? null : Number(v.occurrences),
    };
    this.saving.set(true);
    const request = this.a ? this.service.update(this.a.id, body, this.applySeries()) : this.service.create(body);
    request.subscribe({ next: (saved) => this.ref.close(saved), error: () => this.saving.set(false) });
  }

  remove(): void {
    const a = this.a;
    if (!a) return;
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Excluir compromisso', message: `“${a.title}” será excluído${a.seriesId && this.applySeries() ? ' junto com as próximas ocorrências da série' : ''} e os responsáveis serão notificados. A exclusão fica registrada na auditoria.`, confirmLabel: 'Excluir', danger: true },
        width: '460px',
      })
      .afterClosed()
      .subscribe((ok) => {
        if (!ok) return;
        this.saving.set(true);
        this.service.delete(a.id, !!a.seriesId && this.applySeries()).subscribe({ next: () => this.ref.close('deleted'), error: () => this.saving.set(false) });
      });
  }
}
