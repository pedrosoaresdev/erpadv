import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { catchError, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Address, EmployeeOption } from '../../core/models/models';
import { UF } from '../../shared/labels';
import { OfficeService, Unit, UnitRequest } from './office.service';

/** Cadastro/edição de unidade (filial) e definição dos integrantes. */
@Component({
  selector: 'app-unit-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatSelectModule, MatSlideToggleModule],
  template: `
    <h2 mat-dialog-title>{{ unit ? 'Editar unidade' : 'Nova unidade' }}</h2>
    <mat-dialog-content>
      <div class="grid">
        <mat-form-field class="c2"><mat-label>Nome da unidade</mat-label><input matInput [(ngModel)]="form.name" maxlength="120" required placeholder="Ex.: Filial Caruaru" /></mat-form-field>
        <mat-form-field><mat-label>CNPJ (opcional)</mat-label><input matInput [(ngModel)]="form.cnpj" maxlength="18" /></mat-form-field>
        <mat-form-field><mat-label>Telefone</mat-label><input matInput [(ngModel)]="form.phone" maxlength="30" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>E-mail</mat-label><input matInput type="email" [(ngModel)]="form.email" maxlength="160" /></mat-form-field>
        <mat-form-field><mat-label>CEP</mat-label><input matInput [(ngModel)]="address.zipCode" maxlength="9" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Endereço</mat-label><input matInput [(ngModel)]="address.street" maxlength="160" /></mat-form-field>
        <mat-form-field><mat-label>Número</mat-label><input matInput [(ngModel)]="address.number" maxlength="20" /></mat-form-field>
        <mat-form-field><mat-label>Bairro</mat-label><input matInput [(ngModel)]="address.neighborhood" maxlength="80" /></mat-form-field>
        <mat-form-field><mat-label>Cidade</mat-label><input matInput [(ngModel)]="address.city" maxlength="80" /></mat-form-field>
        <mat-form-field><mat-label>UF</mat-label>
          <mat-select [(ngModel)]="address.state"><mat-option [value]="null">—</mat-option>@for (uf of ufs; track uf) { <mat-option [value]="uf">{{ uf }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field class="c2"><mat-label>Responsável pela unidade</mat-label>
          <mat-select [(ngModel)]="form.managerUid">
            <mat-option [value]="null">—</mat-option>
            @for (e of staff(); track e.uid) { <mat-option [value]="e.uid">{{ e.displayName }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-slide-toggle [(ngModel)]="form.active">Unidade ativa</mat-slide-toggle>
      </div>

      <h3>Integrantes</h3>
      <p class="muted small">Quem trabalha nesta unidade. O filtro por unidade do dashboard considera os processos e prazos desses integrantes.</p>
      <div class="members">
        @for (e of staff(); track e.uid) {
          <mat-checkbox [checked]="members.has(e.uid)" (change)="toggle(e.uid, $event.checked)">{{ e.displayName }}</mat-checkbox>
        }
      </div>
      @if (error()) { <p class="error">{{ error() }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving() || !form.name.trim()"><mat-icon>save</mat-icon>Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 12px; align-items: center; }
    .c2 { grid-column: span 2; }
    h3 { margin: 16px 0 4px; font-size: 15px; }
    .small { font-size: 12px; }
    .members { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 4px 12px; max-height: 220px; overflow: auto; }
    .error { color: var(--app-danger); }
    @media (max-width: 700px) { .grid { grid-template-columns: minmax(0, 1fr); } .c2 { grid-column: auto; } }
  `,
})
export class UnitDialogComponent {
  private readonly service = inject(OfficeService);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<UnitDialogComponent>);
  readonly unit = inject<Unit | null>(MAT_DIALOG_DATA);
  readonly ufs = UF;
  readonly saving = signal(false);
  readonly error = signal('');
  readonly staff = toSignal(this.api.get<EmployeeOption[]>('/employees/options', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly form: UnitRequest = {
    name: this.unit?.name ?? '', cnpj: this.unit?.cnpj ?? null, phone: this.unit?.phone ?? null, email: this.unit?.email ?? null,
    address: null, managerUid: this.unit?.managerUid ?? null, active: this.unit?.active ?? true,
  };
  readonly address: Address = { ...(this.unit?.address ?? {}) };
  readonly members = new Set<string>(this.unit?.memberUids ?? []);

  toggle(uid: string, on: boolean): void {
    if (on) this.members.add(uid); else this.members.delete(uid);
  }

  save(): void {
    const hasAddress = Object.values(this.address).some((v) => v && String(v).trim());
    const body: UnitRequest = { ...this.form, address: hasAddress ? this.address : null };
    this.saving.set(true);
    this.error.set('');
    this.service.saveUnit(this.unit?.id ?? null, body)
      .pipe(switchMap((u) => this.service.setUnitMembers(u.id, [...this.members])))
      .subscribe({
        next: (u) => this.ref.close(u),
        error: (e) => { this.saving.set(false); this.error.set(e?.error?.message ?? 'Não foi possível salvar a unidade.'); },
      });
  }
}
