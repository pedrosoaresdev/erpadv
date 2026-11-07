import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CursorPage } from '../../core/models/models';

export type DocumentCategory =
  | 'PROCURACAO' | 'CONTRATO' | 'PETICAO' | 'RECURSO' | 'DECISAO' | 'SENTENCA' | 'DOCUMENTO_PESSOAL'
  | 'COMPROVANTE' | 'LAUDO' | 'INQUERITO' | 'SIGILOSO' | 'OUTRO';

export const DOCUMENT_CATEGORY_LABELS: Record<DocumentCategory, string> = {
  PROCURACAO: 'Procurações',
  CONTRATO: 'Contratos',
  PETICAO: 'Petições',
  RECURSO: 'Recursos',
  DECISAO: 'Decisões',
  SENTENCA: 'Sentenças',
  DOCUMENTO_PESSOAL: 'Documentos pessoais',
  COMPROVANTE: 'Comprovantes',
  LAUDO: 'Laudos',
  INQUERITO: 'Inquéritos',
  SIGILOSO: 'Documentos sigilosos',
  OUTRO: 'Outros',
};

export const ALWAYS_CONFIDENTIAL: DocumentCategory[] = ['INQUERITO', 'SIGILOSO'];
export const MAX_DOCUMENT_BYTES = 25 * 1024 * 1024;
export const ACCEPTED_TYPES = 'application/pdf,image/png,image/jpeg,application/vnd.openxmlformats-officedocument.wordprocessingml.document';

export interface StoredDocument {
  id: string;
  title: string;
  description: string | null;
  category: DocumentCategory;
  categoryLabel: string;
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  contractId: string | null;
  confidential: boolean;
  restricted: boolean;
  sharedWithClient: boolean;
  archived: boolean;
  currentVersion: number;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
  uploadedByName: string;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentMeta {
  title: string;
  description?: string | null;
  category: DocumentCategory;
  clientId?: string | null;
  caseId?: string | null;
  confidential: boolean;
  sharedWithClient: boolean;
}

export interface DocumentVersion {
  id: string;
  version: number;
  fileName: string;
  size: number;
  sha256: string;
  note: string | null;
  uploadedByName: string;
  createdAt: string;
}

export interface AccessLogEntry {
  id: string;
  version: number;
  action: 'UPLOAD' | 'NEW_VERSION' | 'VIEW' | 'DOWNLOAD' | 'UPDATE' | 'ARCHIVE' | 'UNARCHIVE';
  userName: string;
  ip: string | null;
  createdAt: string;
}

export interface DocumentQuery {
  search?: string;
  category?: DocumentCategory | '';
  clientId?: string;
  caseId?: string;
  includeArchived?: boolean;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly api = inject(ApiService);

  list(q: DocumentQuery, cursor: string | null, size = 30): Observable<CursorPage<StoredDocument>> {
    return this.api.get('/documents', { ...q, cursor, size });
  }

  get(id: string): Observable<StoredDocument> {
    return this.api.get(`/documents/${encodeURIComponent(id)}`);
  }

  upload(meta: DocumentMeta, file: File): Observable<StoredDocument> {
    const form = new FormData();
    form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
    form.append('file', file, file.name);
    return this.api.postForm('/documents', form);
  }

  newVersion(id: string, file: File, note: string | null): Observable<StoredDocument> {
    const form = new FormData();
    form.append('file', file, file.name);
    const query = note ? `?note=${encodeURIComponent(note)}` : '';
    return this.api.postForm(`/documents/${encodeURIComponent(id)}/versions${query}`, form);
  }

  update(id: string, meta: DocumentMeta): Observable<StoredDocument> {
    return this.api.put(`/documents/${encodeURIComponent(id)}`, meta);
  }

  setArchived(id: string, archived: boolean): Observable<StoredDocument> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/${archived ? 'archive' : 'unarchive'}`);
  }

  versions(id: string): Observable<DocumentVersion[]> {
    return this.api.get(`/documents/${encodeURIComponent(id)}/versions`);
  }

  accessLog(id: string): Observable<CursorPage<AccessLogEntry>> {
    return this.api.get(`/documents/${encodeURIComponent(id)}/access-log`, { size: 50 });
  }

  content(id: string, download: boolean, version?: number): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/documents/${encodeURIComponent(id)}/content`, { download, version });
  }
}
