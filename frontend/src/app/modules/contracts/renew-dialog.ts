import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import { Contract, ContractsService } from './contracts.service';

/** Renovação: novo término e, no partido mensal, meses adicionais com reajuste opcional da mensalidade. */
@Component({
  selector: 'app-contract-renew-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>autorenew</mat-icon>Renovar contrato {{ c.number }}</h2>
    <mat-dialog-content>
      <p class="muted small">Término atual: {{ c.endDate ? c.endDate.split('-').reverse().join('/') : 'indeterminado' }}.
        @if (monthly) { Novas mensalidades são lançadas no contas a receber a partir da última parcela. }</p>
      <form [formGroup]="form" class="form-grid" id="renewForm" (ngSubmit)="save()">
        <mat-form-field class="c6"><mat-label>Novo término</mat-label><input matInput type="date" formControlName="newEndDate" /></mat-form-field>
        @if (monthly) {
          <mat-form-field class="c6"><mat-label>Meses adicionais</mat-label><input matInput type="number" min="0" max="60" formControlName="monthsAdded" /></mat-form-field>
          <mat-form-field class="c6"><mat-label>Mensalidade (R$)</mat-label><input matInput formControlName="monthly" inputmode="decimal" /><mat-hint>Altere para aplicar reajuste</mat-hint></mat-form-field>
        }
        <mat-form-field><mat-label>Observação</mat-label><input matInput formControlName="note" maxlength="500" placeholder="Ex.: reajuste pelo IPCA de 4,5%" /></mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="renewForm" [disabled]="saving()"><mat-icon>autorenew</mat-icon>Renovar</button>
    </mat-dialog-actions>
  `,
  styles: `.small { font-size: 12px; }`,
})
export class ContractRenewDialogComponent {
  readonly c = inject<Contract>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ContractRenewDialogComponent>);
  private readonly service = inject(ContractsService);
  readonly monthly = this.c.feeModel === 'MENSAL';
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    newEndDate: ['', Validators.required],
    monthsAdded: [this.monthly ? 12 : 0, [Validators.min(0), Validators.max(60)]],
    monthly: [centsToInput(this.c.monthlyCents ?? 0)],
    note: [''],
  });

  save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.renew(this.c.id, {
      newEndDate: v.newEndDate, monthsAdded: this.monthly ? Number(v.monthsAdded) || 0 : null,
      monthlyCents: this.monthly ? parseMoneyToCents(v.monthly) : null, note: v.note.trim() || null,
    }).subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
