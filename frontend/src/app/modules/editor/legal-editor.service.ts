import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { StoredDocument } from '../documents/documents.service';

export type LegalDocKind =
  | 'PROCURACAO' | 'CONTRATO_HONORARIOS' | 'PETICAO_INICIAL' | 'CONTESTACAO' | 'REPLICA' | 'RECURSO' | 'CONTRARRAZOES'
  | 'DECLARACAO_HIPOSSUFICIENCIA' | 'SUBSTABELECIMENTO' | 'NOTIFICACAO_EXTRAJUDICIAL' | 'REQUERIMENTO' | 'PARECER'
  | 'RELATORIO_PROCESSUAL' | 'OUTRO';

export const KIND_LABELS: Record<LegalDocKind, string> = {
  PROCURACAO: 'Procuração',
  CONTRATO_HONORARIOS: 'Contrato de honorários',
  PETICAO_INICIAL: 'Petição inicial',
  CONTESTACAO: 'Contestação',
  REPLICA: 'Réplica',
  RECURSO: 'Recurso',
  CONTRARRAZOES: 'Contrarrazões',
  DECLARACAO_HIPOSSUFICIENCIA: 'Declaração de hipossuficiência',
  SUBSTABELECIMENTO: 'Substabelecimento',
  NOTIFICACAO_EXTRAJUDICIAL: 'Notificação extrajudicial',
  REQUERIMENTO: 'Requerimento',
  PARECER: 'Parecer',
  RELATORIO_PROCESSUAL: 'Relatório processual',
  OUTRO: 'Outro documento',
};

export type DraftStatus = 'RASCUNHO' | 'FINAL';

export interface LegalTemplate {
  id: string;
  name: string;
  kind: LegalDocKind;
  kindLabel: string;
  description: string | null;
  system: boolean;
  content: string;
  updatedAt: string | null;
  createdByName: string | null;
}

export interface Letterhead {
  id: string;
  name: string;
  hasPdf: boolean;
  marginTop: number;
  marginBottom: number;
  marginLeft: number;
  marginRight: number;
  isDefault: boolean;
  createdByName: string | null;
  createdAt: string;
}

export interface LetterheadMeta {
  name: string;
  marginTop: number;
  marginBottom: number;
  marginLeft: number;
  marginRight: number;
  isDefault: boolean;
}

export interface FieldInfo {
  key: string;
  label: string;
  group: string;
}

export interface FieldsResponse {
  catalog: FieldInfo[];
  values: Record<string, string | null>;
}

export interface DraftSummary {
  id: string;
  title: string;
  kind: LegalDocKind;
  kindLabel: string;
  status: DraftStatus;
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  version: number;
  lawyerName: string | null;
  updatedByName: string | null;
  updatedAt: string;
  createdAt: string;
  storedDocumentId: string | null;
}

export interface Draft extends DraftSummary {
  templateId: string | null;
  templateName: string | null;
  lawyerUid: string | null;
  content: string;
  header: string | null;
  footer: string | null;
  pageNumbers: boolean;
  letterheadId: string | null;
  finalizedAt: string | null;
  finalizedByName: string | null;
  createdByName: string | null;
  missingFields: string[];
}

export interface DraftUpdate {
  title: string;
  kind: LegalDocKind;
  content: string;
  header: string | null;
  footer: string | null;
  pageNumbers: boolean;
  clientId: string | null;
  caseId: string | null;
  /** '' = sem papel timbrado. */
  letterheadId: string;
}

export interface VersionSummary {
  id: string;
  version: number;
  title: string;
  status: DraftStatus;
  note: string | null;
  createdByName: string;
  createdAt: string;
  words: number;
}

