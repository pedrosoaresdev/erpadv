import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastService } from '../../core/services/toast.service';
import { generatePassword, passwordMatchValidator, passwordValidator } from '../validators/password';

export interface PasswordDialogData {
  title: string;
  subtitle?: string;
}

/** Define uma nova senha (com geração de senha forte). Fecha devolvendo a senha escolhida. */
@Component({
  selector: 'app-password-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>key</mat-icon>{{ data.title }}</h2>
    <mat-dialog-content>
      @if (data.subtitle) { <p class="muted sub">{{ data.subtitle }}</p> }
      <form [formGroup]="form" id="pwdForm" (ngSubmit)="save()" class="form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Nova senha</mat-label>
          <input matInput [type]="show() ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
          <button mat-icon-button matSuffix type="button" (click)="show.set(!show())" [attr.aria-label]="show() ? 'Ocultar senha' : 'Mostrar senha'">
            <mat-icon>{{ show() ? 'visibility_off' : 'visibility' }}</mat-icon>
          </button>
          <mat-hint>Mínimo de 8 caracteres, com letras e números.</mat-hint>
          @if (form.controls.password.hasError('weakPassword') || form.controls.password.hasError('required')) {
            <mat-error>Use ao menos 8 caracteres com letras e números.</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Confirmar senha</mat-label>
          <input matInput [type]="show() ? 'text' : 'password'" formControlName="confirm" autocomplete="new-password" />
        </mat-form-field>
        @if (form.hasError('passwordMismatch') && form.controls.confirm.touched) {
          <div class="err">As senhas não conferem.</div>
        }
      </form>
      <div class="row gen">
        <button mat-stroked-button type="button" (click)="generate()"><mat-icon>casino</mat-icon>Gerar senha forte</button>
        <button mat-button type="button" (click)="copy()" [disabled]="!form.controls.password.value"><mat-icon>content_copy</mat-icon>Copiar</button>
      </div>
      <p class="muted small">As sessões abertas do usuário serão encerradas. Informe a nova senha por um canal seguro.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="pwdForm">Definir senha</button>
    </mat-dialog-actions>
  `,
  styles: `
    .sub { margin: 0 0 12px; }
    .gen { margin: 4px 0 8px; }
    .err { color: var(--app-danger); font-size: 13px; margin: -8px 0 8px; }
    .small { font-size: 12px; }
  `,
})
export class PasswordDialogComponent {
  readonly data = inject<PasswordDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PasswordDialogComponent>);
  private readonly clipboard = inject(Clipboard);
  private readonly toast = inject(ToastService);
  readonly show = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group(
    {
      password: ['', [Validators.required, passwordValidator]],
      confirm: ['', Validators.required],
    },
    { validators: passwordMatchValidator },
  );

  generate(): void {
    const value = generatePassword();
    this.form.setValue({ password: value, confirm: value });
    this.show.set(true);
  }

  copy(): void {
    this.clipboard.copy(this.form.controls.password.value);
    this.toast.success('Senha copiada.');
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.ref.close(this.form.controls.password.value);
  }
}
