import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuditLog, CursorPage } from '../../core/models/models';

export type ContractStatus = 'RASCUNHO' | 'ATIVO' | 'ENCERRADO' | 'CANCELADO';
export type ContractType = 'HONORARIOS' | 'PARTIDO_MENSAL' | 'CONSULTORIA' | 'PRESTACAO_SERVICOS' | 'ADMINISTRATIVO' | 'PROCURACAO' | 'OUTRO';
export type FeeModel = 'FIXO' | 'PARCELADO' | 'ETAPAS' | 'EXITO' | 'MENSAL';

export const CONTRACT_TYPE_LABELS: Record<ContractType, string> = {
  HONORARIOS: 'Contrato de honorários advocatícios',
  PARTIDO_MENSAL: 'Prestação de serviços — partido mensal',
  CONSULTORIA: 'Consultoria jurídica',
  PRESTACAO_SERVICOS: 'Prestação de serviços jurídicos',
  ADMINISTRATIVO: 'Contrato administrativo (locação, fornecedores, parcerias)',
  PROCURACAO: 'Procuração',
  OUTRO: 'Outro',
};

export const FEE_MODEL_LABELS: Record<FeeModel, string> = {
  FIXO: 'Honorários fixos',
  PARCELADO: 'Honorários parcelados',
  ETAPAS: 'Honorários por etapas',
  EXITO: 'Somente êxito',
  MENSAL: 'Partido mensal',
};

export const CONTRACT_STATUS_LABELS: Record<ContractStatus, string> = {
  RASCUNHO: 'Rascunho',
  ATIVO: 'Ativo',
  ENCERRADO: 'Encerrado',
  CANCELADO: 'Cancelado',
};

export const CONTRACT_STATUS_TONE: Record<ContractStatus, string> = {
  RASCUNHO: 'warning', ATIVO: 'success', ENCERRADO: '', CANCELADO: 'danger',
};

export interface FeeStage {
  id: string;
  description: string;
  amountCents: number;
  dueDate: string | null;
  billed: boolean;
  transactionId: string | null;
}

export interface Contract {
  id: string;
  number: string;
  title: string;
  type: ContractType;
  typeLabel: string;
  status: ContractStatus;
  clientId: string;
  clientName: string;
  caseId: string | null;
  caseNumber: string | null;
  responsibleUid: string;
  responsibleName: string;
  object: string;
  startDate: string;
  endDate: string | null;
  daysToEnd: number | null;
  feeModel: FeeModel;
  feeModelLabel: string;
  amountCents: number | null;
  installments: number | null;
  firstDueDate: string | null;
  monthlyCents: number | null;
  months: number | null;
  stages: FeeStage[];
  successPercentBp: number | null;
  successBase: string | null;
  successBilled: boolean;
  templateId: string | null;
  body: string | null;
  bodyGeneratedAt: string | null;
  signedDocumentId: string | null;
  signedAt: string | null;
  activatedAt: string | null;
  cancelReason: string | null;
  createdAt: string;
  renewals: ContractRenewal[] | null;
}

export interface ContractRenewal {
  renewedOn: string;
  previousEndDate: string | null;
  newEndDate: string;
  monthsAdded: number | null;
  monthlyCents: number | null;
  note: string | null;
  byName: string;
}

export interface ContractRequest {
  title: string;
  type: ContractType;
  clientId: string;
  caseId?: string | null;
  responsibleUid: string;
  object: string;
  startDate: string;
  endDate?: string | null;
  feeModel: FeeModel;
  amountCents?: number | null;
  installments?: number | null;
  firstDueDate?: string | null;
  monthlyCents?: number | null;
  months?: number | null;
  stages?: { id?: string; description: string; amountCents: number; dueDate?: string | null }[];
  successPercentBp?: number | null;
  successBase?: string | null;
}

export interface ContractTemplate {
  id: string;
  name: string;
  type: ContractType;
  typeLabel: string;
  description: string | null;
  body: string;
  active: boolean;
  system: boolean;
  fields: string[];
}

export interface FieldInfo {
  key: string;
  description: string;
}

@Injectable({ providedIn: 'root' })
export class ContractsService {
  private readonly api = inject(ApiService);
  private fields$: Observable<FieldInfo[]> | null = null;

  list(q: { status?: ContractStatus | ''; clientId?: string; search?: string; expiring?: boolean }, cursor: string | null): Observable<CursorPage<Contract>> {
    return this.api.get('/contracts', { ...q, cursor, size: 30 });
  }

  get(id: string): Observable<Contract> {
    return this.api.get(`/contracts/${encodeURIComponent(id)}`);
  }

  create(body: ContractRequest): Observable<Contract> {
    return this.api.post('/contracts', body);
  }

  update(id: string, body: ContractRequest): Observable<Contract> {
    return this.api.put(`/contracts/${encodeURIComponent(id)}`, body);
  }

  action(id: string, action: 'activate' | 'close'): Observable<Contract> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/${action}`);
  }

  renew(id: string, body: { newEndDate: string; monthsAdded: number | null; monthlyCents: number | null; note: string | null }): Observable<Contract> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/renew`, body);
  }

  cancel(id: string, reason: string): Observable<Contract> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/cancel`, { reason });
  }

  billStage(id: string, stageId: string): Observable<Contract> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/stages/${encodeURIComponent(stageId)}/bill`);
  }

  success(id: string, obtainedCents: number, dueDate: string, note: string | null): Observable<Contract> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/success`, { obtainedCents, dueDate, note });
  }

  generate(id: string, templateId: string): Observable<{ body: string; missingFields: string[] }> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/generate`, { templateId });
  }

  saveBody(id: string, body: string): Observable<Contract> {
    return this.api.put(`/contracts/${encodeURIComponent(id)}/body`, { body });
  }

  export(id: string, format: 'pdf' | 'docx'): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/contracts/${encodeURIComponent(id)}/export`, { format });
  }

  exportToDocuments(id: string, format: 'pdf' | 'docx'): Observable<{ documentId: string }> {
    return this.api.post(`/contracts/${encodeURIComponent(id)}/export-to-documents?format=${format}`);
  }

  uploadSigned(id: string, file: File): Observable<Contract> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.api.postForm(`/contracts/${encodeURIComponent(id)}/signed`, form);
  }

  history(id: string): Observable<CursorPage<AuditLog>> {
    return this.api.get(`/contracts/${encodeURIComponent(id)}/history`);
  }

  templates(): Observable<ContractTemplate[]> {
    return this.api.get('/contracts/templates');
  }

  saveTemplate(id: string | null, body: { name: string; type: ContractType; description: string | null; body: string; active: boolean }): Observable<ContractTemplate> {
    return id ? this.api.put(`/contracts/templates/${encodeURIComponent(id)}`, body) : this.api.post('/contracts/templates', body);
  }

  deleteTemplate(id: string): Observable<void> {
    return this.api.delete(`/contracts/templates/${encodeURIComponent(id)}`);
  }

  fields(): Observable<FieldInfo[]> {
    this.fields$ ??= this.api.get<FieldInfo[]>('/contracts/fields').pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.fields$;
  }
}
