import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { FormArray, FormControl, FormGroup } from '@angular/forms';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { DuplicateMatch, EmployeeOption } from '../../core/models/models';
import { SOURCE_LABELS } from '../crm/crm.service';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Client, ClientRequest, ClientType } from '../../core/models/models';
import { UF } from '../../shared/labels';
import { cpfCnpjValidator, formatCpfCnpj, formatPhone } from '../../shared/validators/br-validators';
import { ClientsService } from './clients.service';

const MARITAL = ['Solteiro(a)', 'Casado(a)', 'União estável', 'Divorciado(a)', 'Separado(a)', 'Viúvo(a)'];

/** Cadastro/edição de cliente PF ou PJ. Retorna o cliente salvo. */
@Component({
  selector: 'app-client-form-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatButtonToggleModule, MatIconModule, RouterLink,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ data ? 'edit' : 'person_add' }}</mat-icon>{{ data ? 'Editar cliente' : 'Novo cliente' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="clientForm" (ngSubmit)="save()">
        <div class="type-row">
          <mat-button-toggle-group formControlName="type" aria-label="Tipo de pessoa">
            <mat-button-toggle value="PF"><mat-icon>person</mat-icon> Pessoa física</mat-button-toggle>
            <mat-button-toggle value="PJ"><mat-icon>apartment</mat-icon> Pessoa jurídica</mat-button-toggle>
          </mat-button-toggle-group>
        </div>

        @if (duplicates().length) {
          <div class="dup" role="alert">
            <mat-icon>content_copy</mat-icon>
            <div>
              <strong>Possível cadastro duplicado</strong>
              @for (d of duplicates(); track d.client.id) {
                <div><a [routerLink]="['/clientes', d.client.id]" mat-dialog-close>{{ d.client.name }}</a>
                  <span class="muted"> · {{ d.reason }} · {{ d.client.documentMasked }}</span></div>
              }
            </div>
          </div>
        }
        <div class="form-section-title">Identificação</div>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>{{ isPj() ? 'Razão social' : 'Nome completo' }}</mat-label>
          <input matInput formControlName="name" (blur)="checkDuplicates()" />
          @if (form.controls.name.invalid) { <mat-error>Campo obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>{{ isPj() ? 'CNPJ' : 'CPF' }}</mat-label>
          <input matInput formControlName="document" (input)="maskDoc($event)" (blur)="checkDuplicates()" [placeholder]="sensitiveLocked() ? data!.documentMasked : ''" />
          @if (sensitiveLocked()) { <mat-hint>Protegido — sem permissão para visualizar</mat-hint> }
          @if (form.controls.document.hasError('required')) { <mat-error>Obrigatório</mat-error> }
          @if (form.controls.document.hasError('cpf') || form.controls.document.hasError('cnpj')) { <mat-error>{{ isPj() ? 'CNPJ' : 'CPF' }} inválido</mat-error> }
        </mat-form-field>
        @if (isPj()) {
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Nome fantasia</mat-label>
            <input matInput formControlName="tradeName" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c3">
            <mat-label>Inscrição estadual</mat-label>
            <input matInput formControlName="stateRegistration" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c3">
            <mat-label>Inscrição municipal</mat-label>
            <input matInput formControlName="municipalRegistration" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c8">
            <mat-label>Representante legal</mat-label>
            <input matInput formControlName="legalRepresentative" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Cargo do representante</mat-label>
            <input matInput formControlName="legalRepresentativeRole" placeholder="Ex.: sócio-administrador" />
          </mat-form-field>
        } @else {
          <mat-form-field appearance="outline" class="c4">
            <mat-label>RG</mat-label>
            <input matInput formControlName="rg" [placeholder]="sensitiveLocked() && data?.rg ? '***' : ''" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Data de nascimento</mat-label>
            <input matInput type="date" formControlName="birthDate" [max]="today" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Estado civil</mat-label>
            <mat-select formControlName="maritalStatus">
              <mat-option value="">—</mat-option>
              @for (m of marital; track m) { <mat-option [value]="m">{{ m }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Profissão</mat-label>
            <input matInput formControlName="profession" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
            <mat-label>Nacionalidade</mat-label>
            <input matInput formControlName="nationality" />
          </mat-form-field>
        }

        <div class="form-section-title">Contato</div>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Telefone</mat-label>
          <input matInput formControlName="phone" (input)="maskPhone('phone', $event)" (blur)="checkDuplicates()" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>WhatsApp</mat-label>
          <input matInput formControlName="whatsapp" (input)="maskPhone('whatsapp', $event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>E-mail</mat-label>
          <input matInput type="email" formControlName="email" (blur)="checkDuplicates()" />
          @if (form.controls.email.invalid) { <mat-error>E-mail inválido</mat-error> }
        </mat-form-field>

        @if (isPj()) {
          <div class="form-section-title row">Pessoas de contato <span class="spacer"></span>
            <button mat-button type="button" (click)="addContact()" [disabled]="contacts.length >= 10"><mat-icon>add</mat-icon>Adicionar</button></div>
          @for (c of contacts.controls; track c; let i = $index) {
            <ng-container [formGroup]="c">
              <mat-form-field appearance="outline" class="c3"><mat-label>Nome</mat-label><input matInput formControlName="name" /></mat-form-field>
              <mat-form-field appearance="outline" class="c3"><mat-label>Função</mat-label><input matInput formControlName="role" placeholder="Ex.: financeiro" /></mat-form-field>
              <mat-form-field appearance="outline" class="c2"><mat-label>Telefone</mat-label><input matInput formControlName="phone" /></mat-form-field>
              <mat-form-field appearance="outline" class="c3"><mat-label>E-mail</mat-label><input matInput formControlName="email" /></mat-form-field>
              <div class="c1 rm"><button mat-icon-button type="button" (click)="contacts.removeAt(i)" aria-label="Remover contato"><mat-icon>delete</mat-icon></button></div>
            </ng-container>
          }
        }

        <div class="form-section-title">Origem e responsável</div>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Origem do cliente</mat-label>
          <mat-select formControlName="source">
            <mat-option value="">Não informada</mat-option>
            @for (s of sources; track s) { <mat-option [value]="s">{{ sourceLabels[s] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Detalhe da origem</mat-label>
          <input matInput formControlName="sourceDetail" placeholder="Ex.: indicado por cliente X" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Advogado responsável</mat-label>
          <mat-select formControlName="responsibleUid">
            <mat-option value="">—</mat-option>
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
        </mat-form-field>

        <div class="form-section-title">Endereço</div>
        <ng-container formGroupName="address">
          <mat-form-field appearance="outline" class="c3">
            <mat-label>CEP</mat-label>
            <input matInput formControlName="zipCode" maxlength="9" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Logradouro</mat-label>
            <input matInput formControlName="street" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c3">
            <mat-label>Número</mat-label>
            <input matInput formControlName="number" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c3">
            <mat-label>Complemento</mat-label>
            <input matInput formControlName="complement" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c3">
            <mat-label>Bairro</mat-label>
            <input matInput formControlName="neighborhood" />
          </mat-form-field>
          <mat-form-field appearance="outline" class="c4">
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
        </ng-container>

        <div class="form-section-title">Observações</div>
        <mat-form-field appearance="outline">
          <mat-label>Observações internas</mat-label>
          <textarea matInput rows="3" formControlName="notes" maxlength="4000"></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="clientForm" [disabled]="saving()">
        <mat-icon>save</mat-icon>{{ saving() ? 'Salvando…' : 'Salvar' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .type-row { grid-column: span 12; margin: 4px 0 16px; }
    .dup { grid-column: span 12; display: flex; gap: 10px; padding: 10px 14px; border-radius: 10px; margin-bottom: 8px;
      background: var(--app-warning-bg); color: var(--app-warning); font-size: 13px; }
    .dup a { color: inherit; font-weight: 600; }
    .c1 { grid-column: span 1; }
    .rm { display: flex; align-items: flex-start; padding-top: 8px; }
    @media (max-width: 760px) { .form-grid > .c1 { grid-column: span 12; } }
    mat-dialog-content { max-height: 70vh; }
  `,
})
export class ClientFormDialogComponent {
  readonly data = inject<Client | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ClientFormDialogComponent>);
  private readonly service = inject(ClientsService);
  readonly ufs = UF;
  readonly marital = MARITAL;
  readonly today = new Date().toISOString().slice(0, 10);
  readonly saving = signal(false);
  private readonly api = inject(ApiService);
  readonly sources = Object.keys(SOURCE_LABELS);
  readonly sourceLabels = SOURCE_LABELS as Record<string, string>;
  readonly staff = toSignal(this.api.get<EmployeeOption[]>('/employees/options', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly duplicates = signal<DuplicateMatch[]>([]);
  private lastDuplicateQuery = '';
  /** Edição por quem não vê dados sensíveis: CPF/CNPJ e RG não são enviados. */
  readonly sensitiveLocked = signal(!!this.data && !this.data.sensitiveVisible);

  private currentType: ClientType = this.data?.type ?? 'PF';
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly form = this.fb.group({
    type: [this.data?.type ?? ('PF' as ClientType), Validators.required],
    name: [this.data?.name ?? '', [Validators.required, Validators.maxLength(200)]],
    tradeName: [this.data?.tradeName ?? ''],
    document: [
      this.data?.sensitiveVisible ? this.data.document : '',
      this.data && !this.data.sensitiveVisible ? [] : [Validators.required, cpfCnpjValidator(() => this.currentType)],
    ],
    rg: [this.data?.sensitiveVisible ? (this.data.rg ?? '') : ''],
    birthDate: [this.data?.birthDate ?? ''],
    phone: [this.data?.phone ?? ''],
    whatsapp: [this.data?.whatsapp ?? ''],
    email: [this.data?.email ?? '', Validators.email],
    profession: [this.data?.profession ?? ''],
    maritalStatus: [this.data?.maritalStatus ?? ''],
    notes: [this.data?.notes ?? ''],
    nationality: [this.data?.nationality ?? (this.data ? '' : 'Brasileira')],
    stateRegistration: [this.data?.stateRegistration ?? ''],
    municipalRegistration: [this.data?.municipalRegistration ?? ''],
    legalRepresentative: [this.data?.legalRepresentative ?? ''],
    legalRepresentativeRole: [this.data?.legalRepresentativeRole ?? ''],
    source: [this.data?.source ?? ''],
    sourceDetail: [this.data?.sourceDetail ?? ''],
    responsibleUid: [this.data?.responsibleUid ?? ''],
    contacts: new FormArray((this.data?.contacts ?? []).map((c) => ClientFormDialogComponent.contactGroup(c))),
    address: this.fb.group({
      zipCode: [this.data?.address?.zipCode ?? ''],
      street: [this.data?.address?.street ?? ''],
      number: [this.data?.address?.number ?? ''],
      complement: [this.data?.address?.complement ?? ''],
      neighborhood: [this.data?.address?.neighborhood ?? ''],
      city: [this.data?.address?.city ?? ''],
      state: [this.data?.address?.state ?? ''],
    }),
  });

  constructor() {
    if (this.sensitiveLocked()) {
      this.form.controls.document.disable();
      this.form.controls.rg.disable();
      this.form.controls.type.disable();
    }
    this.form.controls.type.valueChanges.subscribe((type) => {
      this.currentType = type;
      this.form.controls.document.updateValueAndValidity();
    });
  }

  static contactGroup(c?: { name: string; role: string | null; phone: string | null; email: string | null }) {
    return new FormGroup({
      name: new FormControl(c?.name ?? '', { nonNullable: true, validators: [Validators.required, Validators.maxLength(120)] }),
      role: new FormControl(c?.role ?? '', { nonNullable: true }),
      phone: new FormControl(c?.phone ?? '', { nonNullable: true }),
      email: new FormControl(c?.email ?? '', { nonNullable: true, validators: [Validators.email] }),
    });
  }

  get contacts() {
    return this.form.controls.contacts;
  }

  addContact(): void {
    this.contacts.push(ClientFormDialogComponent.contactGroup());
  }

  /** Avisa sobre possíveis duplicidades (mesmo documento, telefone, e-mail ou nome). */
  checkDuplicates(): void {
    const v = this.form.getRawValue();
    const params = { name: v.name, document: this.sensitiveLocked() ? '' : v.document, phone: v.phone, email: v.email, excludeId: this.data?.id ?? '' };
    const key = JSON.stringify(params);
    if (key === this.lastDuplicateQuery || (!v.name && !v.document && !v.phone && !v.email)) return;
    this.lastDuplicateQuery = key;
    this.api.get<DuplicateMatch[]>('/clients/duplicates', params, true).pipe(catchError(() => of([])))
      .subscribe((d) => this.duplicates.set(d));
  }

  isPj(): boolean {
    return this.form.controls.type.value === 'PJ';
  }

  maskDoc(event: Event): void {
    this.form.controls.document.setValue(formatCpfCnpj((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  maskPhone(field: 'phone' | 'whatsapp', event: Event): void {
    this.form.controls[field].setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const empty = (s: string | null | undefined) => (s && s.trim() ? s.trim() : null);
    const address = (Object.values(v.address) as string[]).some((x) => x && x.trim()) ? {
      zipCode: empty(v.address.zipCode), street: empty(v.address.street), number: empty(v.address.number),
      complement: empty(v.address.complement), neighborhood: empty(v.address.neighborhood),
      city: empty(v.address.city), state: empty(v.address.state),
    } : null;
    const body: ClientRequest = {
      type: v.type,
      name: v.name.trim(),
      tradeName: v.type === 'PJ' ? empty(v.tradeName) : null,
      document: this.sensitiveLocked() ? null : v.document,
      rg: this.sensitiveLocked() || v.type === 'PJ' ? null : empty(v.rg),
      birthDate: v.type === 'PF' ? empty(v.birthDate) : null,
      phone: empty(v.phone),
      whatsapp: empty(v.whatsapp),
      email: empty(v.email),
      profession: v.type === 'PF' ? empty(v.profession) : null,
      maritalStatus: v.type === 'PF' ? empty(v.maritalStatus) : null,
      notes: empty(v.notes),
      address,
      responsibleUid: empty(v.responsibleUid),
      nationality: v.type === 'PF' ? empty(v.nationality) : null,
      stateRegistration: v.type === 'PJ' ? empty(v.stateRegistration) : null,
      municipalRegistration: v.type === 'PJ' ? empty(v.municipalRegistration) : null,
      legalRepresentative: v.type === 'PJ' ? empty(v.legalRepresentative) : null,
      legalRepresentativeRole: v.type === 'PJ' ? empty(v.legalRepresentativeRole) : null,
      contacts: v.type === 'PJ' ? v.contacts.filter((c) => c.name.trim()).map((c) => ({
        name: c.name.trim(), role: empty(c.role), phone: empty(c.phone), email: empty(c.email),
      })) : [],
      source: empty(v.source),
      sourceDetail: empty(v.sourceDetail),
    };
    this.saving.set(true);
    const request = this.data ? this.service.update(this.data.id, body) : this.service.create(body);
    request.subscribe({
      next: (client) => this.ref.close(client),
      error: () => this.saving.set(false),
    });
  }
}
