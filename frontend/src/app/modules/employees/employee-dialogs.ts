import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
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
import { formatCpfCnpj, formatPhone, isValidCpf } from '../../shared/validators/br-validators';
import { generatePassword, passwordValidator } from '../../shared/validators/password';
import { CustomRole, CustomRoleRequest, EmployeesService, RolePreset } from './employees.service';

const PRIVILEGED: Role[] = ['SOCIO_ADMIN', 'MASTER'];
const optionalCpf = (c: { value: string }) => (!c.value || isValidCpf(c.value) ? null : { cpf: true });

/** Seleção de acesso: cargo personalizado ("custom:<id>") ou papel padrão ("role:<ROLE>"). */
function parseAccess(value: string): { customRoleId: string | null; role: Role | null } {
  return value.startsWith('custom:')
    ? { customRoleId: value.slice(7), role: null }
    : { customRoleId: null, role: value.slice(5) as Role };
}

const ACCESS_SELECT = `
  <mat-select formControlName="access" (selectionChange)="accessChanged()">
    @if (customRoles().length) {
      <mat-optgroup label="Cargos do escritório">
        @for (c of customRoles(); track c.id) {
          <mat-option [value]="'custom:' + c.id" [disabled]="!canAssignCustom(c)">
            <span class="dot" [style.background]="c.color || '#7a8ca8'"></span>{{ c.name }}
          </mat-option>
        }
      </mat-optgroup>
    }
    <mat-optgroup label="Papéis padrão">
      @for (r of roles(); track r.role) { <mat-option [value]="'role:' + r.role">{{ r.label }}</mat-option> }
    </mat-optgroup>
  </mat-select>
`;

const DOT_STYLE = `.dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }`;

