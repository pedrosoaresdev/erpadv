import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { InteractionChannel } from '../../core/models/models';
import { CHANNEL_LABELS, enumOptions } from '../../shared/labels';
import { ClientsService } from './clients.service';

const localNow = () => {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
};

/** Registro de atendimento ao cliente. */
@Component({
  selector: 'app-interaction-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>support_agent</mat-icon>Registrar atendimento</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="interactionForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Canal</mat-label>
          <mat-select formControlName="channel">
            @for (o of channels; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Data e hora</mat-label>
          <input matInput type="datetime-local" formControlName="occurredAt" [max]="max" />
          @if (form.controls.occurredAt.invalid) { <mat-error>Informe a data</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Assunto</mat-label>
          <input matInput formControlName="subject" maxlength="200" />
          @if (form.controls.subject.invalid) { <mat-error>Informe o assunto</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Descrição</mat-label>
          <textarea matInput rows="5" formControlName="description" maxlength="4000"></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="interactionForm" [disabled]="saving()">Registrar</button>
    </mat-dialog-actions>
  `,
})
export class InteractionDialogComponent {
  private readonly clientId = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<InteractionDialogComponent>);
  private readonly service = inject(ClientsService);
  readonly channels = enumOptions(CHANNEL_LABELS);
  readonly max = localNow();
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    channel: ['PRESENCIAL' as InteractionChannel, Validators.required],
    occurredAt: [localNow(), Validators.required],
    subject: ['', [Validators.required, Validators.maxLength(200)]],
    description: [''],
  });

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service
      .addInteraction(this.clientId, {
        channel: v.channel,
        subject: v.subject.trim(),
        description: v.description.trim() || null,
        occurredAt: new Date(v.occurredAt).toISOString(),
      })
      .subscribe({ next: (i) => this.ref.close(i), error: () => this.saving.set(false) });
  }
}
