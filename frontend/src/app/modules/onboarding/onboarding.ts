import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { UF } from '../../shared/labels';
import { cpfCnpjValidator, formatCpfCnpj, formatPhone } from '../../shared/validators/br-validators';

/** Primeiro acesso: cria o escritório (tenant) e torna o usuário sócio administrador. */
@Component({
  selector: 'app-onboarding',
  imports: [ReactiveFormsModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatSelectModule, MatProgressBarModule],
  template: `
    <div class="card wrap">
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      <span class="eyebrow">Primeiro acesso</span>
      <h2>Configure seu escritório</h2>
      <p class="sub">Estes dados identificam o escritório no sistema. Você poderá convidar sua equipe logo depois.</p>
      <form [formGroup]="form" (ngSubmit)="submit()" class="form-grid">
        <mat-form-field appearance="outline">
          <mat-label>Nome do escritório</mat-label>
          <input matInput formControlName="name" placeholder="Ex.: Lucena & Associados Advocacia" />
          @if (form.controls.name.invalid) { <mat-error>Informe o nome do escritório</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>CNPJ (opcional)</mat-label>
          <input matInput formControlName="cnpj" (input)="mask('cnpj', $event)" />
          @if (form.controls.cnpj.invalid) { <mat-error>CNPJ inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Telefone</mat-label>
          <input matInput formControlName="phone" (input)="mask('phone', $event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Cidade</mat-label>
          <input matInput formControlName="city" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>UF</mat-label>
          <mat-select formControlName="state">
            <mat-option value="">—</mat-option>
            @for (uf of ufs; track uf) { <mat-option [value]="uf">{{ uf }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <div class="form-section-title">Responsável</div>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Seu nome</mat-label>
          <input matInput formControlName="responsibleName" />
          @if (form.controls.responsibleName.invalid) { <mat-error>Informe seu nome</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>OAB</mat-label>
          <input matInput formControlName="oabNumber" placeholder="SP 123456" />
        </mat-form-field>
        <div class="actions">
          <button mat-button type="button" (click)="auth.logout()">Sair</button>
          <button mat-flat-button type="submit" [disabled]="loading()"><mat-icon>rocket_launch</mat-icon>Criar escritório</button>
        </div>
      </form>
    </div>
  `,
  styles: `
    .wrap { padding: 32px; position: relative; overflow: hidden; }
    mat-progress-bar { position: absolute; top: 0; left: 0; right: 0; }
    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase; color: var(--app-gold); }
    h2 { font-size: 24px; margin-top: 4px; }
    .sub { color: var(--app-text-muted); margin: 6px 0 24px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `,
})
export class OnboardingComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly ufs = UF;
  readonly loading = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', [Validators.required, Validators.maxLength(150)]],
    cnpj: ['', cpfCnpjValidator(() => 'PJ')],
    phone: [''],
    city: [''],
    state: [''],
    responsibleName: [this.auth.user()?.displayName ?? '', [Validators.required, Validators.maxLength(120)]],
    oabNumber: [''],
  });

  mask(field: 'cnpj' | 'phone', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = field === 'cnpj' ? formatCpfCnpj(input.value) : formatPhone(input.value);
    this.form.controls[field].setValue(value, { emitEvent: false });
  }

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    try {
      const value = this.form.getRawValue();
      await firstValueFrom(this.api.post('/onboarding/office', { ...value, email: this.auth.user()?.email }));
      const me = await this.auth.refresh();
      await firstValueFrom(this.api.post('/auth/session', {}, true)).catch(() => undefined);
      this.toast.success('Escritório criado. Bem-vindo(a)!');
      await this.router.navigateByUrl(this.auth.homePath(me));
    } catch {
      /* erro exibido pelo interceptor */
    } finally {
      this.loading.set(false);
    }
  }
}
