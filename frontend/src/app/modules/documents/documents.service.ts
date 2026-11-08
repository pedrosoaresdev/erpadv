import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
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
/** Limite padrão; o valor efetivo vem de GET /documents/settings (DOCUMENTS_MAX_MB no servidor). */
export const MAX_DOCUMENT_BYTES = 25 * 1024 * 1024;
export const ACCEPTED_EXTENSIONS = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'jpg', 'jpeg', 'png', 'txt'];
export const ACCEPTED_TYPES = ACCEPTED_EXTENSIONS.map((e) => '.' + e).join(',');

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
  folderId: string | null;
  tags: string[];
  favorite: boolean;
  deleted: boolean;
  deletedAt: string | null;
  deletedByName: string | null;
}

export interface DocumentFolder {
  id: string;
  name: string;
  parentId: string | null;
  color: string | null;
  createdAt: string;
}

/** Pasta com profundidade, para árvore e seletores. */
export interface FolderNode extends DocumentFolder {
  depth: number;
  path: string;
}

export interface DocumentSettings {
  maxBytes: number;
  extensions: string[];
}

export interface DocumentMeta {
  title: string;
  description?: string | null;
  category: DocumentCategory;
  clientId?: string | null;
  caseId?: string | null;
  confidential: boolean;
  sharedWithClient: boolean;
  folderId?: string | null;
  tags?: string[] | null;
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
  action: 'UPLOAD' | 'NEW_VERSION' | 'VIEW' | 'DOWNLOAD' | 'UPDATE' | 'ARCHIVE' | 'UNARCHIVE' | 'MOVE' | 'TRASH' | 'RESTORE' | 'SHARE';
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
  folderId?: string;
  favorites?: boolean;
  trash?: boolean;
  tag?: string;
}

/** Ordena as pastas em árvore (pré-ordem), com caminho legível. */
export function folderTree(folders: DocumentFolder[]): FolderNode[] {
  const byParent = new Map<string | null, DocumentFolder[]>();
  for (const f of folders) {
    const key = f.parentId && folders.some((p) => p.id === f.parentId) ? f.parentId : null;
    byParent.set(key, [...(byParent.get(key) ?? []), f]);
  }
  const out: FolderNode[] = [];
  const walk = (parent: string | null, depth: number, prefix: string) => {
    for (const f of (byParent.get(parent) ?? []).sort((a, b) => a.name.localeCompare(b.name, 'pt-BR'))) {
      const path = prefix ? `${prefix} / ${f.name}` : f.name;
      out.push({ ...f, depth, path });
      if (depth < 20) walk(f.id, depth + 1, path);
    }
  };
  walk(null, 0, '');
  return out;
}

export function fileExtension(name: string): string {
  const i = name.lastIndexOf('.');
  return i < 0 ? '' : name.slice(i + 1).toLowerCase();
}

/** SHA-256 em hexadecimal (Web Crypto); null quando indisponível (contexto não seguro). */
export async function sha256Hex(file: Blob): Promise<string | null> {
  if (!globalThis.crypto?.subtle) return null;
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
}

@Injectable({ providedIn: 'root' })
export class DocumentsService {
  private readonly api = inject(ApiService);
  private settings$?: Observable<DocumentSettings>;

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

  settings(): Observable<DocumentSettings> {
    this.settings$ ??= this.api.get<DocumentSettings>('/documents/settings').pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.settings$;
  }

  duplicates(sha256: string): Observable<StoredDocument[]> {
    return this.api.get('/documents/duplicates', { sha256 }, true);
  }

  folders(): Observable<DocumentFolder[]> {
    return this.api.get('/documents/folders');
  }

  saveFolder(id: string | null, body: { name: string; parentId: string | null; color: string | null }): Observable<DocumentFolder> {
    return id ? this.api.put(`/documents/folders/${encodeURIComponent(id)}`, body) : this.api.post('/documents/folders', body);
  }

  deleteFolder(id: string): Observable<void> {
    return this.api.delete(`/documents/folders/${encodeURIComponent(id)}`);
  }

  move(id: string, folderId: string | null): Observable<StoredDocument> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/move`, { folderId });
  }

  copy(id: string, folderId: string | null): Observable<StoredDocument> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/copy`, { folderId });
  }

  favorite(id: string, on: boolean): Observable<StoredDocument> {
    const url = `/documents/${encodeURIComponent(id)}/favorite`;
    return on ? this.api.post(url) : this.api.delete(url);
  }

  trash(id: string): Observable<StoredDocument> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/trash`);
  }

  restore(id: string): Observable<StoredDocument> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/restore`);
  }

  purge(id: string, reason: string): Observable<void> {
    return this.api.delete(`/documents/${encodeURIComponent(id)}?reason=${encodeURIComponent(reason)}`);
  }

  share(id: string, uids: string[], message: string | null): Observable<void> {
    return this.api.post(`/documents/${encodeURIComponent(id)}/share`, { uids, message });
  }
}
