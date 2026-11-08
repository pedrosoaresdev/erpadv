import { Injectable, inject } from '@angular/core';
import { HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CursorPage } from '../../core/models/models';
import {
  BankAccount,
  CollectionChannel,
  CollectionDraft,
  CollectionOutcome,
  CollectionRecord,
  CostCenter,
  Statement,
  DelinquencyReport,
  FinanceCategory,
  FinanceDirection,
  FinanceScope,
  FinanceSummary,
  PaymentMethod,
  Transaction,
  TransactionRequest,
  TransactionView,
} from './finance.models';

export interface TransactionQuery {
  scope?: FinanceScope;
  direction?: FinanceDirection;
  view?: TransactionView;
  category?: FinanceCategory | '';
  clientId?: string;
  contractId?: string;
  costCenterId?: string;
  from?: string;
  to?: string;
}

@Injectable({ providedIn: 'root' })
export class FinanceService {
  private readonly api = inject(ApiService);

  list(q: TransactionQuery, cursor: string | null, size = 30): Observable<CursorPage<Transaction>> {
    return this.api.get('/finance/transactions', { ...q, cursor, size });
  }

  create(body: TransactionRequest): Observable<Transaction[]> {
    return this.api.post('/finance/transactions', body);
  }

  update(id: string, body: TransactionRequest): Observable<Transaction> {
    return this.api.put(`/finance/transactions/${encodeURIComponent(id)}`, body);
  }

  pay(id: string, body: { paidDate: string; paidAmountCents: number; paymentMethod: PaymentMethod; bankAccountId?: string | null }): Observable<Transaction> {
    return this.api.post(`/finance/transactions/${encodeURIComponent(id)}/pay`, body);
  }

  reopen(id: string): Observable<Transaction> {
    return this.api.post(`/finance/transactions/${encodeURIComponent(id)}/reopen`);
  }

  cancel(id: string, reason: string): Observable<Transaction> {
    return this.api.post(`/finance/transactions/${encodeURIComponent(id)}/cancel`, { reason });
  }

  uploadReceipt(id: string, file: File): Observable<Transaction> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.api.postForm(`/finance/transactions/${encodeURIComponent(id)}/receipt`, form);
  }

  downloadReceipt(id: string): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/finance/transactions/${encodeURIComponent(id)}/receipt`);
  }

  exportCsv(q: TransactionQuery): Observable<HttpResponse<Blob>> {
    return this.api.getBlob('/finance/transactions/export', { ...q });
  }

  summary(scope: FinanceScope, from: string, to: string): Observable<FinanceSummary> {
    return this.api.get('/finance/summary', { scope, from, to });
  }

  dashboard(from: string, to: string): Observable<FinanceSummary> {
    return this.api.get('/finance/dashboard', { from, to }, true);
  }

  delinquency(): Observable<DelinquencyReport> {
    return this.api.get('/finance/delinquency');
  }

  // ------------------------------------------------------------------ cadastros auxiliares

  costCenters(): Observable<CostCenter[]> {
    return this.api.get('/finance/cost-centers');
  }

  saveCostCenter(id: string | null, body: { name: string; code: string | null; description: string | null; active: boolean }): Observable<CostCenter> {
    return id ? this.api.put(`/finance/cost-centers/${encodeURIComponent(id)}`, body) : this.api.post('/finance/cost-centers', body);
  }

  deleteCostCenter(id: string): Observable<void> {
    return this.api.delete(`/finance/cost-centers/${encodeURIComponent(id)}`);
  }

  accounts(): Observable<BankAccount[]> {
    return this.api.get('/finance/bank-accounts');
  }

  saveAccount(id: string | null, body: Partial<BankAccount>): Observable<BankAccount> {
    return id ? this.api.put(`/finance/bank-accounts/${encodeURIComponent(id)}`, body) : this.api.post('/finance/bank-accounts', body);
  }

  deleteAccount(id: string): Observable<void> {
    return this.api.delete(`/finance/bank-accounts/${encodeURIComponent(id)}`);
  }

  statement(id: string, from: string, to: string): Observable<Statement> {
    return this.api.get(`/finance/bank-accounts/${encodeURIComponent(id)}/statement`, { from, to });
  }

  recibo(id: string): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/finance/transactions/${encodeURIComponent(id)}/recibo`);
  }

  collections(clientId?: string | null): Observable<CollectionRecord[]> {
    return this.api.get('/finance/collections', { clientId: clientId ?? null });
  }

  collectionDraft(clientId: string): Observable<CollectionDraft> {
    return this.api.get('/finance/collections/draft', { clientId });
  }

  registerCollection(body: { transactionIds: string[]; channel: CollectionChannel; outcome: CollectionOutcome; message: string | null; promisedDate: string | null }): Observable<CollectionRecord> {
    return this.api.post('/finance/collections', body);
  }
}

/** Salva um Blob recebido da API usando o nome do Content-Disposition. */
export function saveBlob(response: HttpResponse<Blob>, fallback: string): void {
  const disposition = response.headers.get('Content-Disposition') ?? '';
  const match = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(disposition);
  const name = match ? decodeURIComponent(match[1] ?? match[2]) : fallback;
  const url = URL.createObjectURL(response.body as Blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
