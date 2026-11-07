import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  AuditLog,
  CaseMovement,
  CaseRelated,
  CaseRequest,
  CaseStatus,
  CursorPage,
  EmployeeOption,
  LegalArea,
  LegalCase,
  MovementType,
} from '../../core/models/models';

export interface CaseFilter {
  search?: string;
  status?: CaseStatus | '';
  area?: LegalArea | '';
  responsibleUid?: string;
  court?: string;
  includeArchived?: boolean;
}

@Injectable({ providedIn: 'root' })
export class CasesService {
  private readonly api = inject(ApiService);
  private lawyers$: Observable<EmployeeOption[]> | null = null;

  list(filter: CaseFilter, cursor: string | null, size = 20): Observable<CursorPage<LegalCase>> {
    return this.api.get('/cases', { ...filter, cursor, size });
  }

  get(id: string): Observable<LegalCase> {
    return this.api.get(`/cases/${encodeURIComponent(id)}`);
  }

  create(body: CaseRequest): Observable<LegalCase> {
    return this.api.post('/cases', body);
  }

  update(id: string, body: CaseRequest): Observable<LegalCase> {
    return this.api.put(`/cases/${encodeURIComponent(id)}`, body);
  }

  archive(id: string): Observable<LegalCase> {
    return this.api.post(`/cases/${encodeURIComponent(id)}/archive`);
  }

  unarchive(id: string): Observable<LegalCase> {
    return this.api.post(`/cases/${encodeURIComponent(id)}/unarchive`);
  }

  movements(id: string, cursor: string | null): Observable<CursorPage<CaseMovement>> {
    return this.api.get(`/cases/${encodeURIComponent(id)}/movements`, { cursor, size: 30 });
  }

  related(id: string): Observable<CaseRelated> {
    return this.api.get(`/cases/${encodeURIComponent(id)}/related`);
  }

  history(id: string, cursor: string | null): Observable<CursorPage<AuditLog>> {
    return this.api.get(`/cases/${encodeURIComponent(id)}/history`, { cursor, size: 30 });
  }

  links(id: string): Observable<Record<string, number>> {
    return this.api.get(`/cases/${encodeURIComponent(id)}/links`);
  }

  duplicate(id: string): Observable<LegalCase> {
    return this.api.post(`/cases/${encodeURIComponent(id)}/duplicate`);
  }

  delete(id: string, reason: string): Observable<void> {
    return this.api.delete(`/cases/${encodeURIComponent(id)}?reason=${encodeURIComponent(reason)}`);
  }

  report(id: string, format: 'pdf' | 'xlsx') {
    return this.api.getBlob(`/cases/${encodeURIComponent(id)}/report`, { format });
  }

  export(filter: CaseFilter, ids: string[], format: 'pdf' | 'xlsx') {
    return this.api.getBlob('/cases/export', { ...filter, ids: ids.length ? ids.join(',') : undefined, format });
  }

  addMovement(id: string, body: { type: MovementType; date: string; title: string; description: string | null; important?: boolean }): Observable<CaseMovement> {
    return this.api.post(`/cases/${encodeURIComponent(id)}/movements`, body);
  }

  /** Advogados ativos (cache por sessão de página). */
  lawyers(): Observable<EmployeeOption[]> {
    this.lawyers$ ??= this.api.get<EmployeeOption[]>('/employees/lawyers').pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.lawyers$;
  }

  invalidateLawyers(): void {
    this.lawyers$ = null;
  }
}
