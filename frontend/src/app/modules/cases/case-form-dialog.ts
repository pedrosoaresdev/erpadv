import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { CasePhase, CaseRequest, CaseStatus, ClientSummary, LegalArea, LegalCase } from '../../core/models/models';
import { AREA_LABELS, CASE_STATUS_LABELS, INSTANCES, PHASE_LABELS, UF, enumOptions } from '../../shared/labels';
import { centsToInput, cnjValidator, formatCnj, parseMoneyToCents } from '../../shared/validators/br-validators';
import { ClientsService } from '../clients/clients.service';
import { CasesService } from './cases.service';

export interface CaseFormData {
  legalCase?: LegalCase | null;
  client?: { id: string; name: string } | null;
}

interface ClientPick {
  id: string;
  name: string;
}

/** Cadastro/edição de processo judicial. */
@Component({
  selector: 'app-case-form-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatAutocompleteModule, MatSlideToggleModule,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>gavel</mat-icon>{{ editing ? 'Editar processo' : 'Novo processo' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="caseForm" (ngSubmit)="save()">
        <div class="form-section-title">Partes</div>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Cliente</mat-label>
          <input matInput [formControl]="clientSearch" [matAutocomplete]="clientAuto" placeholder="Digite nome ou CPF/CNPJ" />
          <mat-icon matSuffix>person_search</mat-icon>
          <mat-autocomplete #clientAuto="matAutocomplete" [displayWith]="displayClient" (optionSelected)="pickClient($event.option.value)">
            @for (c of clientOptions(); track c.id) {
              <mat-option [value]="c">{{ c.name }} <span class="muted">· {{ c.documentMasked }}</span></mat-option>
            }
          </mat-autocomplete>
          @if (form.controls.clientId.invalid && clientSearch.touched) { <mat-error>Selecione um cliente da lista</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Parte contrária</mat-label>
          <input matInput formControlName="opposingParty" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Polo ativo</mat-label>
          <input matInput formControlName="activeParty" placeholder="Autor(es) / requerente(s)" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Polo passivo</mat-label>
          <input matInput formControlName="passiveParty" placeholder="Réu(s) / requerido(s)" />
        </mat-form-field>

        <div class="form-section-title">Identificação processual</div>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Número CNJ</mat-label>
          <input matInput formControlName="cnjNumber" (input)="maskCnj($event)" placeholder="0000000-00.0000.0.00.0000" />
          <mat-hint>Deixe em branco se ainda não distribuído</mat-hint>
          @if (form.controls.cnjNumber.invalid) { <mat-error>Número CNJ inválido (dígito verificador)</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>Área</mat-label>
          <mat-select formControlName="area">
            @for (o of areas; track o.value) { <mat-option [value]="o.value" [disabled]="o.value === 'CRIMINAL' && !canCriminal">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>Situação</mat-label>
          <mat-select formControlName="status">
            @for (o of statuses; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Classe processual</mat-label>
          <input matInput formControlName="proceduralClass" placeholder="Ex.: Procedimento Comum Cível" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Assunto principal</mat-label>
          <input matInput formControlName="subject" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Assuntos secundários (separados por ;)</mat-label>
          <input matInput formControlName="secondarySubjects" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Fase processual</mat-label>
          <mat-select formControlName="phase">
            <mat-option [value]="null">—</mat-option>
            @for (o of phases; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
          </mat-select>
        </mat-form-field>

        <div class="form-section-title">Juízo</div>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>Tribunal</mat-label>
          <input matInput formControlName="court" placeholder="TJSP, TRT2, STJ…" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>Instância</mat-label>
          <mat-select formControlName="instance">
            <mat-option value="">—</mat-option>
            @for (i of instances; track i) { <mat-option [value]="i">{{ i }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Juízo / órgão julgador</mat-label>
          <input matInput formControlName="judgingBody" placeholder="Ex.: 2º Juizado Especial Cível" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Vara</mat-label>
          <input matInput formControlName="courtDivision" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c3">
          <mat-label>Comarca</mat-label>
          <input matInput formControlName="district" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c2">
          <mat-label>UF</mat-label>
          <mat-select formControlName="state">
            <mat-option value="">—</mat-option>
            @for (uf of ufs; track uf) { <mat-option [value]="uf">{{ uf }}</mat-option> }
          </mat-select>
        </mat-form-field>

        <div class="form-section-title">Gestão</div>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Advogado responsável</mat-label>
          <mat-select formControlName="responsibleUid">
            @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}{{ l.oabNumber ? ' · OAB ' + l.oabNumber : '' }}</mat-option> }
          </mat-select>
          @if (form.controls.responsibleUid.invalid) { <mat-error>Selecione o responsável</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Valor da causa (R$)</mat-label>
          <input matInput formControlName="claimValue" inputmode="decimal" placeholder="0,00" />
          @if (form.controls.claimValue.invalid) { <mat-error>Valor inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Data de distribuição</mat-label>
          <input matInput type="date" formControlName="distributionDate" [max]="today" />
        </mat-form-field>
        <div class="toggle">
          <mat-slide-toggle formControlName="freeJustice">Justiça gratuita</mat-slide-toggle>
          <mat-slide-toggle formControlName="confidential" [disabled]="!canCriminal">Segredo de justiça / sigiloso (acesso restrito)</mat-slide-toggle>
          @if (!canCriminal) { <span class="muted small">Requer permissão do departamento criminal.</span> }
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Observações internas</mat-label>
          <textarea matInput rows="3" formControlName="notes" maxlength="4000"></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="caseForm" [disabled]="saving()"><mat-icon>save</mat-icon>{{ saving() ? 'Salvando…' : 'Salvar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 72vh; }
    .toggle { grid-column: span 12; display: flex; gap: 12px; align-items: center; margin: 4px 0 16px; flex-wrap: wrap; }
    .small { font-size: 12px; }
  `,
})
export class CaseFormDialogComponent {
  private readonly data = inject<CaseFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CaseFormDialogComponent>);
  private readonly service = inject(CasesService);
  private readonly clients = inject(ClientsService);
  private readonly auth = inject(AuthService);

  readonly editing = !!this.data?.legalCase;
  readonly canCriminal = this.auth.has('CRIMINAL_WRITE');
  readonly areas = enumOptions(AREA_LABELS);
  readonly statuses = enumOptions(CASE_STATUS_LABELS, ['ARQUIVADO']);
  readonly ufs = UF;
  readonly instances = INSTANCES;
  readonly phases = enumOptions(PHASE_LABELS);
  readonly today = new Date().toISOString().slice(0, 10);
  readonly saving = signal(false);
  readonly lawyers = toSignal(this.service.lawyers(), { initialValue: [] });
  readonly clientOptions = signal<ClientSummary[]>([]);

  private readonly c = this.data?.legalCase;
  private readonly initialClient: ClientPick | null = this.c
    ? { id: this.c.clientId, name: this.c.clientName }
    : this.data?.client ?? null;

  readonly clientSearch = new FormControl<ClientPick | string>(this.initialClient ?? '');
  readonly form = inject(FormBuilder).nonNullable.group({
    clientId: [this.initialClient?.id ?? '', Validators.required],
    opposingParty: [this.c?.opposingParty ?? ''],
    cnjNumber: [this.c?.cnjNumber ?? '', cnjValidator],
    area: [this.c?.area ?? ('CIVEL' as LegalArea), Validators.required],
    status: [this.c?.status ?? ('EM_ANDAMENTO' as CaseStatus), Validators.required],
    proceduralClass: [this.c?.proceduralClass ?? ''],
    subject: [this.c?.subject ?? ''],
    court: [this.c?.court ?? ''],
    courtDivision: [this.c?.courtDivision ?? ''],
    district: [this.c?.district ?? ''],
    state: [this.c?.state ?? ''],
    responsibleUid: [this.c?.responsibleUid ?? (this.auth.me()?.uid ?? ''), Validators.required],
    claimValue: [centsToInput(this.c?.claimValueCents), Validators.pattern(/^[\d.,\s]*$/)],
    distributionDate: [this.c?.distributionDate ?? ''],
    confidential: [this.c?.confidential ?? false],
    notes: [this.c?.notes ?? ''],
    activeParty: [this.c?.activeParty ?? ''],
    passiveParty: [this.c?.passiveParty ?? ''],
    instance: [this.c?.instance ?? ''],
    judgingBody: [this.c?.judgingBody ?? ''],
    secondarySubjects: [(this.c?.secondarySubjects ?? []).join('; ')],
    freeJustice: [this.c?.freeJustice ?? false],
    phase: [(this.c?.phase ?? null) as CasePhase | null],
  });

  constructor() {
    this.clientSearch.valueChanges
      .pipe(
        debounceTime(250),
        filter((v): v is string => typeof v === 'string'),
        switchMap((term) => {
          this.form.controls.clientId.setValue('');
          return term.trim().length >= 2
            ? this.clients.list({ search: term.trim(), status: 'ACTIVE' }, null, 8)
            : of({ items: [], nextCursor: null });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.clientOptions.set(page.items));
    if (this.c?.restricted && !this.canCriminal) {
      this.form.disable();
    }
  }

  displayClient = (value: ClientPick | string | null): string => (typeof value === 'string' ? value : value?.name ?? '');

  pickClient(client: ClientPick): void {
    this.form.controls.clientId.setValue(client.id);
  }

  maskCnj(event: Event): void {
    this.form.controls.cnjNumber.setValue(formatCnj((event.target as HTMLInputElement).value));
  }

  save(): void {
    this.clientSearch.markAsTouched();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const empty = (s: string) => (s && s.trim() ? s.trim() : null);
    const body: CaseRequest = {
      cnjNumber: empty(v.cnjNumber),
      clientId: v.clientId,
      opposingParty: empty(v.opposingParty),
      court: empty(v.court)?.toUpperCase() ?? null,
      courtDivision: empty(v.courtDivision),
      district: empty(v.district),
      state: empty(v.state),
      area: v.area,
      proceduralClass: empty(v.proceduralClass),
      subject: empty(v.subject),
      responsibleUid: v.responsibleUid,
      claimValueCents: parseMoneyToCents(v.claimValue),
      distributionDate: empty(v.distributionDate),
      status: v.status,
      notes: empty(v.notes),
      confidential: v.confidential,
      activeParty: empty(v.activeParty),
      passiveParty: empty(v.passiveParty),
      instance: empty(v.instance),
      judgingBody: empty(v.judgingBody),
      secondarySubjects: v.secondarySubjects.split(';').map((x) => x.trim()).filter(Boolean).slice(0, 10),
      freeJustice: v.freeJustice,
      phase: v.phase,
    };
    this.saving.set(true);
    const request = this.c ? this.service.update(this.c.id, body) : this.service.create(body);
    request.subscribe({ next: (saved) => this.ref.close(saved), error: () => this.saving.set(false) });
  }
}
