import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { catchError, debounceTime, filter, merge, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import {
  CountingMode,
  Deadline,
  DeadlineCalculation,
  DeadlinePriority,
  DeadlineRequest,
  DeadlineType,
  LegalCase,
} from '../../core/models/models';
import { COUNTING_LABELS, DEADLINE_TYPE_LABELS, PRIORITY_LABELS, enumOptions } from '../../shared/labels';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { CasesService } from '../cases/cases.service';
import { DeadlinesService } from './deadlines.service';

export interface DeadlineFormData {
  deadline?: Deadline | null;
  legalCase?: { id: string; cnjNumber: string | null; clientName: string; court: string | null } | null;
}

interface CasePick {
  id: string;
  label: string;
  court: string | null;
}

export const todayIso = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** Cadastro/edição de prazo com simulação da contagem (feriados, recesso e suspensões). */
@Component({
  selector: 'app-deadline-form-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatAutocompleteModule, MatProgressSpinnerModule, BrDatePipe,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>alarm_add</mat-icon>{{ editing ? 'Editar prazo' : 'Novo prazo' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="deadlineForm" (ngSubmit)="save()">
        <div class="form-section-title">Vínculo</div>
        <mat-form-field class="c8">
          <mat-label>Processo (opcional)</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ, cliente ou parte" />
          <mat-icon matSuffix>gavel</mat-icon>
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="displayCase" (optionSelected)="pickCase($event.option.value)">
            @for (c of caseOptions(); track c.id) {
              <mat-option [value]="c">{{ c.label }}</mat-option>
            }
          </mat-autocomplete>
          <mat-hint>Cliente e tribunal são herdados do processo</mat-hint>
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Prioridade</mat-label>
          <mat-select formControlName="priority">
            @for (o of priorities; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>

        <div class="form-section-title">Prazo</div>
        <mat-form-field class="c4">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">
            @for (o of types; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="c8">
          <mat-label>Descrição</mat-label>
          <input matInput formControlName="description" maxlength="300" placeholder="Ex.: Apresentar contestação" />
          @if (form.controls.description.invalid) { <mat-error>Informe a descrição</mat-error> }
        </mat-form-field>

        <mat-form-field class="c4">
          <mat-label>Data da intimação / início</mat-label>
          <input matInput type="date" formControlName="startDate" />
          @if (form.controls.startDate.invalid) { <mat-error>Obrigatória</mat-error> }
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Contagem</mat-label>
          <mat-select formControlName="countingMode">
            @for (o of modes; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        @if (form.controls.countingMode.value === 'MANUAL') {
          <mat-form-field class="c4">
            <mat-label>Vencimento</mat-label>
            <input matInput type="date" formControlName="dueDate" [min]="form.controls.startDate.value" />
            @if (form.controls.dueDate.invalid) { <mat-error>Informe o vencimento</mat-error> }
          </mat-form-field>
        } @else {
          <mat-form-field class="c4">
            <mat-label>Quantidade de dias</mat-label>
            <input matInput type="number" min="1" max="3650" formControlName="days" />
            @if (form.controls.days.invalid) { <mat-error>Entre 1 e 3650</mat-error> }
          </mat-form-field>
        }
        <mat-form-field class="c4">
          <mat-label>Tribunal</mat-label>
          <input matInput formControlName="court" placeholder="TJSP" />
          <mat-hint>Aplica feriados/suspensões cadastrados</mat-hint>
        </mat-form-field>
        <mat-form-field class="c8">
          <mat-label>Responsável</mat-label>
          <mat-select formControlName="responsibleUid">
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          @if (form.controls.responsibleUid.invalid) { <mat-error>Selecione o responsável</mat-error> }
        </mat-form-field>

        @if (form.controls.countingMode.value !== 'MANUAL') {
          <div class="calc" [class.loading]="calculating()">
            @if (calculating()) {
              <mat-spinner diameter="22" />
            } @else if (calculation(); as c) {
              <div class="calc-head">
                <mat-icon>event_available</mat-icon>
                <div>
                  <div class="muted small">Vencimento calculado</div>
                  <div class="due">{{ c.dueDate | brDate }}</div>
                </div>
              </div>
              @if (c.skippedDays.length) {
                <details>
                  <summary>{{ c.skippedDays.length }} dia(s) não contado(s)</summary>
                  <ul>
                    @for (s of c.skippedDays; track s.date) { <li><span class="mono">{{ s.date | brDate }}</span> — {{ s.reason }}</li> }
                  </ul>
                </details>
              }
              <p class="warn"><mat-icon inline>gavel</mat-icon>A contagem é uma sugestão e deve ser validada pelo advogado responsável.</p>
            } @else {
              <span class="muted">Informe data inicial e quantidade de dias para calcular o vencimento.</span>
            }
          </div>
        }

        <mat-form-field>
          <mat-label>Observações</mat-label>
          <textarea matInput rows="3" formControlName="notes" maxlength="4000"></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="deadlineForm" [disabled]="saving()"><mat-icon>save</mat-icon>{{ saving() ? 'Salvando…' : 'Salvar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 74vh; }
    .calc { grid-column: span 12; border: 1px solid var(--app-border); border-left: 3px solid var(--app-gold); border-radius: 10px;
      padding: 14px 16px; margin: 4px 0 18px; background: var(--app-surface-2); min-height: 60px; }
    .calc.loading { display: grid; place-items: center; }
    .calc-head { display: flex; gap: 12px; align-items: center; }
    .calc-head mat-icon { color: var(--app-gold); }
    .due { font-size: 20px; font-weight: 700; }
    .small { font-size: 12px; }
    details { margin-top: 8px; font-size: 13px; }
    summary { cursor: pointer; color: var(--app-text-muted); }
    ul { margin: 6px 0 0; padding-left: 18px; line-height: 1.6; }
    .warn { margin: 10px 0 0; font-size: 12px; color: var(--app-warning); display: flex; gap: 6px; align-items: center; }
  `,
})
export class DeadlineFormDialogComponent {
  private readonly data = inject<DeadlineFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DeadlineFormDialogComponent>);
  private readonly service = inject(DeadlinesService);
  private readonly cases = inject(CasesService);
  private readonly auth = inject(AuthService);

  private readonly d = this.data?.deadline ?? null;
  readonly editing = !!this.d;
  readonly types = enumOptions(DEADLINE_TYPE_LABELS);
  readonly modes = enumOptions(COUNTING_LABELS);
  readonly priorities = enumOptions(PRIORITY_LABELS);
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly caseOptions = signal<CasePick[]>([]);
  readonly calculation = signal<DeadlineCalculation | null>(null);
  readonly calculating = signal(false);
  readonly saving = signal(false);

  private caseId: string | null = this.d?.caseId ?? this.data?.legalCase?.id ?? null;
  private readonly initialCase: CasePick | null = this.d?.caseId
    ? { id: this.d.caseId, label: `${this.d.caseNumber ?? 'Sem CNJ'} · ${this.d.clientName ?? ''}`, court: this.d.court }
    : this.data?.legalCase
      ? { id: this.data.legalCase.id, label: `${this.data.legalCase.cnjNumber ?? 'Sem CNJ'} · ${this.data.legalCase.clientName}`, court: this.data.legalCase.court }
      : null;
  readonly caseSearch = new FormControl<CasePick | string>(this.initialCase ?? '');

  readonly form = inject(FormBuilder).nonNullable.group({
    description: [this.d?.description ?? '', [Validators.required, Validators.maxLength(300)]],
    type: [this.d?.type ?? ('MANIFESTACAO' as DeadlineType), Validators.required],
    startDate: [this.d?.startDate ?? todayIso(), Validators.required],
    countingMode: [this.d?.countingMode ?? ('UTEIS' as CountingMode), Validators.required],
    days: [this.d?.days ?? 15, [Validators.min(1), Validators.max(3650)]],
    dueDate: [this.d?.dueDate ?? ''],
    court: [this.d?.court ?? this.initialCase?.court ?? ''],
    responsibleUid: [this.d?.responsibleUid ?? this.auth.me()?.uid ?? '', Validators.required],
    priority: [this.d?.priority ?? ('MEDIA' as DeadlinePriority), Validators.required],
    notes: [this.d?.notes ?? ''],
  });

  constructor() {
    this.caseSearch.valueChanges
      .pipe(
        debounceTime(250),
        filter((v): v is string => typeof v === 'string'),
        switchMap((term) => {
          this.caseId = null;
          return term.trim().length >= 2
            ? this.cases.list({ search: term.trim(), includeArchived: false }, null, 8).pipe(catchError(() => of({ items: [], nextCursor: null })))
            : of({ items: [] as LegalCase[], nextCursor: null });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) =>
        this.caseOptions.set(page.items.map((c) => ({ id: c.id, label: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}`, court: c.court }))),
      );

    const c = this.form.controls;
    merge(c.startDate.valueChanges, c.days.valueChanges, c.countingMode.valueChanges, c.court.valueChanges)
      .pipe(debounceTime(350), takeUntilDestroyed())
      .subscribe(() => this.recalculate());
    this.recalculate();
  }

  displayCase = (v: CasePick | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  pickCase(c: CasePick): void {
    this.caseId = c.id;
    if (c.court && !this.form.controls.court.value) {
      this.form.controls.court.setValue(c.court);
    }
  }

  recalculate(): void {
    const v = this.form.getRawValue();
    if (v.countingMode === 'MANUAL' || !v.startDate || !v.days || v.days < 1) {
      this.calculation.set(null);
      return;
    }
    this.calculating.set(true);
    this.service.calculate({ startDate: v.startDate, days: Number(v.days), countingMode: v.countingMode, court: v.court || null }).subscribe({
      next: (r) => { this.calculation.set(r); this.calculating.set(false); },
      error: () => { this.calculation.set(null); this.calculating.set(false); },
    });
  }

  save(): void {
    const v = this.form.getRawValue();
    if (v.countingMode === 'MANUAL' && !v.dueDate) {
      this.form.controls.dueDate.setErrors({ required: true });
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const body: DeadlineRequest = {
      caseId: this.caseId,
      description: v.description.trim(),
      type: v.type,
      startDate: v.startDate,
      countingMode: v.countingMode,
      days: v.countingMode === 'MANUAL' ? null : Number(v.days),
      dueDate: v.countingMode === 'MANUAL' ? v.dueDate : null,
      court: v.court.trim() || null,
      responsibleUid: v.responsibleUid,
      priority: v.priority,
      notes: v.notes.trim() || null,
    };
    this.saving.set(true);
    const request = this.d ? this.service.update(this.d.id, body) : this.service.create(body);
    request.subscribe({ next: (saved) => this.ref.close(saved), error: () => this.saving.set(false) });
  }
}
