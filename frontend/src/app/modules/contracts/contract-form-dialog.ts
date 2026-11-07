import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ClientSummary, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import { CasesService } from '../cases/cases.service';
import { ClientsService } from '../clients/clients.service';
import { localToday } from '../finance/transaction-dialogs';
import { CONTRACT_TYPE_LABELS, Contract, ContractRequest, ContractType, ContractsService, FEE_MODEL_LABELS, FeeModel } from './contracts.service';

/** Cadastro/edição de contrato com modelos de honorários (fixo, parcelado, etapas, êxito, mensal). */
@Component({
  selector: 'app-contract-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, MatAutocompleteModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>handshake</mat-icon>{{ c ? 'Editar contrato ' + c.number : 'Novo contrato' }}</h2>
    <mat-dialog-content>
      @if (locked) { <div class="notice"><mat-icon>lock</mat-icon>Contrato ativo: honorários bloqueados. Para alterá-los, registre um aditivo (novo contrato).</div> }
      <form [formGroup]="form" class="form-grid" id="contractForm" (ngSubmit)="save()">
        <div class="form-section-title">Partes e objeto</div>
        <mat-form-field class="c8"><mat-label>Título</mat-label><input matInput formControlName="title" maxlength="200" placeholder="Ex.: Ação revisional bancária" /></mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">@for (t of types; track t) { <mat-option [value]="t">{{ typeLabels[t] }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Cliente</mat-label>
          <input matInput [formControl]="clientSearch" [matAutocomplete]="clientAuto" placeholder="Nome ou CPF/CNPJ" />
          <mat-autocomplete #clientAuto="matAutocomplete" [displayWith]="display" (optionSelected)="clientId = $event.option.value.id">
            @for (o of clientOptions(); track o.id) { <mat-option [value]="o">{{ o.name }} <span class="muted">· {{ o.documentMasked }}</span></mat-option> }
          </mat-autocomplete>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Processo (opcional)</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ ou cliente" />
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="display" (optionSelected)="pickCase($event.option.value)">
            @for (o of caseOptions(); track o.id) { <mat-option [value]="o">{{ o.name }}</mat-option> }
          </mat-autocomplete>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Advogado responsável</mat-label>
          <mat-select formControlName="responsibleUid">@for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field class="c3"><mat-label>Início</mat-label><input matInput type="date" formControlName="startDate" /></mat-form-field>
        <mat-form-field class="c3"><mat-label>Término (opcional)</mat-label><input matInput type="date" formControlName="endDate" /></mat-form-field>
        <mat-form-field><mat-label>Objeto / escopo dos serviços</mat-label><textarea matInput rows="3" formControlName="object" maxlength="4000"></textarea></mat-form-field>

        <div class="form-section-title">Honorários</div>
        <mat-form-field class="c4">
          <mat-label>Modalidade</mat-label>
          <mat-select formControlName="feeModel">@for (f of feeModels; track f) { <mat-option [value]="f">{{ feeLabels[f] }}</mat-option> }</mat-select>
        </mat-form-field>
        @switch (form.controls.feeModel.value) {
          @case ('FIXO') {
            <mat-form-field class="c4"><mat-label>Valor (R$)</mat-label><input matInput formControlName="amount" inputmode="decimal" /></mat-form-field>
            <mat-form-field class="c4"><mat-label>Vencimento</mat-label><input matInput type="date" formControlName="firstDueDate" /></mat-form-field>
          }
          @case ('PARCELADO') {
            <mat-form-field class="c3"><mat-label>Valor total (R$)</mat-label><input matInput formControlName="amount" inputmode="decimal" /></mat-form-field>
            <mat-form-field class="c2"><mat-label>Parcelas</mat-label><input matInput type="number" min="2" max="60" formControlName="installments" /></mat-form-field>
            <mat-form-field class="c3"><mat-label>1º vencimento</mat-label><input matInput type="date" formControlName="firstDueDate" /></mat-form-field>
          }
          @case ('MENSAL') {
            <mat-form-field class="c3"><mat-label>Mensalidade (R$)</mat-label><input matInput formControlName="monthly" inputmode="decimal" /></mat-form-field>
            <mat-form-field class="c2"><mat-label>Meses</mat-label><input matInput type="number" min="1" max="60" formControlName="months" /></mat-form-field>
            <mat-form-field class="c3"><mat-label>1º vencimento</mat-label><input matInput type="date" formControlName="firstDueDate" /></mat-form-field>
          }
        }
        @if (form.controls.feeModel.value === 'ETAPAS') {
          <div class="stages" formArrayName="stages">
            @for (s of stages.controls; track $index; let i = $index) {
              <div class="stage" [formGroupName]="i">
                <mat-form-field subscriptSizing="dynamic" class="sd"><mat-label>Etapa</mat-label><input matInput formControlName="description" placeholder="Ex.: Protocolo da inicial" /></mat-form-field>
                <mat-form-field subscriptSizing="dynamic" class="sa"><mat-label>Valor (R$)</mat-label><input matInput formControlName="amount" inputmode="decimal" /></mat-form-field>
                <mat-form-field subscriptSizing="dynamic" class="sa"><mat-label>Previsão</mat-label><input matInput type="date" formControlName="dueDate" /></mat-form-field>
                <button mat-icon-button type="button" (click)="stages.removeAt(i)" [disabled]="locked || stages.length === 1" aria-label="Remover etapa"><mat-icon>delete</mat-icon></button>
              </div>
            }
            <button mat-stroked-button type="button" (click)="addStage()" [disabled]="locked"><mat-icon>add</mat-icon>Adicionar etapa</button>
          </div>
        }
        <mat-form-field class="c3">
          <mat-label>Êxito (%)</mat-label>
          <input matInput formControlName="successPercent" inputmode="decimal" placeholder="Ex.: 20" />
          <mat-hint>{{ form.controls.feeModel.value === 'EXITO' ? 'Obrigatório' : 'Opcional, combina com a modalidade' }}</mat-hint>
        </mat-form-field>
        <mat-form-field class="c9"><mat-label>Base do êxito</mat-label><input matInput formControlName="successBase" placeholder="Ex.: proveito econômico obtido pelo cliente" /></mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="contractForm" [disabled]="saving()"><mat-icon>save</mat-icon>Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; }
    .notice { display: flex; gap: 8px; align-items: center; padding: 10px 14px; border-radius: 10px; background: var(--app-warning-bg); color: var(--app-warning); margin-bottom: 12px; }
    .stages { grid-column: span 12; display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
    .stage { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .sd { flex: 2 1 240px; }
    .sa { flex: 1 1 140px; }
    .form-grid .c9 { grid-column: span 9; }
    @media (max-width: 760px) { .form-grid .c9 { grid-column: span 12; } }
  `,
})
export class ContractFormDialogComponent {
  readonly c = inject<Contract | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ContractFormDialogComponent>);
  private readonly service = inject(ContractsService);
  private readonly clients = inject(ClientsService);
  private readonly casesService = inject(CasesService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly locked = this.c?.status === 'ATIVO';
  readonly types = Object.keys(CONTRACT_TYPE_LABELS) as ContractType[];
  readonly typeLabels = CONTRACT_TYPE_LABELS;
  readonly feeModels = Object.keys(FEE_MODEL_LABELS) as FeeModel[];
  readonly feeLabels = FEE_MODEL_LABELS;
  readonly lawyers = toSignal(this.casesService.lawyers(), { initialValue: [] });
  readonly saving = signal(false);
  readonly clientOptions = signal<ClientSummary[]>([]);
  readonly caseOptions = signal<{ id: string; name: string; clientId: string; clientName: string }[]>([]);

  clientId: string | null = this.c?.clientId ?? null;
  caseId: string | null = this.c?.caseId ?? null;
  readonly clientSearch = new FormControl<{ id: string; name: string } | string>(this.c ? { id: this.c.clientId, name: this.c.clientName } : '');
  readonly caseSearch = new FormControl<{ id: string; name: string } | string>(this.c?.caseId ? { id: this.c.caseId, name: this.c.caseNumber ?? 'Processo' } : '');

  readonly stages = this.fb.array((this.c?.stages ?? []).map((s) => this.stageGroup(s.id, s.description, centsToInput(s.amountCents), s.dueDate ?? '')));
  readonly form = this.fb.group({
    title: [this.c?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    type: [this.c?.type ?? ('HONORARIOS' as ContractType)],
    responsibleUid: [this.c?.responsibleUid ?? this.auth.me()?.uid ?? '', Validators.required],
    startDate: [this.c?.startDate ?? localToday(), Validators.required],
    endDate: [this.c?.endDate ?? ''],
    object: [this.c?.object ?? '', [Validators.required, Validators.maxLength(4000)]],
    feeModel: [this.c?.feeModel ?? ('PARCELADO' as FeeModel)],
    amount: [centsToInput(this.c?.amountCents)],
    installments: [this.c?.installments ?? 3],
    firstDueDate: [this.c?.firstDueDate ?? localToday()],
    monthly: [centsToInput(this.c?.monthlyCents)],
    months: [this.c?.months ?? 12],
    successPercent: [this.c?.successPercentBp ? String(this.c.successPercentBp / 100).replace('.', ',') : ''],
    successBase: [this.c?.successBase ?? ''],
    stages: this.stages,
  });

  constructor() {
    if (this.stages.length === 0) this.addStage();
    if (this.locked) {
      ['feeModel', 'amount', 'installments', 'firstDueDate', 'monthly', 'months', 'successPercent', 'successBase'].forEach((k) => this.form.get(k)?.disable());
      this.stages.disable();
    }
    this.clientSearch.valueChanges.pipe(debounceTime(250), filter((v): v is string => typeof v === 'string'), switchMap((t) => {
      this.clientId = null;
      return t.trim().length >= 2 ? this.clients.list({ search: t.trim(), status: 'ACTIVE' }, null, 8).pipe(catchError(() => of({ items: [] as ClientSummary[], nextCursor: null })))
        : of({ items: [] as ClientSummary[], nextCursor: null });
    }), takeUntilDestroyed()).subscribe((p) => this.clientOptions.set(p.items));
    this.caseSearch.valueChanges.pipe(debounceTime(250), filter((v): v is string => typeof v === 'string'), switchMap((t) => {
      this.caseId = null;
      return t.trim().length >= 2 ? this.casesService.list({ search: t.trim() }, null, 8).pipe(catchError(() => of({ items: [] as LegalCase[], nextCursor: null })))
        : of({ items: [] as LegalCase[], nextCursor: null });
    }), takeUntilDestroyed()).subscribe((p) => this.caseOptions.set(p.items.map((c) => ({ id: c.id, name: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}`, clientId: c.clientId, clientName: c.clientName }))));
  }

  private stageGroup(id: string | null, description: string, amount: string, dueDate: string) {
    return this.fb.group({ id: [id as string | null], description: [description, Validators.required], amount: [amount, Validators.required], dueDate: [dueDate] });
  }

  addStage(): void {
    (this.stages as FormArray).push(this.stageGroup(null, '', '', ''));
  }

  display = (v: { name: string } | string | null): string => (typeof v === 'string' ? v : v?.name ?? '');

  pickCase(c: { id: string; clientId: string; clientName: string }): void {
    this.caseId = c.id;
    if (!this.clientId) {
      this.clientId = c.clientId;
      this.clientSearch.setValue({ id: c.clientId, name: c.clientName }, { emitEvent: false });
    }
  }

  save(): void {
    if (!this.clientId) {
      this.toast.error('Selecione o cliente na lista.');
      return;
    }
    const model = this.form.controls.feeModel.getRawValue();
    if (model !== 'ETAPAS') this.stages.disable();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      if (model !== 'ETAPAS' && !this.locked) this.stages.enable();
      return;
    }
    const v = this.form.getRawValue();
    const pct = v.successPercent ? Math.round(Number(String(v.successPercent).replace(',', '.')) * 100) : null;
    const body: ContractRequest = {
      title: v.title.trim(), type: v.type, clientId: this.clientId, caseId: this.caseId, responsibleUid: v.responsibleUid,
      object: v.object.trim(), startDate: v.startDate, endDate: v.endDate || null, feeModel: v.feeModel,
      amountCents: v.feeModel === 'FIXO' || v.feeModel === 'PARCELADO' ? parseMoneyToCents(v.amount) : null,
      installments: v.feeModel === 'PARCELADO' ? Number(v.installments) : v.feeModel === 'FIXO' ? 1 : null,
      firstDueDate: ['FIXO', 'PARCELADO', 'MENSAL'].includes(v.feeModel) ? v.firstDueDate : null,
      monthlyCents: v.feeModel === 'MENSAL' ? parseMoneyToCents(v.monthly) : null,
      months: v.feeModel === 'MENSAL' ? Number(v.months) : null,
      stages: v.feeModel === 'ETAPAS' ? v.stages.map((s) => ({ id: s.id ?? undefined, description: s.description.trim(), amountCents: parseMoneyToCents(s.amount) ?? 0, dueDate: s.dueDate || null })) : [],
      successPercentBp: pct && pct > 0 ? pct : null,
      successBase: v.successBase.trim() || null,
    };
    this.saving.set(true);
    const req = this.c ? this.service.update(this.c.id, body) : this.service.create(body);
    req.subscribe({ next: (r) => this.ref.close(r), error: () => { this.saving.set(false); if (model !== 'ETAPAS' && !this.locked) this.stages.enable(); } });
  }
}
