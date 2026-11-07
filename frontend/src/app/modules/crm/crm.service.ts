import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ClientType, LegalArea } from '../../core/models/models';

export type LeadStage = 'NOVO' | 'CONTATO' | 'QUALIFICADO' | 'PROPOSTA' | 'NEGOCIACAO' | 'GANHO' | 'PERDIDO';
export type LeadSource = 'INDICACAO' | 'SITE' | 'GOOGLE' | 'INSTAGRAM' | 'FACEBOOK' | 'LINKEDIN' | 'WHATSAPP' | 'EVENTO' | 'CLIENTE_ATUAL' | 'OUTRO';
export type LeadActivityType = 'NOTA' | 'LIGACAO' | 'EMAIL' | 'REUNIAO' | 'WHATSAPP' | 'PROPOSTA' | 'ETAPA' | 'CONVERSAO';

export const STAGES: { key: LeadStage; label: string; color: string; open: boolean }[] = [
  { key: 'NOVO', label: 'Novo', color: '#7a8ca8', open: true },
  { key: 'CONTATO', label: 'Primeiro contato', color: '#3b6fb6', open: true },
  { key: 'QUALIFICADO', label: 'Qualificado', color: '#2c7a7b', open: true },
  { key: 'PROPOSTA', label: 'Proposta enviada', color: '#b7791f', open: true },
  { key: 'NEGOCIACAO', label: 'Negociação', color: '#c05621', open: true },
  { key: 'GANHO', label: 'Ganho', color: '#2f855a', open: false },
  { key: 'PERDIDO', label: 'Perdido', color: '#9b2c2c', open: false },
];

export const SOURCE_LABELS: Record<LeadSource, string> = {
  INDICACAO: 'Indicação', SITE: 'Site', GOOGLE: 'Google', INSTAGRAM: 'Instagram', FACEBOOK: 'Facebook', LINKEDIN: 'LinkedIn',
  WHATSAPP: 'WhatsApp', EVENTO: 'Evento/palestra', CLIENTE_ATUAL: 'Cliente atual', OUTRO: 'Outro',
};

export const ACTIVITY_LABELS: Record<LeadActivityType, { label: string; icon: string }> = {
  NOTA: { label: 'Anotação', icon: 'sticky_note_2' },
  LIGACAO: { label: 'Ligação', icon: 'call' },
  EMAIL: { label: 'E-mail', icon: 'mail' },
  REUNIAO: { label: 'Reunião', icon: 'groups' },
  WHATSAPP: { label: 'WhatsApp', icon: 'chat' },
  PROPOSTA: { label: 'Proposta', icon: 'request_quote' },
  ETAPA: { label: 'Etapa', icon: 'swap_horiz' },
  CONVERSAO: { label: 'Conversão', icon: 'verified' },
};

export interface Lead {
  id: string;
  name: string;
  type: ClientType;
  company: string | null;
  phone: string | null;
  email: string | null;
  source: LeadSource;
  sourceLabel: string;
  sourceDetail: string | null;
  legalArea: LegalArea | null;
  subject: string | null;
  stage: LeadStage;
  stageLabel: string;
  probability: number;
  estimatedValueCents: number;
  ownerUid: string;
  ownerName: string;
  nextAction: string | null;
  nextActionDate: string | null;
  nextActionOverdue: boolean;
  notes: string | null;
  lostReason: string | null;
  tags: string[];
  score: number;
  temperature: 'QUENTE' | 'MORNO' | 'FRIO';
  daysInStage: number;
  stageEnteredAt: string | null;
  lastActivityAt: string | null;
  wonAt: string | null;
  lostAt: string | null;
  convertedClientId: string | null;
  createdAt: string | null;
}

export interface LeadActivity {
  id: string;
  type: LeadActivityType;
  text: string;
  actorName: string;
  at: string;
}

export interface LeadRequest {
  name: string;
  type: ClientType;
  company?: string | null;
  phone?: string | null;
  email?: string | null;
  source: LeadSource;
  sourceDetail?: string | null;
  legalArea?: LegalArea | null;
  subject?: string | null;
  stage?: LeadStage | null;
  estimatedValueCents: number;
  ownerUid?: string | null;
  nextAction?: string | null;
  nextActionDate?: string | null;
  notes?: string | null;
  tags: string[];
}

export interface CrmMetrics {
  openLeads: number;
  pipelineValueCents: number;
  weightedPipelineCents: number;
  wonCount: number;
  lostCount: number;
  conversionRate: number;
  avgDaysToWin: number;
  overdueActions: number;
  hotLeads: number;
  funnel: { stage: LeadStage; label: string; count: number; valueCents: number }[];
  sources: { source: LeadSource; label: string; leads: number; won: number; conversionRate: number }[];
  owners: { uid: string; name: string; open: number; won: number; wonValueCents: number }[];
  months: { month: string; created: number; won: number; wonValueCents: number }[];
  lostReasons: string[];
}

@Injectable({ providedIn: 'root' })
export class CrmService {
  private readonly api = inject(ApiService);

  list(filter: { search?: string; ownerUid?: string; open?: boolean } = {}): Observable<Lead[]> {
    return this.api.get('/crm/leads', filter);
  }

  get(id: string): Observable<{ lead: Lead; activities: LeadActivity[] }> {
    return this.api.get(`/crm/leads/${encodeURIComponent(id)}`);
  }

  create(body: LeadRequest): Observable<Lead> {
    return this.api.post('/crm/leads', body);
  }

  update(id: string, body: LeadRequest): Observable<Lead> {
    return this.api.put(`/crm/leads/${encodeURIComponent(id)}`, body);
  }

  stage(id: string, stage: LeadStage, lostReason?: string | null): Observable<Lead> {
    return this.api.post(`/crm/leads/${encodeURIComponent(id)}/stage`, { stage, lostReason: lostReason ?? null });
  }

  activity(id: string, body: { type: LeadActivityType; text: string; nextAction?: string | null; nextActionDate?: string | null }) {
    return this.api.post<{ lead: Lead; activities: LeadActivity[] }>(`/crm/leads/${encodeURIComponent(id)}/activities`, body);
  }

  convert(id: string, client: unknown): Observable<{ lead: Lead; clientId: string }> {
    return this.api.post(`/crm/leads/${encodeURIComponent(id)}/convert`, { client });
  }

  delete(id: string): Observable<void> {
    return this.api.delete(`/crm/leads/${encodeURIComponent(id)}`);
  }

  metrics(months = 6): Observable<CrmMetrics> {
    return this.api.get('/crm/metrics', { months });
  }
}
