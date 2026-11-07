import { Component, inject, signal } from '@angular/core';
import { Observable } from 'rxjs';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ToastService } from '../../core/services/toast.service';
import { PasswordDialogComponent } from '../../shared/components/password-dialog';
import { UF, USER_STATUS_LABELS } from '../../shared/labels';
import { BrDatePipe, RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { cpfCnpjValidator, formatCpfCnpj, formatPhone, isValidCpf } from '../../shared/validators/br-validators';
import { generatePassword, passwordMatchValidator, passwordValidator } from '../../shared/validators/password';
import { AdminUserRequest, OfficeDetail, OfficeSummary, OfficeUser, PLAN_LABELS, Plan, PlatformService } from './platform.service';

const cpfValidator = (c: { value: string }) => (!c.value || isValidCpf(c.value) ? null : { cpf: true });

function adminGroup(fb: FormBuilder) {
  return fb.nonNullable.group(
    {
      fullName: ['', [Validators.required, Validators.maxLength(120)]],
      cpf: ['', [Validators.required, cpfValidator]],
      oabNumber: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9 ./-]{3,20}$/)]],
      email: ['', [Validators.required, Validators.email]],
      phone: [''],
      password: ['', [Validators.required, passwordValidator]],
      confirm: ['', Validators.required],
    },
    { validators: passwordMatchValidator },
  );
}

/** Campos do administrador (nome, CPF, OAB, e-mail, senha) — usado na criação e em "novo administrador". */
const ADMIN_FIELDS = `
  <mat-form-field appearance="outline" class="c6">
    <mat-label>Nome completo do advogado</mat-label>
    <input matInput formControlName="fullName" autocomplete="off" />
    @if (admin.controls.fullName.invalid) { <mat-error>Obrigatório</mat-error> }
  </mat-form-field>
  <mat-form-field appearance="outline" class="c3">
    <mat-label>CPF</mat-label>
    <input matInput formControlName="cpf" (input)="maskCpf($event)" inputmode="numeric" />
    @if (admin.controls.cpf.invalid) { <mat-error>CPF inválido</mat-error> }
  </mat-form-field>
  <mat-form-field appearance="outline" class="c3">
    <mat-label>Nº OAB</mat-label>
    <input matInput formControlName="oabNumber" placeholder="SP 123456" />
    @if (admin.controls.oabNumber.invalid) { <mat-error>Informe UF e número</mat-error> }
  </mat-form-field>
  <mat-form-field appearance="outline" class="c6">
    <mat-label>E-mail de acesso</mat-label>
    <input matInput type="email" formControlName="email" autocomplete="off" />
    @if (admin.controls.email.invalid) { <mat-error>E-mail inválido</mat-error> }
  </mat-form-field>
  <mat-form-field appearance="outline" class="c6">
    <mat-label>Telefone</mat-label>
    <input matInput formControlName="phone" (input)="maskAdminPhone($event)" />
  </mat-form-field>
  <mat-form-field appearance="outline" class="c6">
    <mat-label>Senha inicial</mat-label>
    <input matInput [type]="showPassword() ? 'text' : 'password'" formControlName="password" autocomplete="new-password" />
    <button mat-icon-button matSuffix type="button" (click)="showPassword.set(!showPassword())" aria-label="Mostrar senha">
      <mat-icon>{{ showPassword() ? 'visibility_off' : 'visibility' }}</mat-icon>
    </button>
    @if (admin.controls.password.invalid) { <mat-error>8+ caracteres com letras e números</mat-error> }
  </mat-form-field>
  <mat-form-field appearance="outline" class="c6">
    <mat-label>Confirmar senha</mat-label>
    <input matInput [type]="showPassword() ? 'text' : 'password'" formControlName="confirm" autocomplete="new-password" />
    @if (admin.hasError('passwordMismatch')) { <mat-hint class="warn">As senhas não conferem</mat-hint> }
  </mat-form-field>
  <div class="row pw-tools">
    <button mat-stroked-button type="button" (click)="generate()"><mat-icon>casino</mat-icon>Gerar senha forte</button>
    <button mat-button type="button" (click)="copyPassword()" [disabled]="!admin.controls.password.value"><mat-icon>content_copy</mat-icon>Copiar senha</button>
  </div>
`;

