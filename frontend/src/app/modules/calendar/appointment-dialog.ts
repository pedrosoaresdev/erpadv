import { Component, inject, signal } from '@angular/core';
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
import { Appointment, AppointmentRequest, AppointmentType, LegalCase } from '../../core/models/models';
import { APPOINTMENT_LABELS, REMINDER_OPTIONS, enumOptions } from '../../shared/labels';
import { CasesService } from '../cases/cases.service';
import { DeadlinesService } from '../deadlines/deadlines.service';
import { CalendarService } from './calendar.service';
import { addDays, fromLocal, toLocalDateInput, toLocalTimeInput } from './calendar-utils';

export interface AppointmentDialogData {
  appointment?: Appointment | null;
  date?: Date;
  legalCase?: { id: string; cnjNumber: string | null; clientName: string } | null;
}

/** Resultado: compromisso salvo ou 'deleted'. */
@Component({
  selector: 'app-appointment-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatAutocompleteModule, MatSlideToggleModule,
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

        <mat-form-field class="c6">
          <mat-label>Local</mat-label>
          <input matInput formControlName="location" placeholder="Fórum, sala, endereço" />
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Link da sala virtual</mat-label>
          <input matInput formControlName="meetingUrl" placeholder="https://" />
          @if (form.controls.meetingUrl.invalid) { <mat-error>Use um link https://</mat-error> }
        </mat-form-field>

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
      </form>
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (a && auth.has('CALENDAR_WRITE')) {
        <button mat-button class="danger" type="button" (click)="remove()" [disabled]="saving()"><mat-icon>delete</mat-icon>Excluir</button>
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
    .err { grid-column: span 12; color: var(--app-danger); font-size: 13px; margin: -8px 0 12px; }
    .danger { color: var(--app-danger); }
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
    type: [this.a?.type ?? ((this.data?.legalCase ? 'AUDIENCIA' : 'REUNIAO') as AppointmentType), Validators.required],
    title: [this.a?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    date: [toLocalDateInput(this.startRef), Validators.required],
    startTime: [toLocalTimeInput(this.startRef)],
    endTime: [toLocalTimeInput(this.endRef)],
    allDay: [this.a?.allDay ?? false],
    location: [this.a?.location ?? ''],
    meetingUrl: [this.a?.meetingUrl ?? '', Validators.pattern(/^$|^https:\/\/.+/)],
    responsibleUids: [this.a?.responsibleUids ?? [this.auth.me()?.uid ?? ''].filter(Boolean), Validators.required],
    reminders: [this.a?.reminders ?? [60, 1440]],
    description: [this.a?.description ?? ''],
  });

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
      meetingUrl: v.meetingUrl.trim() || null,
      description: v.description.trim() || null,
      responsibleUids: v.responsibleUids,
      caseId: this.caseId,
      reminders: v.reminders,
    };
    this.saving.set(true);
    const request = this.a ? this.service.update(this.a.id, body) : this.service.create(body);
    request.subscribe({ next: (saved) => this.ref.close(saved), error: () => this.saving.set(false) });
  }

  remove(): void {
    const a = this.a;
    if (!a) return;
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Excluir compromisso', message: `“${a.title}” será excluído e os responsáveis serão notificados. A exclusão fica registrada na auditoria.`, confirmLabel: 'Excluir', danger: true },
        width: '460px',
      })
      .afterClosed()
      .subscribe((ok) => {
        if (!ok) return;
        this.saving.set(true);
        this.service.delete(a.id).subscribe({ next: () => this.ref.close('deleted'), error: () => this.saving.set(false) });
      });
  }
}
