import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { HttpResponse } from '@angular/common/http';
import { Role, UserStatus } from '../../core/models/models';
import {
  BillingSettings, InvoiceStatus, PlanInfo, SubscriptionInvoice, SubscriptionStatus, SubscriptionView, SupportTicket, TicketStatus,
} from '../settings/office.service';

export interface SubscriptionRequest {
  planCode: string | null;
  billingCycle: 'MENSAL' | 'ANUAL' | null;
  priceOverrideCents: number | null;
  graceDays: number | null;
  maxUsers: number | null;
  maxStorageMb: number | null;
  billingEmail: string | null;
  subscriptionStatus: SubscriptionStatus | null;
  nextInvoiceDate: string | null;
  trialEndsAt: string | null;
}

export interface PlatformAlert { officeId: string; officeName: string; kind: 'TRIAL' | 'STORAGE' | 'NO_PRICE'; message: string }

export interface PlatformMetrics {
  offices: number;
  byStatus: Record<string, number>;
  bySubscription: Record<string, number>;
  byPlan: Record<string, number>;
  mrrCents: number;
  openInvoicesCents: number;
  overdueInvoices: number;
  overdueInvoicesCents: number;
  users: number;
  activeUsers7d: number;
  storageBytes: number;
  ticketsAwaiting: number;
  alerts: PlatformAlert[];
  health: string;
  uptimeMinutes: number;
  generatedAt: string;
}

export type Plan = 'ESSENCIAL' | 'PROFISSIONAL' | 'ENTERPRISE' | string;

export const PLAN_LABELS: Record<string, string> = {
  ESSENCIAL: 'Essencial',
  PROFISSIONAL: 'Profissional',
  ENTERPRISE: 'Enterprise',
};

export interface OfficeSummary {
  id: string;
  name: string;
  cnpj: string | null;
  email: string | null;
  phone: string | null;
  city: string | null;
  state: string | null;
  plan: Plan;
  status: 'ACTIVE' | 'SUSPENDED';
  maxUsers: number | null;
  createdAt: string | null;
  suspendedAt: string | null;
  suspendedReason: string | null;
  createdByPlatform: boolean;
  seats: number;
}

export interface OfficeUser {
  uid: string;
  displayName: string;
  email: string;
  role: Role;
  roleLabel: string;
  customRoleName: string | null;
  status: UserStatus;
  oabNumber: string | null;
  cpfMasked: string | null;
  owner: boolean;
  createdAt: string | null;
  lastLoginAt: string | null;
}

export interface OfficeDetail {
  office: OfficeSummary;
  users: OfficeUser[];
  clients: number;
  cases: number;
  contracts: number;
  boards: number;
  leads: number;
}

export interface PlatformOverview {
  stats: { offices: number; activeOffices: number; suspendedOffices: number; users: number; activeUsers: number };
  offices: OfficeSummary[];
}

export interface AdminUserRequest {
  fullName: string;
  cpf: string;
  oabNumber: string;
  email: string;
  password: string;
  phone?: string | null;
}

export interface OfficeRequest {
  name: string;
  cnpj?: string | null;
  email?: string | null;
  phone?: string | null;
  city?: string | null;
  state?: string | null;
  plan: Plan;
  maxUsers?: number | null;
}

/** Painel master do desenvolvedor da plataforma. */
@Injectable({ providedIn: 'root' })
export class PlatformService {
  private readonly api = inject(ApiService);

  overview(): Observable<PlatformOverview> {
    return this.api.get('/platform/offices');
  }

  detail(id: string): Observable<OfficeDetail> {
    return this.api.get(`/platform/offices/${encodeURIComponent(id)}`);
  }

  create(body: OfficeRequest & { admin: AdminUserRequest }): Observable<{ office: OfficeSummary; admin: OfficeUser }> {
    return this.api.post('/platform/offices', body);
  }

  update(id: string, body: OfficeRequest): Observable<OfficeSummary> {
    return this.api.put(`/platform/offices/${encodeURIComponent(id)}`, body);
  }

  suspend(id: string, reason: string): Observable<OfficeSummary> {
    return this.api.post(`/platform/offices/${encodeURIComponent(id)}/suspend`, { reason });
  }

  reactivate(id: string): Observable<OfficeSummary> {
    return this.api.post(`/platform/offices/${encodeURIComponent(id)}/reactivate`);
  }

  addAdmin(id: string, body: AdminUserRequest): Observable<OfficeUser> {
    return this.api.post(`/platform/offices/${encodeURIComponent(id)}/admins`, body);
  }

  setPassword(officeId: string, uid: string, password: string): Observable<void> {
    return this.api.post(`/platform/offices/${encodeURIComponent(officeId)}/users/${encodeURIComponent(uid)}/password`, { password });
  }

  resetLink(officeId: string, uid: string): Observable<{ link: string }> {
    return this.api.post(`/platform/offices/${encodeURIComponent(officeId)}/users/${encodeURIComponent(uid)}/reset-link`);
  }

  // ------------------------------------------------------------------ planos, assinaturas, faturas (cobrança manual)

  plans(): Observable<PlanInfo[]> {
    return this.api.get('/platform/plans');
  }

  savePlan(p: Omit<PlanInfo, 'id'>): Observable<PlanInfo> {
    return this.api.put('/platform/plans', p);
  }

  subscription(officeId: string): Observable<SubscriptionView> {
    return this.api.get(`/platform/offices/${encodeURIComponent(officeId)}/subscription`);
  }

  updateSubscription(officeId: string, body: SubscriptionRequest): Observable<SubscriptionView> {
    return this.api.put(`/platform/offices/${encodeURIComponent(officeId)}/subscription`, body);
  }

  issueInvoice(officeId: string): Observable<SubscriptionInvoice> {
    return this.api.post(`/platform/offices/${encodeURIComponent(officeId)}/invoices`);
  }

  invoices(officeId: string | null, status: InvoiceStatus | null): Observable<SubscriptionInvoice[]> {
    return this.api.get('/platform/invoices', { officeId, status });
  }

  pay(id: string, body: { paidDate: string | null; amountCents: number | null; method: string | null; note: string | null }): Observable<SubscriptionInvoice> {
    return this.api.post(`/platform/invoices/${encodeURIComponent(id)}/pay`, body);
  }

  cancelInvoice(id: string, reason: string): Observable<SubscriptionInvoice> {
    return this.api.post(`/platform/invoices/${encodeURIComponent(id)}/cancel`, { reason });
  }

  invoicePdf(id: string): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/platform/invoices/${encodeURIComponent(id)}/pdf`);
  }

  runBilling(): Observable<{ actions: number }> {
    return this.api.post('/platform/billing/run');
  }

  billingSettings(): Observable<BillingSettings> {
    return this.api.get('/platform/billing/settings');
  }

  saveBillingSettings(s: BillingSettings): Observable<BillingSettings> {
    return this.api.put('/platform/billing/settings', s);
  }

  // ------------------------------------------------------------------ suporte e monitoramento

  tickets(status: TicketStatus | null): Observable<SupportTicket[]> {
    return this.api.get('/platform/support/tickets', { status });
  }

  reply(id: string, message: string | null, status: TicketStatus | null): Observable<SupportTicket> {
    return this.api.post(`/platform/support/tickets/${encodeURIComponent(id)}/reply`, { message, status });
  }

  monitoring(): Observable<PlatformMetrics> {
    return this.api.get('/platform/monitoring');
  }
}
