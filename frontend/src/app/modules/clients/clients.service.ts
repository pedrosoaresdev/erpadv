import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import {
  Client,
  ClientInteraction,
  ClientRequest,
  ClientStatus,
  ClientSummary,
  ClientType,
  CursorPage,
  InteractionChannel,
  LegalCase,
} from '../../core/models/models';

export interface ClientFilter {
  search?: string;
  status?: ClientStatus | '';
  type?: ClientType | '';
  defaulting?: boolean | null;
}

@Injectable({ providedIn: 'root' })
export class ClientsService {
  private readonly api = inject(ApiService);

  list(filter: ClientFilter, cursor: string | null, size = 20): Observable<CursorPage<ClientSummary>> {
    return this.api.get('/clients', {
      search: filter.search,
      status: filter.status,
      type: filter.type,
      defaulting: filter.defaulting ?? undefined,
      cursor,
      size,
    });
  }

  get(id: string): Observable<Client> {
    return this.api.get(`/clients/${encodeURIComponent(id)}`);
  }

  create(body: ClientRequest): Observable<Client> {
    return this.api.post('/clients', body);
  }

  update(id: string, body: ClientRequest): Observable<Client> {
    return this.api.put(`/clients/${encodeURIComponent(id)}`, body);
  }

  inactivate(id: string, reason: string): Observable<Client> {
    return this.api.post(`/clients/${encodeURIComponent(id)}/inactivate`, { reason });
  }

  reactivate(id: string): Observable<Client> {
    return this.api.post(`/clients/${encodeURIComponent(id)}/reactivate`);
  }

  setDefaulting(id: string, defaulting: boolean): Observable<Client> {
    return this.api.patch(`/clients/${encodeURIComponent(id)}/defaulting`, { defaulting });
  }

  interactions(id: string, cursor: string | null): Observable<CursorPage<ClientInteraction>> {
    return this.api.get(`/clients/${encodeURIComponent(id)}/interactions`, { cursor, size: 20 });
  }

  addInteraction(id: string, body: { channel: InteractionChannel; subject: string; description: string | null; occurredAt: string }): Observable<ClientInteraction> {
    return this.api.post(`/clients/${encodeURIComponent(id)}/interactions`, body);
  }

  cases(id: string, cursor: string | null): Observable<CursorPage<LegalCase>> {
    return this.api.get(`/clients/${encodeURIComponent(id)}/cases`, { cursor, size: 20 });
  }
}
