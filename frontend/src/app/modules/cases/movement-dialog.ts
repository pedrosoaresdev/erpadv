import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MovementType } from '../../core/models/models';
import { MOVEMENT_LABELS, enumOptions } from '../../shared/labels';
import { CasesService } from './cases.service';

const todayLocal = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

/** Registro de movimentação, decisão, despacho, sentença etc. */
@Component({
  selector: 'app-movement-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  styles: `.imp { grid-column: span 12; display: flex; flex-direction: column; gap: 2px; } .small { font-size: 12px; padding-left: 8px; }`,
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>post_add</mat-icon>Registrar andamento</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="movementForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">
            @for (o of types; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Data do ato</mat-label>
          <input matInput type="date" formControlName="date" [max]="max" />
          @if (form.controls.date.invalid) { <mat-error>Informe a data</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Título</mat-label>
          <input matInput formControlName="title" maxlength="200" placeholder="Ex.: Deferida tutela de urgência" />
          @if (form.controls.title.invalid) { <mat-error>Informe o título</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Teor / descrição</mat-label>
          <textarea matInput rows="6" formControlName="description" maxlength="8000"></textarea>
        </mat-form-field>
        <div class="imp">
          <mat-checkbox formControlName="important">Marcar como importante (destaque e aviso ao responsável)</mat-checkbox>
          <span class="muted small">Sentenças, acórdãos, decisões e trânsito em julgado são importantes automaticamente.
            Recurso muda a situação para "Em recurso"; suspensão, para "Suspenso".</span>
        </div>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="movementForm" [disabled]="saving()">Registrar</button>
    </mat-dialog-actions>
  `,
})
export class MovementDialogComponent {
  private readonly caseId = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<MovementDialogComponent>);
  private readonly service = inject(CasesService);
  readonly types = enumOptions(MOVEMENT_LABELS, ['SISTEMA']);
  readonly max = todayLocal();
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    type: ['MOVIMENTACAO' as MovementType, Validators.required],
    date: [todayLocal(), Validators.required],
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
    important: [false],
  });

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service
      .addMovement(this.caseId, { type: v.type, date: v.date, title: v.title.trim(), description: v.description.trim() || null, important: v.important })
      .subscribe({ next: (m) => this.ref.close(m), error: () => this.saving.set(false) });
  }
}