// ======================================================================= cadastro
@Component({
  selector: 'app-invite-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatButtonToggleModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>person_add</mat-icon>Novo integrante</h2>
    <mat-dialog-content>
      @if (done()) {
        @if (link()) {
          <div class="ok">
            <strong>Integrante cadastrado.</strong> Envie o link abaixo para que a pessoa defina a senha de acesso.
            O link é pessoal e expira — não o compartilhe em canais públicos.
          </div>
          <div class="link-box mono">{{ link() }}</div>
        } @else {
          <div class="ok">
            <strong>Integrante cadastrado com senha.</strong> Informe o e-mail e a senha por um canal seguro;
            recomende a troca no primeiro acesso.
          </div>
        }
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
            <mat-label>Cargo / papel</mat-label>
            ${ACCESS_SELECT}
            <mat-hint>{{ accessHint() }}</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Função exibida (opcional)</mat-label>
            <input matInput formControlName="position" placeholder="Ex.: Coordenadora trabalhista" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>OAB</mat-label>
            <input matInput formControlName="oabNumber" placeholder="SP 123456" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>CPF (opcional)</mat-label>
            <input matInput formControlName="cpf" (input)="maskCpf($event)" inputmode="numeric" />
            @if (form.controls.cpf.invalid) { <mat-error>CPF inválido</mat-error> }
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Telefone</mat-label>
            <input matInput formControlName="phone" (input)="maskPhone($event)" />
          </mat-form-field>

          <div class="form-section-title">Primeiro acesso</div>
          <mat-button-toggle-group formControlName="mode" class="mode" aria-label="Forma de acesso">
            <mat-button-toggle value="password"><mat-icon>password</mat-icon> Definir senha agora</mat-button-toggle>
            <mat-button-toggle value="link"><mat-icon>link</mat-icon> Gerar link para a pessoa definir</mat-button-toggle>
          </mat-button-toggle-group>
          @if (form.controls.mode.value === 'password') {
            <mat-form-field appearance="outline" class="c8">
              <mat-label>Senha inicial</mat-label>
              <input matInput [type]="show() ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
              <button mat-icon-button matSuffix type="button" (click)="show.set(!show())" aria-label="Mostrar senha">
                <mat-icon>{{ show() ? 'visibility_off' : 'visibility' }}</mat-icon>
              </button>
              @if (form.controls.password.invalid) { <mat-error>8+ caracteres com letras e números</mat-error> }
            </mat-form-field>
            <div class="c4 pw-tools">
              <button mat-stroked-button type="button" (click)="generate()"><mat-icon>casino</mat-icon>Gerar</button>
              <button mat-button type="button" (click)="copyPassword()" [disabled]="!form.controls.password.value"><mat-icon>content_copy</mat-icon>Copiar</button>
            </div>
          }
        </form>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (done()) {
        @if (link()) { <button mat-stroked-button (click)="copy()"><mat-icon>content_copy</mat-icon>Copiar link</button> }
        <button mat-flat-button [mat-dialog-close]="true">Concluir</button>
      } @else {
        <button mat-button mat-dialog-close type="button">Cancelar</button>
        <button mat-flat-button type="submit" form="inviteForm" [disabled]="saving()">Cadastrar</button>
      }
    </mat-dialog-actions>
  `,
  styles: `
    .ok { background: var(--app-success-bg); color: var(--app-success); border-radius: 10px; padding: 12px 14px; line-height: 1.5; }
    .link-box { margin-top: 12px; padding: 12px; border-radius: 10px; background: var(--app-surface-2); border: 1px solid var(--app-border); word-break: break-all; font-size: 12px; }
    .mode { grid-column: span 12; margin-bottom: 16px; flex-wrap: wrap; }
    .pw-tools { display: flex; gap: 4px; align-items: flex-start; padding-top: 8px; }
    ${DOT_STYLE}
  `,
})
export class InviteDialogComponent {
  private readonly service = inject(EmployeesService);
  private readonly auth = inject(AuthService);
  private readonly clipboard = inject(Clipboard);
  private readonly toast = inject(ToastService);
  readonly saving = signal(false);
  readonly done = signal(false);
  readonly link = signal<string | null>(null);
  readonly show = signal(false);
  private readonly allRoles = toSignal(this.service.roles(), { initialValue: [] });
  readonly customRoles = toSignal(this.service.customRoles(), { initialValue: [] as CustomRole[] });
  readonly roles = computed(() =>
    this.allRoles().filter((r) => r.staff && (!PRIVILEGED.includes(r.role) || this.auth.me()?.role === 'SOCIO_ADMIN')),
  );
  readonly form = inject(FormBuilder).nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(120)]],
    email: ['', [Validators.required, Validators.email]],
    access: ['role:ADVOGADO', Validators.required],
    position: [''],
    oabNumber: [''],
    cpf: ['', optionalCpf],
    phone: [''],
    mode: ['password' as 'password' | 'link'],
    password: ['', [Validators.required, passwordValidator]],
  });
  private readonly access = signal(this.form.controls.access.value);
  readonly accessHint = computed(() => {
    const parsed = parseAccess(this.access());
    const custom = this.customRoles().find((c) => c.id === parsed.customRoleId);
    return custom ? `${custom.permissions.length} permissões do cargo` : 'Permissões padrão do papel';
  });

  constructor() {
    this.form.controls.mode.valueChanges.subscribe((mode) => {
      const pwd = this.form.controls.password;
      if (mode === 'password') pwd.enable(); else pwd.disable();
    });
  }

  canAssignCustom(c: CustomRole): boolean {
    return c.permissions.every((p) => this.auth.has(p));
  }

  accessChanged(): void {
    this.access.set(this.form.controls.access.value);
  }

  maskPhone(event: Event): void {
    this.form.controls.phone.setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  maskCpf(event: Event): void {
    this.form.controls.cpf.setValue(formatCpfCnpj((event.target as HTMLInputElement).value).slice(0, 14), { emitEvent: false });
  }

  generate(): void {
    this.form.controls.password.setValue(generatePassword());
    this.show.set(true);
  }

  copyPassword(): void {
    this.clipboard.copy(this.form.controls.password.value);
    this.toast.success('Senha copiada.');
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const v = this.form.getRawValue();
    const access = parseAccess(v.access);
    this.service
      .invite({
        email: v.email.trim(), displayName: v.displayName.trim(), role: access.role, customRoleId: access.customRoleId,
        oabNumber: v.oabNumber || null, cpf: v.cpf || null, phone: v.phone || null, position: v.position || null,
        password: v.mode === 'password' ? v.password : null,
      })
      .subscribe({
        next: (r) => {
          this.link.set(r.passwordSetupLink);
          this.done.set(true);
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
          <mat-label>Cargo / papel</mat-label>
          ${ACCESS_SELECT}
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3"><mat-label>Função exibida</mat-label><input matInput formControlName="position" /></mat-form-field>
        <mat-form-field appearance="outline" class="c3"><mat-label>OAB</mat-label><input matInput formControlName="oabNumber" /></mat-form-field>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>CPF</mat-label>
          <input matInput formControlName="cpf" [placeholder]="data.cpfMasked || ''" (input)="maskCpf($event)" />
          <mat-hint>{{ data.cpfMasked ? 'Atual: ' + data.cpfMasked + ' (vazio mantém)' : 'Opcional' }}</mat-hint>
          @if (form.controls.cpf.invalid) { <mat-error>CPF inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3"><mat-label>Telefone</mat-label><input matInput formControlName="phone" /></mat-form-field>
      </form>

      <div class="form-section-title">Permissões efetivas</div>
      <p class="muted hint">Marcações diferentes do padrão do {{ customRole() ? 'cargo' : 'papel' }} ficam destacadas e são registradas como concessão ou revogação individual.</p>
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
    ${DOT_STYLE}
  `,
})
export class EmployeeEditDialogComponent {
  readonly data = inject<Employee>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<EmployeeEditDialogComponent>);
  private readonly service = inject(EmployeesService);
  private readonly auth = inject(AuthService);
  readonly saving = signal(false);
  private readonly allRoles = toSignal(this.service.roles(), { initialValue: [] });
  readonly customRoles = toSignal(this.service.customRoles(), { initialValue: [] as CustomRole[] });
  private readonly catalog = toSignal(this.service.permissions(), { initialValue: [] as PermissionInfo[] });
  readonly roles = computed(() =>
    this.allRoles().filter((r) => r.staff && (!PRIVILEGED.includes(r.role) || this.auth.me()?.role === 'SOCIO_ADMIN')),
  );
  readonly granted = signal(new Set<Permission>(this.data.permissionsGranted));
  readonly revoked = signal(new Set<Permission>(this.data.permissionsRevoked));

  readonly form = inject(FormBuilder).nonNullable.group({
    displayName: [this.data.displayName, [Validators.required, Validators.maxLength(120)]],
    access: [this.data.customRoleId ? 'custom:' + this.data.customRoleId : 'role:' + this.data.role, Validators.required],
    position: [this.data.position ?? ''],
    oabNumber: [this.data.oabNumber ?? ''],
    cpf: ['', optionalCpf],
    phone: [this.data.phone ?? ''],
  });
  private readonly access = signal(this.form.controls.access.value);
  readonly customRole = computed(() => {
    const id = parseAccess(this.access()).customRoleId;
    return id ? this.customRoles().find((c) => c.id === id) ?? null : null;
  });
  readonly baseRole = computed<Role>(() => this.customRole()?.baseRole ?? parseAccess(this.access()).role ?? this.data.role);

  readonly defaults = computed(() => {
    const custom = this.customRole();
    if (custom) return new Set(custom.permissions);
    return new Set(this.allRoles().find((r) => r.role === this.baseRole())?.permissions ?? []);
  });
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

  moduleLabel(module: string): string {
    return MODULE_LABELS[module] ?? module;
  }

  canGrant(p: Permission): boolean {
    return this.auth.has(p);
  }

  canAssignCustom(c: CustomRole): boolean {
    return c.permissions.every((p) => this.auth.has(p));
  }

  isOverride(p: Permission): boolean {
    return this.granted().has(p) || this.revoked().has(p);
  }

  accessChanged(): void {
    this.access.set(this.form.controls.access.value);
    // Ao trocar de cargo, os ajustes individuais anteriores deixam de fazer sentido.
    this.granted.set(new Set());
    this.revoked.set(new Set());
  }

  maskCpf(event: Event): void {
    this.form.controls.cpf.setValue(formatCpfCnpj((event.target as HTMLInputElement).value).slice(0, 14), { emitEvent: false });
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
    const access = parseAccess(v.access);
    this.saving.set(true);
    this.service
      .update(this.data.uid, {
        displayName: v.displayName.trim(),
        role: this.baseRole(),
        customRoleId: access.customRoleId,
        cpf: v.cpf || null,
        position: v.position || null,
        oabNumber: v.oabNumber || null,
        phone: v.phone || null,
        permissionsGranted: [...this.granted()],
        permissionsRevoked: [...this.revoked()],
      })
      .subscribe({ next: (e) => this.ref.close(e), error: () => this.saving.set(false) });
  }
}

