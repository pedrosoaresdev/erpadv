import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

export interface ConfirmData {
  title: string;
  message: string;
  confirmLabel?: string;
  danger?: boolean;
  /** Quando informado, exige um texto (ex.: motivo) e o devolve no resultado. */
  reasonLabel?: string;
  reasonRequired?: boolean;
}

/** Diálogo de confirmação; retorna false/undefined, true ou o motivo digitado. */
@Component({
  selector: 'app-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>{{ data.danger ? 'warning_amber' : 'help_outline' }}</mat-icon>{{ data.title }}
    </h2>
    <mat-dialog-content>
      <p>{{ data.message }}</p>
      @if (data.reasonLabel) {
        <mat-form-field appearance="outline">
          <mat-label>{{ data.reasonLabel }}</mat-label>
          <textarea matInput rows="3" [formControl]="reason" maxlength="500"></textarea>
          @if (reason.hasError('required')) { <mat-error>Campo obrigatório</mat-error> }
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [class.danger]="data.danger" (click)="confirm()">{{ data.confirmLabel ?? 'Confirmar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    p { margin: 0 0 16px; line-height: 1.5; }
    .danger { --mdc-filled-button-container-color: #b42318; --mat-button-filled-container-color: #b42318; }
  `,
})
export class ConfirmDialogComponent {
  readonly data = inject<ConfirmData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConfirmDialogComponent>);
  readonly reason = new FormControl('', this.data.reasonRequired ? [Validators.required] : []);

  confirm(): void {
    if (this.data.reasonLabel) {
      if (this.reason.invalid) {
        this.reason.markAsTouched();
        return;
      }
      this.ref.close(this.reason.value?.trim() ?? '');
      return;
    }
    this.ref.close(true);
  }
}
