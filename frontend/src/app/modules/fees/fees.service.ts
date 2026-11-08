import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { FinanceCategory } from '../finance/finance.models';

export type FeeGroupBy = 'CLIENT' | 'CASE' | 'CONTRACT' | 'CATEGORY';

export interface FeeTotals {
  contractedCents: number;
  receivedCents: number;
  pendingCents: number;
  overdueCents: number;
  titles: number;
}

export interface FeeGroup {
  key: string;
  label: string;
  detail: string | null;
  totals: FeeTotals;
  successPercentBp: number | null;
  feeModel: string | null;
}

export interface FeeSummary {
  from: string;
  to: string;
  totals: FeeTotals;
  groups: FeeGroup[];
  byCategory: FeeGroup[];
}

export interface FeeItem {
  id: string;
  category: FinanceCategory;
  categoryLabel: string;
  description: string;
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  contractId: string | null;
  dueDate: string;
  amountCents: number;
  situation: 'A_VENCER' | 'VENCE_HOJE' | 'ATRASADO' | 'PAGO' | 'CANCELADO';
  paidDate: string | null;
  paidAmountCents: number | null;
  paymentMethod: string | null;
  installmentNumber: number | null;
  installmentCount: number | null;
  receiptNumber: string | null;
}

export interface CommissionRule {
  id: string;
  beneficiaryUid: string;
  beneficiaryName: string;
  description: string | null;
  percentBp: number;
  categories: FinanceCategory[];
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  responsibleOnly: boolean;
  active: boolean;
}

export interface CommissionLine {
  receiptId: string;
  paidDate: string;
  description: string;
  clientName: string | null;
  caseNumber: string | null;
  baseCents: number;
  percentBp: number;
  commissionCents: number;
  ruleId: string;
  paidOut: boolean;
}

export interface CommissionStatement {
  beneficiaryUid: string;
  beneficiaryName: string;
  baseCents: number;
  commissionCents: number;
  pendingCents: number;
  paidOutCents: number;
  lines: CommissionLine[];
}

export interface Payout {
  id: string;
  beneficiaryUid: string;
  beneficiaryName: string;
  periodFrom: string;
  periodTo: string;
  amountCents: number;
  receipts: number;
  expenseId: string;
  createdByName: string;
  createdAt: string;
}

export const FEE_CATEGORIES: FinanceCategory[] = ['HONORARIOS', 'HONORARIOS_EXITO', 'HONORARIOS_SUCUMBENCIA', 'CONTRATO'];

export function percent(bp: number | null | undefined): string {
  if (bp == null) return '—';
  return (bp % 100 === 0 ? String(bp / 100) : (bp / 100).toFixed(2).replace('.', ',')) + '%';
}

@Injectable({ providedIn: 'root' })
export class FeesService {
  private readonly api = inject(ApiService);

  summary(from: string, to: string, groupBy: FeeGroupBy): Observable<FeeSummary> {
    return this.api.get('/fees/summary', { from, to, groupBy });
  }

  items(q: { clientId?: string | null; caseId?: string | null; contractId?: string | null }): Observable<FeeItem[]> {
    return this.api.get('/fees/items', { clientId: q.clientId ?? null, caseId: q.caseId ?? null, contractId: q.contractId ?? null });
  }

  rules(): Observable<CommissionRule[]> {
    return this.api.get('/fees/commission-rules');
  }

  saveRule(id: string | null, body: Omit<CommissionRule, 'id' | 'beneficiaryName' | 'clientName' | 'caseNumber'>): Observable<CommissionRule> {
    return id ? this.api.put(`/fees/commission-rules/${encodeURIComponent(id)}`, body) : this.api.post('/fees/commission-rules', body);
  }

  deleteRule(id: string): Observable<void> {
    return this.api.delete(`/fees/commission-rules/${encodeURIComponent(id)}`);
  }

  commissions(from: string, to: string): Observable<CommissionStatement[]> {
    return this.api.get('/fees/commissions', { from, to });
  }

  mine(from: string, to: string): Observable<CommissionStatement[]> {
    return this.api.get('/fees/commissions/mine', { from, to });
  }

  payouts(): Observable<Payout[]> {
    return this.api.get('/fees/commissions/payouts');
  }

  payout(body: { beneficiaryUid: string; from: string; to: string; dueDate: string }): Observable<Payout> {
    return this.api.post('/fees/commissions/payouts', body);
  }
}