// ======================================================================= cargo personalizado
const ROLE_COLORS = ['#1f3b63', '#2f5d9e', '#6b8f71', '#8a6d3b', '#b8860b', '#7a4e9e', '#9b2c2c', '#2c7a7b', '#4a5568'];

@Component({
  selector: 'app-role-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>workspace_premium</mat-icon>{{ data ? 'Editar cargo' : 'Novo cargo' }}</h2>
    <mat-dialog-content>
      @if (!data && presets().length) {
        <div class="presets">
          <span class="muted small">Começar de um modelo:</span>
          @for (p of presets(); track p.name) {
            <button type="button" class="preset" (click)="applyPreset(p)" [style.borderColor]="p.color">
              <span class="dot" [style.background]="p.color"></span>{{ p.name }}
            </button>
          }
        </div>
      }
      <form [formGroup]="form" class="form-grid" id="roleForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Nome do cargo</mat-label>
          <input matInput formControlName="name" placeholder="Ex.: Advogado júnior" />
          @if (form.controls.name.invalid) { <mat-error>Obrigatório (até 60 caracteres)</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Comporta-se como</mat-label>
          <mat-select formControlName="baseRole">
            @for (r of baseRoles(); track r.role) { <mat-option [value]="r.role">{{ r.label }}</mat-option> }
          </mat-select>
          <mat-hint>Ex.: se aparece como advogado responsável</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Descrição</mat-label>
          <input matInput formControlName="description" />
        </mat-form-field>
        <div class="c4 colors">
          @for (c of colors; track c) {
            <button type="button" class="swatch" [style.background]="c" [class.sel]="form.controls.color.value === c"
              (click)="form.controls.color.setValue(c)" [attr.aria-label]="'Cor ' + c"></button>
          }
        </div>
      </form>
      <div class="form-section-title row">
        Permissões do cargo <span class="spacer"></span><span class="muted small">{{ selected().size }} selecionadas</span>
      </div>
      <div class="perm-grid">
        @for (group of groups(); track group.module) {
          <section>
            <h4>
              {{ moduleLabel(group.module) }}
              <button type="button" class="all" (click)="toggleGroup(group.items)">{{ allSelected(group.items) ? 'desmarcar' : 'marcar todas' }}</button>
            </h4>
            @for (p of group.items; track p.code) {
              <mat-checkbox [checked]="selected().has(p.code)" (change)="toggle(p.code, $event.checked)" [disabled]="!auth.has(p.code)">
                {{ p.description }}
              </mat-checkbox>
            }
          </section>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="roleForm" [disabled]="saving() || selected().size === 0">Salvar cargo</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; }
    .presets { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 14px; }
    .preset { border: 1px solid var(--app-border); background: var(--app-surface-2); color: var(--app-text); border-radius: 999px; padding: 6px 12px; cursor: pointer; font: inherit; font-size: 13px; }
    .preset:hover { background: var(--app-gold-soft); }
    .colors { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding-bottom: 20px; }
    .swatch { width: 24px; height: 24px; border-radius: 50%; border: 2px solid transparent; cursor: pointer; }
    .swatch.sel { border-color: var(--app-text); box-shadow: 0 0 0 2px var(--app-surface) inset; }
    .perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px 24px; }
    h4 { font-size: 12px; text-transform: uppercase; letter-spacing: .6px; color: var(--app-text-muted); margin: 12px 0 4px; display: flex; gap: 8px; }
    .all { border: 0; background: none; color: var(--app-info); cursor: pointer; font-size: 11px; text-transform: none; }
    mat-checkbox { display: block; }
    .small { font-size: 12px; }
    ${DOT_STYLE}
  `,
})
export class RoleDialogComponent {
  readonly data = inject<CustomRole | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<RoleDialogComponent>);
  private readonly service = inject(EmployeesService);
  readonly auth = inject(AuthService);
  readonly saving = signal(false);
  readonly colors = ROLE_COLORS;
  readonly presets = toSignal(this.service.presets(), { initialValue: [] as RolePreset[] });
  private readonly allRoles = toSignal(this.service.roles(), { initialValue: [] });
  private readonly catalog = toSignal(this.service.permissions(), { initialValue: [] as PermissionInfo[] });
  readonly baseRoles = computed(() =>
    this.allRoles().filter((r) => r.staff && (r.role !== 'SOCIO_ADMIN' || this.auth.me()?.role === 'SOCIO_ADMIN')),
  );
  readonly selected = signal(new Set<Permission>(this.data?.permissions ?? []));
  readonly groups = computed(() => {
    const map = new Map<string, PermissionInfo[]>();
    this.catalog().forEach((p) => map.set(p.module, [...(map.get(p.module) ?? []), p]));
    return [...map.entries()].map(([module, items]) => ({ module, items }));
  });
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.data?.name ?? '', [Validators.required, Validators.maxLength(60)]],
    description: [this.data?.description ?? '', Validators.maxLength(300)],
    color: [this.data?.color ?? ROLE_COLORS[1]],
    baseRole: [(this.data?.baseRole ?? 'ADVOGADO_ASSOCIADO') as Role, Validators.required],
  });

  moduleLabel(module: string): string {
    return MODULE_LABELS[module] ?? module;
  }

  applyPreset(p: RolePreset): void {
    this.form.patchValue({ name: p.name, description: p.description, color: p.color, baseRole: p.baseRole });
    this.selected.set(new Set(p.permissions.filter((x) => this.auth.has(x))));
  }

  toggle(p: Permission, checked: boolean): void {
    const next = new Set(this.selected());
    if (checked) next.add(p); else next.delete(p);
    this.selected.set(next);
  }

  allSelected(items: PermissionInfo[]): boolean {
    return items.filter((i) => this.auth.has(i.code)).every((i) => this.selected().has(i.code));
  }

  toggleGroup(items: PermissionInfo[]): void {
    const select = !this.allSelected(items);
    const next = new Set(this.selected());
    items.filter((i) => this.auth.has(i.code)).forEach((i) => (select ? next.add(i.code) : next.delete(i.code)));
    this.selected.set(next);
  }

  save(): void {
    if (this.form.invalid || this.selected().size === 0) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body: CustomRoleRequest = {
      name: v.name.trim(), description: v.description || null, color: v.color, baseRole: v.baseRole,
      permissions: [...this.selected()],
    };
    this.saving.set(true);
    const request$: Observable<CustomRole> = this.data ? this.service.updateRole(this.data.id, body) : this.service.createRole(body);
    request$.subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
