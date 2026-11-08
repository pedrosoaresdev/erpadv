import { Injectable, inject } from '@angular/core';
import { Observable, shareReplay } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  AuditLog,
  CountingMode,
  CourtCalendar,
  CourtCalendarEntry,
  CursorPage,
  Deadline,
  DeadlineCalculation,
  DeadlineRequest,
  DeadlineStats,
  DeadlineView,
  EmployeeOption,
} from '../../core/models/models';

@Injectable({ providedIn: 'root' })
export class DeadlinesService {
  private readonly api = inject(ApiService);
  private staff$: Observable<EmployeeOption[]> | null = null;

  publicationDate(availability: string, court: string | null): Observable<{ availabilityDate: string; publicationDate: string; suggestedStartDate: string; notes: string[] }> {
    return this.api.get('/deadlines/publication-date', { availability, court }, true);
  }

  history(id: string) {
    return this.api.get<CursorPage<AuditLog>>(`/deadlines/${encodeURIComponent(id)}/history`);
  }

  uploadReceipt(id: string, file: File): Observable<Deadline> {
    const form = new FormData();
    form.append('file', file);
    return this.api.postForm(`/deadlines/${encodeURIComponent(id)}/receipt`, form);
  }

  receipt(id: string) {
    return this.api.getBlob(`/deadlines/${encodeURIComponent(id)}/receipt`);
  }

  report(q: { from: string; to: string; responsibleUid?: string; format: 'pdf' | 'xlsx' }) {
    return this.api.getBlob('/deadlines/report', q);
  }

  list(view: DeadlineView, filter: { responsibleUid?: string; caseId?: string; clientId?: string }, cursor: string | null, size = 30): Observable<CursorPage<Deadline>> {
    return this.api.get('/deadlines', { view, ...filter, cursor, size });
  }

  stats(responsibleUid?: string): Observable<DeadlineStats> {
    return this.api.get('/deadlines/stats', { responsibleUid });
  }

  get(id: string): Observable<Deadline> {
    return this.api.get(`/deadlines/${encodeURIComponent(id)}`);
  }

  create(body: DeadlineRequest): Observable<Deadline> {
    return this.api.post('/deadlines', body);
  }

  update(id: string, body: DeadlineRequest): Observable<Deadline> {
    return this.api.put(`/deadlines/${encodeURIComponent(id)}`, body);
  }

  validate(id: string): Observable<Deadline> {
    return this.api.post(`/deadlines/${encodeURIComponent(id)}/validate`);
  }

  complete(id: string, note: string | null): Observable<Deadline> {
    return this.api.post(`/deadlines/${encodeURIComponent(id)}/complete`, { note });
  }

  cancel(id: string, note: string): Observable<Deadline> {
    return this.api.post(`/deadlines/${encodeURIComponent(id)}/cancel`, { note });
  }

  reopen(id: string): Observable<Deadline> {
    return this.api.post(`/deadlines/${encodeURIComponent(id)}/reopen`);
  }

  calculate(body: { startDate: string; days: number; countingMode: CountingMode; court?: string | null }): Observable<DeadlineCalculation> {
    return this.api.post('/deadlines/calculate', body, true);
  }

  courtCalendar(year: number): Observable<CourtCalendar> {
    return this.api.get('/court-calendar', { year });
  }

  addCourtCalendar(body: Omit<CourtCalendarEntry, 'id'>): Observable<CourtCalendarEntry> {
    return this.api.post('/court-calendar', body);
  }

  deleteCourtCalendar(id: string): Observable<void> {
    return this.api.delete(`/court-calendar/${encodeURIComponent(id)}`);
  }

  /** Integrantes ativos da equipe (responsáveis possíveis), com cache. */
  staff(): Observable<EmployeeOption[]> {
    this.staff$ ??= this.api.get<EmployeeOption[]>('/employees/options').pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.staff$;
  }
}
