import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Observable, catchError, debounceTime, filter, of, startWith, switchMap } from 'rxjs';
import { ClientSummary } from '../../core/models/models';
import { CentsPipe } from '../../shared/pipes/format.pipes';
import { centsToInput, parseMoneyToCents } from '../../shared/validators/br-validators';
import { ClientsService } from '../clients/clients.service';
import {
  CATEGORY_LABELS,
  EXPENSE_CATEGORIES,
  FinanceCategory,
  FinanceDirection,
  FinanceScope,
  INCOME_CATEGORIES,
  PAYMENT_LABELS,
  PaymentMethod,
  Transaction,
  TransactionRequest,
  splitInstallments,
} from './finance.models';
import { FinanceService } from './finance.service';

export const localToday = (): string => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

const moneyValidator = (c: { value: string }) => {
  const cents = parseMoneyToCents(c.value);
  return cents && cents > 0 ? null : { money: true };
};

export interface TransactionDialogData {
  direction: FinanceDirection;
  scope: FinanceScope;
  transaction?: Transaction | null;
  client?: { id: string; name: string } | null;
}

// ======================================================================= lançamento
@Component({
  selector: 'app-transaction-dialog',
  imports: [
    ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatButtonToggleModule, MatIconModule, MatAutocompleteModule, CentsPipe,
  ],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>{{ isIncome ? 'call_received' : 'call_made' }}</mat-icon>
      {{ t ? 'Editar lançamento' : (isIncome ? 'Nova conta a receber' : 'Nova conta a pagar') }}
      @if (data.scope === 'PESSOAL') { <span class="badge gold">Pessoal</span> }
    </h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="txForm" (ngSubmit)="save()">
        <mat-form-field class="c6">
          <mat-label>Categoria</mat-label>
          <mat-select formControlName="category">
            @for (c of categories; track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Descrição</mat-label>
          <input matInput formControlName="description" maxlength="200" [placeholder]="isIncome ? 'Ex.: Honorários iniciais' : 'Ex.: Aluguel da sede'" />
          @if (form.controls.description.invalid) { <mat-error>Obrigatória</mat-error> }
        </mat-form-field>

        <mat-form-field class="c4">
          <mat-label>{{ schedule() === 'PARCELADO' ? 'Valor total (R$)' : 'Valor (R$)' }}</mat-label>
          <input matInput formControlName="amount" inputmode="decimal" placeholder="0,00" />
          @if (form.controls.amount.invalid) { <mat-error>Informe um valor maior que zero</mat-error> }
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>{{ schedule() === 'UNICO' ? 'Vencimento' : '1º vencimento' }}</mat-label>
          <input matInput type="date" formControlName="dueDate" />
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Forma de pagamento prevista</mat-label>
          <mat-select formControlName="paymentMethod">
            <mat-option [value]="null">—</mat-option>
            @for (p of methods; track p) { <mat-option [value]="p">{{ paymentLabels[p] }}</mat-option> }
          </mat-select>
        </mat-form-field>

        @if (!t) {
          <div class="schedule">
            <mat-button-toggle-group [value]="schedule()" (change)="schedule.set($event.value)" hideSingleSelectionIndicator>
              <mat-button-toggle value="UNICO">Único</mat-button-toggle>
              <mat-button-toggle value="PARCELADO">Parcelado</mat-button-toggle>
              <mat-button-toggle value="RECORRENTE">Recorrente mensal</mat-button-toggle>
            </mat-button-toggle-group>
            @if (schedule() !== 'UNICO') {
              <mat-form-field class="count" subscriptSizing="dynamic">
                <mat-label>{{ schedule() === 'PARCELADO' ? 'Parcelas' : 'Meses' }}</mat-label>
                <input matInput type="number" min="2" max="60" formControlName="count" />
              </mat-form-field>
              <span class="muted small">
                @if (schedule() === 'PARCELADO') {
                  {{ count() }}× de {{ preview()[preview().length - 1] | cents }}{{ preview()[0] !== preview()[1] ? ' (1ª: ' + (preview()[0] | cents) + ')' : '' }}
                } @else {
                  {{ count() }} lançamentos mensais de {{ preview()[0] | cents }}
                }
              </span>
            }
          </div>
        }

        @if (data.scope === 'ESCRITORIO') {
          @if (isIncome) {
            <mat-form-field class="c6">
              <mat-label>Cliente</mat-label>
              <input matInput [formControl]="clientSearch" [matAutocomplete]="clientAuto" placeholder="Nome ou CPF/CNPJ" />
              <mat-icon matSuffix>person_search</mat-icon>
              <mat-autocomplete #clientAuto="matAutocomplete" [displayWith]="displayClient" (optionSelected)="clientId = $event.option.value.id">
                @for (c of clientOptions(); track c.id) { <mat-option [value]="c">{{ c.name }} <span class="muted">· {{ c.documentMasked }}</span></mat-option> }
              </mat-autocomplete>
            </mat-form-field>
          } @else {
            <mat-form-field class="c6">
              <mat-label>Fornecedor / favorecido</mat-label>
              <input matInput formControlName="counterparty" maxlength="200" />
            </mat-form-field>
          }
        } @else {
          <mat-form-field class="c6">
            <mat-label>{{ isIncome ? 'Pagador' : 'Favorecido' }}</mat-label>
            <input matInput formControlName="counterparty" maxlength="200" />
          </mat-form-field>
        }
        <mat-form-field class="c6">
          <mat-label>Nº do documento / nota fiscal</mat-label>
          <input matInput formControlName="documentNumber" maxlength="60" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Observações</mat-label>
          <textarea matInput rows="2" formControlName="notes" maxlength="2000"></textarea>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="txForm" [disabled]="saving()"><mat-icon>save</mat-icon>Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .schedule { grid-column: span 12; display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin: 0 0 18px; }
    .count { width: 110px; }
    .small { font-size: 13px; }
    .dialog-title .badge { margin-left: 8px; }
  `,
})
export class TransactionDialogComponent {
  readonly data = inject<TransactionDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<TransactionDialogComponent>);
  private readonly service = inject(FinanceService);
  private readonly clients = inject(ClientsService);

  readonly t = this.data.transaction ?? null;
  readonly isIncome = this.data.direction === 'RECEITA';
  readonly categories = this.isIncome ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
  readonly labels = CATEGORY_LABELS;
  readonly paymentLabels = PAYMENT_LABELS;
  readonly methods = Object.keys(PAYMENT_LABELS) as PaymentMethod[];
  readonly schedule = signal<'UNICO' | 'PARCELADO' | 'RECORRENTE'>('UNICO');
  readonly saving = signal(false);
  readonly clientOptions = signal<ClientSummary[]>([]);

  clientId: string | null = this.t?.clientId ?? this.data.client?.id ?? null;
  readonly clientSearch = new FormControl<{ id: string; name: string } | string>(
    this.clientId ? { id: this.clientId, name: this.t?.clientName ?? this.data.client?.name ?? '' } : '',
  );

  readonly form = inject(FormBuilder).nonNullable.group({
    category: [this.t?.category ?? ((this.isIncome ? 'HONORARIOS' : 'OPERACIONAL') as FinanceCategory), Validators.required],
    description: [this.t?.description ?? '', [Validators.required, Validators.maxLength(200)]],
    amount: [centsToInput(this.t?.amountCents), moneyValidator],
    dueDate: [this.t?.dueDate ?? localToday(), Validators.required],
    paymentMethod: [(this.t?.paymentMethod ?? null) as PaymentMethod | null],
    counterparty: [this.t?.counterparty ?? ''],
    documentNumber: [this.t?.documentNumber ?? ''],
    notes: [this.t?.notes ?? ''],
    count: [2, [Validators.min(2), Validators.max(60)]],
  });

  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), { initialValue: this.form.getRawValue() });
  readonly count = computed(() => Math.min(Math.max(Number(this.values().count) || 2, 2), 60));
  readonly preview = computed(() => {
    const cents = parseMoneyToCents(this.values().amount ?? '') ?? 0;
    return this.schedule() === 'PARCELADO' ? splitInstallments(cents, this.count()) : [cents];
  });

  constructor() {
    this.clientSearch.valueChanges
      .pipe(
        debounceTime(250),
        filter((v): v is string => typeof v === 'string'),
        switchMap((term) => {
          this.clientId = null;
          return term.trim().length >= 2
            ? this.clients.list({ search: term.trim(), status: 'ACTIVE' }, null, 8).pipe(catchError(() => of({ items: [], nextCursor: null })))
            : of({ items: [] as ClientSummary[], nextCursor: null });
        }),
        takeUntilDestroyed(),
      )
      .subscribe((page) => this.clientOptions.set(page.items));
  }

  displayClient = (v: { id: string; name: string } | string | null): string => (typeof v === 'string' ? v : v?.name ?? '');

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body: TransactionRequest = {
      direction: this.data.direction,
      category: v.category,
      description: v.description.trim(),
      amountCents: parseMoneyToCents(v.amount) ?? 0,
      dueDate: v.dueDate,
      paymentMethod: v.paymentMethod,
      clientId: this.data.scope === 'ESCRITORIO' && this.isIncome ? this.clientId : null,
      counterparty: v.counterparty.trim() || null,
      documentNumber: v.documentNumber.trim() || null,
      notes: v.notes.trim() || null,
      scope: this.data.scope,
      installments: !this.t && this.schedule() === 'PARCELADO' ? this.count() : 1,
      recurrenceMonths: !this.t && this.schedule() === 'RECORRENTE' ? this.count() : 1,
    };
    this.saving.set(true);
    const request: Observable<Transaction | Transaction[]> = this.t ? this.service.update(this.t.id, body) : this.service.create(body);
    request.subscribe({ next: (r: Transaction | Transaction[]) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= baixa
@Component({
  selector: 'app-pay-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule, CentsPipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>price_check</mat-icon>{{ t.direction === 'RECEITA' ? 'Registrar recebimento' : 'Registrar pagamento' }}</h2>
    <mat-dialog-content>
      <p class="info"><strong>{{ t.description }}</strong><br /><span class="muted">Valor previsto {{ t.amountCents | cents }} · vencimento {{ t.dueDate.split('-').reverse().join('/') }}</span></p>
      <form [formGroup]="form" class="form-grid" id="payForm" (ngSubmit)="save()">
        <mat-form-field class="c4"><mat-label>Data</mat-label><input matInput type="date" formControlName="paidDate" [max]="today" /></mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Valor efetivo (R$)</mat-label>
          <input matInput formControlName="amount" inputmode="decimal" />
          <mat-hint>Inclua juros, multa ou desconto</mat-hint>
          @if (form.controls.amount.invalid) { <mat-error>Valor inválido</mat-error> }
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Forma</mat-label>
          <mat-select formControlName="paymentMethod">
            @for (p of methods; track p) { <mat-option [value]="p">{{ paymentLabels[p] }}</mat-option> }
          </mat-select>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="payForm" [disabled]="saving()">Confirmar baixa</button>
    </mat-dialog-actions>
  `,
  styles: `.info { margin: 0 0 16px; line-height: 1.5; }`,
})
export class PayDialogComponent {
  readonly t = inject<Transaction>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<PayDialogComponent>);
  private readonly service = inject(FinanceService);
  readonly today = localToday();
  readonly paymentLabels = PAYMENT_LABELS;
  readonly methods = Object.keys(PAYMENT_LABELS) as PaymentMethod[];
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    paidDate: [localToday(), Validators.required],
    amount: [centsToInput(this.t.amountCents), moneyValidator],
    paymentMethod: [(this.t.paymentMethod ?? 'PIX') as PaymentMethod, Validators.required],
  });

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service
      .pay(this.t.id, { paidDate: v.paidDate, paidAmountCents: parseMoneyToCents(v.amount) ?? 0, paymentMethod: v.paymentMethod })
      .subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
