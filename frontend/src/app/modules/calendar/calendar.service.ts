import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Appointment, AppointmentRequest, AppointmentStatus, AuditLog, CalendarEvent, CursorPage } from '../../core/models/models';

@Injectable({ providedIn: 'root' })
export class CalendarService {
  private readonly api = inject(ApiService);

  events(from: Date, to: Date, mine: boolean, deadlines: boolean, responsibleUid?: string | null): Observable<CalendarEvent[]> {
    return this.api.get('/calendar/events', { from: from.toISOString(), to: to.toISOString(), mine, deadlines, responsibleUid });
  }

  hearings(from: Date, to: Date, responsibleUid?: string | null): Observable<Appointment[]> {
    return this.api.get('/calendar/hearings', { from: from.toISOString(), to: to.toISOString(), responsibleUid });
  }

  rsvp(id: string, status: 'CONFIRMADO' | 'RECUSADO' | 'TALVEZ'): Observable<Appointment> {
    return this.api.post(`/calendar/appointments/${encodeURIComponent(id)}/rsvp`, { status });
  }

  setStatus(id: string, status: AppointmentStatus, outcome: string | null): Observable<Appointment> {
    return this.api.post(`/calendar/appointments/${encodeURIComponent(id)}/status`, { status, outcome });
  }

  ics(id: string) {
    return this.api.getBlob(`/calendar/appointments/${encodeURIComponent(id)}/ics`);
  }

  history(id: string): Observable<CursorPage<AuditLog>> {
    return this.api.get(`/calendar/appointments/${encodeURIComponent(id)}/history`);
  }

  report(q: { from: string; to: string; responsibleUid?: string | null; hearings: boolean; format: 'pdf' | 'xlsx' }) {
    return this.api.getBlob('/calendar/report', q);
  }

  get(id: string): Observable<Appointment> {
    return this.api.get(`/calendar/appointments/${encodeURIComponent(id)}`);
  }

  create(body: AppointmentRequest): Observable<Appointment> {
    return this.api.post('/calendar/appointments', body);
  }

  update(id: string, body: AppointmentRequest, series = false): Observable<Appointment> {
    return this.api.put(`/calendar/appointments/${encodeURIComponent(id)}${series ? '?series=true' : ''}`, body);
  }

  delete(id: string, series = false): Observable<void> {
    return this.api.delete(`/calendar/appointments/${encodeURIComponent(id)}${series ? '?series=true' : ''}`);
  }
}