abstract class AdminFormBase {
  protected readonly clipboard = inject(Clipboard);
  protected readonly toast = inject(ToastService);
  readonly showPassword = signal(false);
  abstract readonly admin: ReturnType<typeof adminGroup>;

  maskCpf(event: Event): void {
    this.admin.controls.cpf.setValue(formatCpfCnpj((event.target as HTMLInputElement).value).slice(0, 14), { emitEvent: false });
  }

  maskAdminPhone(event: Event): void {
    this.admin.controls.phone.setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  generate(): void {
    const value = generatePassword();
    this.admin.patchValue({ password: value, confirm: value });
    this.showPassword.set(true);
  }

  copyPassword(): void {
    this.clipboard.copy(this.admin.controls.password.value);
    this.toast.success('Senha copiada. Envie ao advogado por um canal seguro.');
  }

  protected adminValue(): AdminUserRequest {
    const a = this.admin.getRawValue();
    return {
      fullName: a.fullName.trim(),
      cpf: a.cpf,
      oabNumber: a.oabNumber.trim().toUpperCase(),
      email: a.email.trim().toLowerCase(),
      password: a.password,
      phone: a.phone || null,
    };
  }
}

// ======================================================================= criar / editar escritório
export interface OfficeFormData {
  office?: OfficeSummary;
}

@Component({
  selector: 'app-office-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>domain_add</mat-icon>{{ editing ? 'Editar escritório' : 'Novo escritório' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="officeForm" (ngSubmit)="save()">
        <div class="form-section-title">Escritório</div>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Razão social / nome do escritório</mat-label>
          <input matInput formControlName="name" />
          @if (form.controls.name.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>CNPJ (opcional)</mat-label>
          <input matInput formControlName="cnpj" (input)="maskCnpj($event)" />
          @if (form.controls.cnpj.invalid) { <mat-error>CNPJ inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Plano</mat-label>
          <mat-select formControlName="plan">
            @for (p of plans; track p) { <mat-option [value]="p">{{ planLabels[p] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Limite de usuários (vazio = ilimitado)</mat-label>
          <input matInput type="number" min="1" formControlName="maxUsers" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>E-mail do escritório</mat-label>
          <input matInput type="email" formControlName="email" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Telefone</mat-label>
          <input matInput formControlName="phone" (input)="maskPhone($event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Cidade</mat-label>
          <input matInput formControlName="city" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c2">
          <mat-label>UF</mat-label>
          <mat-select formControlName="state">
            <mat-option value="">—</mat-option>
            @for (uf of ufs; track uf) { <mat-option [value]="uf">{{ uf }}</mat-option> }
          </mat-select>
        </mat-form-field>
      </form>
      @if (!editing) {
        <form [formGroup]="admin" class="form-grid">
          <div class="form-section-title">Advogado administrador (dono do escritório)</div>
          ${ADMIN_FIELDS}
        </form>
        <p class="muted small">O administrador entra com o e-mail e a senha acima e então cria cargos, usuários e permissões da equipe.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="officeForm" [disabled]="saving()">{{ editing ? 'Salvar' : 'Criar escritório' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; }
    .pw-tools { grid-column: span 12; margin: -4px 0 12px; }
    .small { font-size: 12px; }
    .warn { color: var(--app-danger); }
  `,
})
export class OfficeFormDialogComponent extends AdminFormBase {
  private readonly data = inject<OfficeFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<OfficeFormDialogComponent>);
  private readonly service = inject(PlatformService);
  private readonly fb = inject(FormBuilder);
  readonly editing = !!this.data?.office;
  readonly saving = signal(false);
  readonly plans: Plan[] = ['ESSENCIAL', 'PROFISSIONAL', 'ENTERPRISE'];
  readonly planLabels = PLAN_LABELS;
  readonly ufs = UF;
  readonly admin = adminGroup(this.fb);
  readonly form = this.fb.nonNullable.group({
    name: [this.data?.office?.name ?? '', [Validators.required, Validators.maxLength(150)]],
    cnpj: [this.data?.office?.cnpj ?? '', cpfCnpjValidator(() => 'PJ')],
    plan: [(this.data?.office?.plan ?? 'PROFISSIONAL') as Plan, Validators.required],
    maxUsers: [this.data?.office?.maxUsers ?? (null as number | null), [Validators.min(1), Validators.max(10000)]],
    email: [this.data?.office?.email ?? '', Validators.email],
    phone: [this.data?.office?.phone ?? ''],
    city: [this.data?.office?.city ?? ''],
    state: [this.data?.office?.state ?? ''],
  });

  maskCnpj(event: Event): void {
    this.form.controls.cnpj.setValue(formatCpfCnpj((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  maskPhone(event: Event): void {
    this.form.controls.phone.setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  save(): void {
    const invalid = this.form.invalid || (!this.editing && this.admin.invalid);
    if (invalid) {
      this.form.markAllAsTouched();
      this.admin.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const office = {
      name: v.name.trim(), cnpj: v.cnpj || null, plan: v.plan, maxUsers: v.maxUsers || null,
      email: v.email || null, phone: v.phone || null, city: v.city || null, state: v.state || null,
    };
    this.saving.set(true);
    const request$: Observable<unknown> = this.editing
      ? this.service.update(this.data.office!.id, office)
      : this.service.create({ ...office, admin: this.adminValue() });
    request$.subscribe({ next: (r: unknown) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= novo administrador
@Component({
  selector: 'app-admin-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>person_add</mat-icon>Novo administrador · {{ data.name }}</h2>
    <mat-dialog-content>
      <form [formGroup]="admin" class="form-grid" id="adminForm" (ngSubmit)="save()">
        ${ADMIN_FIELDS}
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="adminForm" [disabled]="saving()">Cadastrar</button>
    </mat-dialog-actions>
  `,
  styles: `.pw-tools { grid-column: span 12; margin: -4px 0 12px; } .warn { color: var(--app-danger); }`,
})
export class AdminDialogComponent extends AdminFormBase {
  readonly data = inject<OfficeSummary>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<AdminDialogComponent>);
  private readonly service = inject(PlatformService);
  readonly saving = signal(false);
  readonly admin = adminGroup(inject(FormBuilder));

  save(): void {
    if (this.admin.invalid) {
      this.admin.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.service.addAdmin(this.data.id, this.adminValue()).subscribe({
      next: (u) => this.ref.close(u),
      error: () => this.saving.set(false),
    });
  }
}

// ======================================================================= detalhe do escritório
@Component({
  selector: 'app-office-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule, BrDatePipe, RelativeTimePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>domain</mat-icon>{{ detail()?.office?.name ?? 'Escritório' }}</h2>
    <mat-dialog-content>
      @if (!detail()) { <mat-progress-bar mode="indeterminate" /> }
      @if (detail(); as d) {
        <div class="kpis">
          <div><strong>{{ d.office.seats }}</strong><span>usuários{{ d.office.maxUsers ? ' de ' + d.office.maxUsers : '' }}</span></div>
          <div><strong>{{ d.clients }}</strong><span>clientes</span></div>
          <div><strong>{{ d.cases }}</strong><span>processos</span></div>
          <div><strong>{{ d.contracts }}</strong><span>contratos</span></div>
          <div><strong>{{ d.boards }}</strong><span>quadros</span></div>
          <div><strong>{{ d.leads }}</strong><span>oportunidades</span></div>
        </div>
        <p class="muted small">Criado em {{ d.office.createdAt | brDate }} · ID {{ d.office.id }}
          @if (d.office.status === 'SUSPENDED') { · <span class="danger">Suspenso: {{ d.office.suspendedReason || 'sem motivo informado' }}</span> }</p>
        <div class="form-section-title">Equipe ({{ d.users.length }})</div>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Integrante</th><th>Papel / cargo</th><th>OAB · CPF</th><th>Situação</th><th>Último acesso</th><th></th></tr></thead>
            <tbody>
              @for (u of d.users; track u.uid) {
                <tr>
                  <td><div class="name">{{ u.displayName }} @if (u.owner) { <span class="badge gold">dono</span> }</div><div class="muted small">{{ u.email }}</div></td>
                  <td>{{ u.customRoleName || u.roleLabel }}</td>
                  <td class="small">{{ u.oabNumber || '—' }}<br /><span class="muted">{{ u.cpfMasked || '' }}</span></td>
                  <td><span class="badge" [class.success]="u.status === 'ACTIVE'" [class.danger]="u.status === 'BLOCKED'" [class.info]="u.status === 'INVITED'">{{ statusLabels[u.status] }}</span></td>
                  <td class="muted small">{{ u.lastLoginAt ? (u.lastLoginAt | relative) : 'Nunca acessou' }}</td>
                  <td class="actions">
                    <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações do usuário"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #menu="matMenu" xPosition="before">
                      <button mat-menu-item (click)="setPassword(d.office, u)"><mat-icon>password</mat-icon>Definir nova senha</button>
                      <button mat-menu-item (click)="resetLink(d.office, u)"><mat-icon>link</mat-icon>Copiar link de redefinição</button>
                    </mat-menu>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (detail(); as d) {
        <button mat-stroked-button (click)="addAdmin(d.office)"><mat-icon>person_add</mat-icon>Novo administrador</button>
      }
      <button mat-flat-button mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; min-width: min(860px, 90vw); }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 10px; margin: 4px 0 12px; }
    .kpis div { background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
    .kpis strong { display: block; font-size: 22px; }
    .kpis span { font-size: 12px; color: var(--app-text-muted); }
    .small { font-size: 12px; }
    .name { font-weight: 600; }
    .danger { color: var(--app-danger); }
    .actions { text-align: right; width: 56px; }
  `,
})
export class OfficeDetailDialogComponent {
  private readonly officeId = inject<string>(MAT_DIALOG_DATA);
  private readonly service = inject(PlatformService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly clipboard = inject(Clipboard);
  readonly statusLabels = USER_STATUS_LABELS;
  readonly detail = signal<OfficeDetail | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.service.detail(this.officeId).subscribe((d) => this.detail.set(d));
  }

  addAdmin(office: OfficeSummary): void {
    this.dialog.open(AdminDialogComponent, { data: office, width: '760px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((u?: OfficeUser) => {
        if (u) {
          this.toast.success(`${u.displayName} cadastrado como administrador.`);
          this.load();
        }
      });
  }

  setPassword(office: OfficeSummary, user: OfficeUser): void {
    this.dialog.open(PasswordDialogComponent, {
      data: { title: `Nova senha · ${user.displayName}`, subtitle: `${office.name} · ${user.email}` }, width: '480px',
    }).afterClosed().subscribe((password?: string) => {
      if (password) {
        this.service.setPassword(office.id, user.uid, password).subscribe(() => this.toast.success('Senha redefinida e sessões encerradas.'));
      }
    });
  }

  resetLink(office: OfficeSummary, user: OfficeUser): void {
    this.service.resetLink(office.id, user.uid).subscribe((r) => {
      this.clipboard.copy(r.link);
      this.toast.success('Link de redefinição copiado.');
    });
  }
}