export interface VersionDetail {
  id: string;
  version: number;
  title: string;
  content: string;
  header: string | null;
  footer: string | null;
  pageNumbers: boolean;
  status: DraftStatus;
  note: string | null;
  createdByName: string;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class LegalEditorService {
  private readonly api = inject(ApiService);
  private readonly base = '/legal-editor';
  private readonly imageCache = new Map<string, Observable<string>>();

  templates(): Observable<LegalTemplate[]> {
    return this.api.get(`${this.base}/templates`);
  }

  saveTemplate(id: string | null, body: { name: string; kind: LegalDocKind; description: string | null; content: string }): Observable<LegalTemplate> {
    return id ? this.api.put(`${this.base}/templates/${encodeURIComponent(id)}`, body) : this.api.post(`${this.base}/templates`, body);
  }

  deleteTemplate(id: string): Observable<void> {
    return this.api.delete(`${this.base}/templates/${encodeURIComponent(id)}`);
  }

  letterheads(): Observable<Letterhead[]> {
    return this.api.get(`${this.base}/letterheads`);
  }

  createLetterhead(meta: LetterheadMeta, image: Blob, imageName: string, pdf: File | null): Observable<Letterhead> {
    const form = new FormData();
    form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
    form.append('image', image, imageName);
    if (pdf) form.append('pdf', pdf, pdf.name);
    return this.api.postForm(`${this.base}/letterheads`, form);
  }

  updateLetterhead(id: string, meta: LetterheadMeta): Observable<Letterhead> {
    return this.api.put(`${this.base}/letterheads/${encodeURIComponent(id)}`, meta);
  }

  deleteLetterhead(id: string): Observable<void> {
    this.imageCache.delete(id);
    return this.api.delete(`${this.base}/letterheads/${encodeURIComponent(id)}`);
  }

  /** URL local (blob) da imagem do papel timbrado, em cache durante a sessão. */
  letterheadImage(id: string): Observable<string> {
    let cached = this.imageCache.get(id);
    if (!cached) {
      cached = this.api.getBlob(`${this.base}/letterheads/${encodeURIComponent(id)}/image`)
        .pipe(map((r) => URL.createObjectURL(r.body as Blob)), shareReplay({ bufferSize: 1, refCount: false }));
      this.imageCache.set(id, cached);
    }
    return cached;
  }

  fields(clientId: string | null, caseId: string | null, lawyerUid?: string | null): Observable<FieldsResponse> {
    return this.api.get(`${this.base}/fields`, { clientId, caseId, lawyerUid: lawyerUid ?? null });
  }

  list(filter: { status?: DraftStatus | ''; search?: string; clientId?: string | null; caseId?: string | null }): Observable<DraftSummary[]> {
    return this.api.get(`${this.base}/documents`, {
      status: filter.status || null, search: filter.search || null, clientId: filter.clientId ?? null, caseId: filter.caseId ?? null,
    });
  }

  get(id: string): Observable<Draft> {
    return this.api.get(`${this.base}/documents/${encodeURIComponent(id)}`);
  }

  create(body: { title: string | null; kind: LegalDocKind | null; templateId: string; clientId: string | null; caseId: string | null; lawyerUid: string | null; letterheadId?: string | null }): Observable<Draft> {
    return this.api.post(`${this.base}/documents`, body);
  }

  update(id: string, body: DraftUpdate): Observable<Draft> {
    return this.api.put(`${this.base}/documents/${encodeURIComponent(id)}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`${this.base}/documents/${encodeURIComponent(id)}`);
  }

  duplicate(id: string): Observable<Draft> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/duplicate`);
  }

  saveVersion(id: string, note: string | null): Observable<Draft> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/versions`, { note });
  }

  versions(id: string): Observable<VersionSummary[]> {
    return this.api.get(`${this.base}/documents/${encodeURIComponent(id)}/versions`);
  }

  version(id: string, version: number): Observable<VersionDetail> {
    return this.api.get(`${this.base}/documents/${encodeURIComponent(id)}/versions/${version}`);
  }

  restore(id: string, version: number): Observable<Draft> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/versions/${version}/restore`);
  }

  finalize(id: string): Observable<Draft> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/finalize`);
  }

  reopen(id: string): Observable<Draft> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/reopen`);
  }

  export(id: string, format: 'pdf' | 'docx'): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`${this.base}/documents/${encodeURIComponent(id)}/export`, { format });
  }

  saveToCentral(id: string, format: 'pdf' | 'docx'): Observable<StoredDocument> {
    return this.api.post(`${this.base}/documents/${encodeURIComponent(id)}/save-to-central`, { format });
  }
}

// ------------------------------------------------------------------ HTML

const ALLOWED = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'STRONG', 'B', 'EM', 'I', 'U', 'S', 'STRIKE', 'BR', 'UL', 'OL', 'LI', 'BLOCKQUOTE',
  'TABLE', 'THEAD', 'TBODY', 'TR', 'TD', 'TH', 'HR', 'SPAN']);
const DROP_WITH_CONTENT = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'TEMPLATE', 'NOSCRIPT', 'SVG', 'MATH', 'HEAD', 'TITLE', 'META', 'LINK']);

/**
 * Saneamento no navegador (espelha o do servidor, que é a garantia definitiva): mantém só as tags do editor,
 * {@code text-align}/{@code margin-left} e o marcador de campos pendentes.
 */
export function sanitizeEditorHtml(html: string): string {
  const doc = new DOMParser().parseFromString(`<body>${html ?? ''}</body>`, 'text/html');
  const clean = (node: Node, target: Node, out: Document) => {
    node.childNodes.forEach((child) => {
      if (child.nodeType === Node.TEXT_NODE) {
        target.appendChild(out.createTextNode(child.textContent ?? ''));
        return;
      }
      if (child.nodeType !== Node.ELEMENT_NODE) return;
      const el = child as HTMLElement;
      const tag = el.tagName.toUpperCase();
      if (DROP_WITH_CONTENT.has(tag)) return;
      if (!ALLOWED.has(tag)) {
        // desembrulha (mantém o texto de tags não permitidas, ex.: <font>, <a>)
        clean(el, target, out);
        return;
      }
      const copy = out.createElement(tag.toLowerCase());
      const align = /text-align\s*:\s*(left|center|right|justify)/i.exec(el.getAttribute('style') ?? '')?.[1]
        ?? (['left', 'center', 'right', 'justify'].includes((el.getAttribute('align') ?? '').toLowerCase()) ? el.getAttribute('align')!.toLowerCase() : null);
      const margin = /margin-left\s*:\s*(\d{1,3})px/i.exec(el.getAttribute('style') ?? '')?.[1];
      const styles: string[] = [];
      if (align && tag !== 'SPAN') styles.push(`text-align: ${align.toLowerCase()};`);
      if (margin && Number(margin) <= 400 && tag !== 'SPAN') styles.push(`margin-left: ${margin}px;`);
      if (styles.length) copy.setAttribute('style', styles.join(' '));
      if (tag === 'HR' && el.classList.contains('page-break')) copy.setAttribute('class', 'page-break');
      if (tag === 'SPAN' && el.classList.contains('field')) copy.setAttribute('class', 'field');
      if ((tag === 'TD' || tag === 'TH') && /^\d{1,2}$/.test(el.getAttribute('colspan') ?? '')) copy.setAttribute('colspan', el.getAttribute('colspan')!);
      target.appendChild(copy);
      clean(el, copy, out);
    });
  };
  const out = document.implementation.createHTMLDocument('');
  const root = out.createElement('div');
  clean(doc.body, root, out);
  return root.innerHTML;
}

export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
}

export function plainText(html: string): string {
  const doc = new DOMParser().parseFromString(`<body>${html ?? ''}</body>`, 'text/html');
  return doc.body.textContent ?? '';
}
