import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  CaseMovement,
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

  addMovement(id: string, body: { type: MovementType; date: string; title: string; description: string | null }): Observable<CaseMovement> {
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
