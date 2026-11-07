import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Appointment, AppointmentRequest, CalendarEvent } from '../../core/models/models';

@Injectable({ providedIn: 'root' })
export class CalendarService {
  private readonly api = inject(ApiService);

  events(from: Date, to: Date, mine: boolean, deadlines: boolean): Observable<CalendarEvent[]> {
    return this.api.get('/calendar/events', { from: from.toISOString(), to: to.toISOString(), mine, deadlines });
  }

  get(id: string): Observable<Appointment> {
    return this.api.get(`/calendar/appointments/${encodeURIComponent(id)}`);
  }

  create(body: AppointmentRequest): Observable<Appointment> {
    return this.api.post('/calendar/appointments', body);
  }

  update(id: string, body: AppointmentRequest): Observable<Appointment> {
    return this.api.put(`/calendar/appointments/${encodeURIComponent(id)}`, body);
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/calendar/appointments/${encodeURIComponent(id)}`);
  }
}
