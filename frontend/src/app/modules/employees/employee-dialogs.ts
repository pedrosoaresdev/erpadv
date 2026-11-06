import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AuthService } from '../../core/auth/auth.service';
import { Employee, Permission, PermissionInfo, Role } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { MODULE_LABELS } from '../../shared/labels';
import { formatPhone } from '../../shared/validators/br-validators';
import { EmployeesService } from './employees.service';

const PRIVILEGED: Role[] = ['SOCIO_ADMIN', 'MASTER'];

// ======================================================================= convite
@Component({
  selector: 'app-invite-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>person_add</mat-icon>Convidar integrante</h2>
    <mat-dialog-content>
      @if (link()) {
        <div class="ok">
          <strong>Convite criado.</strong> Envie o link abaixo para que a pessoa defina a senha de acesso.
          O link é pessoal e expira — não o compartilhe em canais públicos.
        </div>
        <div class="link-box mono">{{ link() }}</div>
      } @else {
        <form [formGroup]="form" class="form-grid" id="inviteForm" (ngSubmit)="save()">
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Nome completo</mat-label>
            <input matInput formControlName="displayName" />
            @if (form.controls.displayName.invalid) { <mat-error>Obrigatório</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>E-mail</mat-label>
            <input matInput type="email" formControlName="email" />
            @if (form.controls.email.invalid) { <mat-error>E-mail inválido</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Papel</mat-label>
            <mat-select formControlName="role">
              @for (r of roles(); track r.role) { <mat-option [value]="r.role">{{ r.label }}</mat-option> }
            </mat-select>
            <mat-hint>Define as permissões padrão; ajustes finos podem ser feitos depois.</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Cargo</mat-label>
            <input matInput formControlName="position" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>OAB</mat-label>
            <input matInput formControlName="oabNumber" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Telefone</mat-label>
            <input matInput formControlName="phone" (input)="maskPhone($event)" />
          </mat-form-field>
        </form>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (link()) {
        <button mat-stroked-button (click)="copy()"><mat-icon>content_copy</mat-icon>Copiar link</button>
        <button mat-flat-button [mat-dialog-close]="true">Concluir</button>
      } @else {
        <button mat-button mat-dialog-close type="button">Cancelar</button>
        <button mat-flat-button type="submit" form="inviteForm" [disabled]="saving()">Enviar convite</button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    .ok { background: var(--app-success-bg); color: var(--app-success); border-radius: 10px; padding: 12px 14px; line-height: 1.5; }
    .link-box { margin-top: 12px; padding: 12px; border-radius: 10px; background: var(--app-surface-2); border: 1px solid var(--app-border); word-break: break-all; font-size: 12px; }
  `,
})
export class InviteDialogComponent {
  private readonly service = inject(EmployeesService);
  private readonly auth = inject(AuthService);
  private readonly clipboard = inject(Clipboard);
  private readonly toast = inject(ToastService);
  readonly saving = signal(false);
  readonly link = signal<string | null>(null);
  private readonly allRoles = toSignal(this.service.roles(), { initialValue: [] });
  readonly roles = computed(() =>
    this.allRoles().filter((r) => r.staff && (!PRIVILEGED.includes(r.role) || this.auth.me()?.role === 'SOCIO_ADMIN')),
  );
  readonly form = inject(FormBuilder).nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    role: ['ADVOGADO' as Role, Validators.required],
    position: [''],
    oabNumber: [''],
    phone: [''],
  });

  maskPhone(event: Event): void {
    this.form.controls.phone.setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.form.getRawValue();
    this.service
      .invite({ ...v, email: v.email.trim(), displayName: v.displayName.trim(), oabNumber: v.oabNumber || null, phone: v.phone || null, position: v.position || null })
      .subscribe({
        next: (r) => {
          this.link.set(r.passwordSetupLink);
          this.saving.set(false);
        },
        error: () => this.saving.set(false),
      });
  }

  copy(): void {
    this.clipboard.copy(this.link() ?? '');
    this.toast.success('Link copiado.');
  }
}

// ======================================================================= edição de acesso
@Component({
  selector: 'app-employee-edit-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>manage_accounts</mat-icon>Acesso de {{ data.displayName }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="empForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Nome</mat-label>
          <input matInput formControlName="displayName" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Papel</mat-label>
          <mat-select formControlName="role" (selectionChange)="roleChanged()">
            @for (r of roles(); track r.role) { <mat-option [value]="r.role">{{ r.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4"><mat-label>Cargo</mat-label><input matInput formControlName="position" /></mat-form-field>
        <mat-form-field appearance="outline" class="c4"><mat-label>OAB</mat-label><input matInput formControlName="oabNumber" /></mat-form-field>
        <mat-form-field appearance="outline" class="c4"><mat-label>Telefone</mat-label><input matInput formControlName="phone" /></mat-form-field>
      </form>

      <div class="form-section-title">Permissões efetivas</div>
      <p class="muted hint">Marcações diferentes do padrão do papel ficam destacadas e são registradas como concessão ou revogação individual.</p>
      <div class="perm-grid">
        @for (group of groups(); track group.module) {
          <section>
            <h4>{{ moduleLabel(group.module) }}</h4>
            @for (p of group.items; track p.code) {
              <mat-checkbox [checked]="effective().has(p.code)" (change)="toggle(p.code, $event.checked)"
                [class.changed]="isOverride(p.code)" [disabled]="!canGrant(p.code) && !effective().has(p.code)">
                {{ p.description }}
              </mat-checkbox>
            }
          </section>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="empForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 74vh; }
    .hint { margin: -4px 0 12px; font-size: 13px; }
    .perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px 24px; }
    h4 { font-size: 12px; text-transform: uppercase; letter-spacing: .6px; color: var(--app-text-muted); margin: 12px 0 4px; }
    mat-checkbox { display: block; }
    .changed { background: var(--app-gold-soft); border-radius: 8px; }
  `,
})
export class EmployeeEditDialogComponent {
  readonly data = inject<Employee>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<EmployeeEditDialogComponent>);
  private readonly service = inject(EmployeesService);
  private readonly auth = inject(AuthService);
  readonly saving = signal(false);
  private readonly allRoles = toSignal(this.service.roles(), { initialValue: [] });
  private readonly catalog = toSignal(this.service.permissions(), { initialValue: [] as PermissionInfo[] });
  readonly roles = computed(() =>
    this.allRoles().filter((r) => r.staff && (!PRIVILEGED.includes(r.role) || this.auth.me()?.role === 'SOCIO_ADMIN')),
  );
  readonly granted = signal(new Set<Permission>(this.data.permissionsGranted));
  readonly revoked = signal(new Set<Permission>(this.data.permissionsRevoked));
  readonly role = signal<Role>(this.data.role);

  readonly defaults = computed(() => new Set(this.allRoles().find((r) => r.role === this.role())?.permissions ?? []));
  readonly effective = computed(() => {
    const result = new Set(this.defaults());
    this.granted().forEach((p) => result.add(p));
    this.revoked().forEach((p) => result.delete(p));
    return result;
  });
  readonly groups = computed(() => {
    const map = new Map<string, PermissionInfo[]>();
    this.catalog().forEach((p) => map.set(p.module, [...(map.get(p.module) ?? []), p]));
    return [...map.entries()].map(([module, items]) => ({ module, items }));
  });

  readonly form = inject(FormBuilder).nonNullable.group({
    displayName: [this.data.displayName, [Validators.required, Validators.maxLength(120)]],
    role: [this.data.role, Validators.required],
    position: [this.data.position ?? ''],
    oabNumber: [this.data.oabNumber ?? ''],
    phone: [this.data.phone ?? ''],
  });

  moduleLabel(module: string): string {
    return MODULE_LABELS[module] ?? module;
  }

  canGrant(p: Permission): boolean {
    return this.auth.has(p);
  }

  isOverride(p: Permission): boolean {
    return this.granted().has(p) || this.revoked().has(p);
  }

  roleChanged(): void {
    this.role.set(this.form.controls.role.value);
  }

  toggle(p: Permission, checked: boolean): void {
    const isDefault = this.defaults().has(p);
    const granted = new Set(this.granted());
    const revoked = new Set(this.revoked());
    granted.delete(p);
    revoked.delete(p);
    if (checked && !isDefault) granted.add(p);
    if (!checked && isDefault) revoked.add(p);
    this.granted.set(granted);
    this.revoked.set(revoked);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service
      .update(this.data.uid, {
        displayName: v.displayName.trim(),
        role: v.role,
        position: v.position || null,
        oabNumber: v.oabNumber || null,
        phone: v.phone || null,
        permissionsGranted: [...this.granted()],
        permissionsRevoked: [...this.revoked()],
      })
      .subscribe({ next: (e) => this.ref.close(e), error: () => this.saving.set(false) });
  }
}
