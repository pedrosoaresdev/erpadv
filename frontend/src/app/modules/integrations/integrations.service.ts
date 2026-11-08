import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';

export type Provider = 'DATAJUD' | 'DJEN' | 'PJE' | 'EPROC' | 'PROJUDI' | 'ESAJ' | 'SMTP' | 'WHATSAPP' | 'GOOGLE_CALENDAR' | 'ZAPSIGN';

export interface Connector {
  provider: Provider;
  label: string;
  category: 'JUDICIAL' | 'COMUNICACAO' | 'AGENDA' | 'ASSINATURA';
  availability: 'API_PUBLICA' | 'CREDENCIAL' | 'SEM_API';
  description: string;
  enabled: boolean;
  autoSync: boolean;
  state: 'SEM_API' | 'NAO_CONFIGURADO' | 'DESATIVADO' | 'ERRO' | 'ATIVO';
  stateLabel: string;
  config: Record<string, string>;
  configuredSecrets: string[];
  secretKeys: string[];
  lastRunAt: string | null;
  lastSuccessAt: string | null;
  lastError: string | null;
  lastErrorAt: string | null;
  webhookPath: string | null;
}

export interface IntegrationRun {
  id: string;
  provider: Provider;
  trigger: string;
  status: 'EXECUTANDO' | 'SUCESSO' | 'PARCIAL' | 'ERRO';
  startedAt: string;
  finishedAt: string | null;
  processed: number;
  created: number;
  updated: number;
  errors: number;
  message: string | null;
  details: string[];
  triggeredByName: string | null;
}

export type PublicationStatus = 'NOVA' | 'LIDA' | 'PRAZO_CRIADO' | 'ARQUIVADA';

export interface Publication {
  id: string;
  externalId: number;
  availableDate: string;
  court: string | null;
  kind: string | null;
  documentType: string | null;
  organ: string | null;
  className: string | null;
  text: string;
  caseNumber: string | null;
  link: string | null;
  oab: string;
  lawyerUid: string | null;
  lawyerName: string | null;
  caseId: string | null;
  clientName: string | null;
  status: PublicationStatus;
  deadlineId: string | null;
  readAt: string | null;
  readByName: string | null;
}

export interface ImportResult {
  total: number;
  created: number;
  duplicates: number;
  errors: number;
  rows: { line: number; cnj: string | null; status: 'CRIADO' | 'DUPLICADO' | 'ERRO'; message: string; caseId: string | null }[];
}

export interface GoogleStatus {
  available: boolean;
  connected: boolean;
  email: string | null;
  connectedAt: string | null;
}

export interface Signer {
  name: string;
  email: string;
  phone?: string | null;
  status?: string;
  signUrl?: string | null;
  signedAt?: string | null;
}

export interface SignatureRequest {
  id: string;
  documentId: string;
  documentTitle: string;
  status: 'ENVIADO' | 'ASSINADO' | 'RECUSADO' | 'CANCELADO';
  signers: Signer[];
  signedStored: boolean;
  signedVersion: number | null;
  createdByName: string;
  createdAt: string;
  completedAt: string | null;
  sandbox: boolean;
}

@Injectable({ providedIn: 'root' })
export class IntegrationsService {
  private readonly api = inject(ApiService);

  catalog(): Observable<Connector[]> {
    return this.api.get('/integrations');
  }

  save(provider: Provider, body: { enabled: boolean; autoSync: boolean; config: Record<string, string>; secrets: Record<string, string> }): Observable<Connector> {
    return this.api.put(`/integrations/${provider}`, body);
  }

  runs(provider?: Provider | null): Observable<IntegrationRun[]> {
    return this.api.get('/integrations/runs', { provider: provider ?? null });
  }

  syncDataJud(): Observable<IntegrationRun> {
    return this.api.post('/integrations/datajud/sync');
  }

  syncDjen(from: string, to: string): Observable<IntegrationRun> {
    return this.api.post(`/integrations/djen/sync?from=${from}&to=${to}`);
  }

  importTemplate(): Observable<HttpResponse<Blob>> {
    return this.api.getBlob('/integrations/import/cases/template');
  }

  importCases(file: File): Observable<ImportResult> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.api.postForm('/integrations/import/cases', form);
  }

  publications(q: { status?: PublicationStatus | ''; lawyerUid?: string; from?: string; to?: string; caseId?: string }): Observable<Publication[]> {
    return this.api.get('/publications', { status: q.status || null, lawyerUid: q.lawyerUid || null, from: q.from || null, to: q.to || null, caseId: q.caseId || null });
  }

  setPublicationStatus(id: string, status: PublicationStatus): Observable<Publication> {
    return this.api.post(`/publications/${encodeURIComponent(id)}/status`, { status });
  }

  linkDeadline(id: string, deadlineId: string): Observable<Publication> {
    return this.api.post(`/publications/${encodeURIComponent(id)}/deadline`, { id: deadlineId });
  }

  linkCase(id: string, caseId: string): Observable<Publication> {
    return this.api.post(`/publications/${encodeURIComponent(id)}/case`, { id: caseId });
  }

  googleStatus(): Observable<GoogleStatus> {
    return this.api.get('/integrations/google/me', undefined, true);
  }

  googleConnect(): Observable<{ url: string }> {
    return this.api.post('/integrations/google/connect');
  }

  googleDisconnect(): Observable<void> {
    return this.api.delete('/integrations/google/me');
  }

  signatures(documentId?: string | null): Observable<SignatureRequest[]> {
    return this.api.get('/integrations/signatures', { documentId: documentId ?? null });
  }

  sendSignature(documentId: string, signers: Signer[]): Observable<SignatureRequest> {
    return this.api.post('/integrations/signatures', { documentId, signers });
  }

  refreshSignature(id: string): Observable<SignatureRequest> {
    return this.api.post(`/integrations/signatures/${encodeURIComponent(id)}/refresh`);
  }
}
