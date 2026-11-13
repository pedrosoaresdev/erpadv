import { HttpResponse } from '@angular/common/http';
import { Injectable, Signal, inject, signal } from '@angular/core';
import { Observable, catchError, of, shareReplay, tap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Address } from '../../core/models/models';

export interface ModuleView { code: string; label: string; inPlan: boolean; enabled: boolean }

export interface OfficeSettings {
  id: string;
  name: string;
  cnpj: string | null;
  email: string | null;
  phone: string | null;
  website: string | null;
  oabRegistration: string | null;
  address: Address | null;
  hasLogo: boolean;
  logoUpdatedAt: string | null;
  modules: ModuleView[];
  planCode: string | null;
  planName: string | null;
  subscriptionStatus: SubscriptionStatus | null;
  trialEndsAt: string | null;
}

export interface ActivityItem {
  id: string;
  action: string;
  summary: string | null;
  actorName: string | null;
  actorEmail: string | null;
  at: string;
}

export interface OfficeUsage {
  users: number;
  maxUsers: number | null;
  storageBytes: number;
  maxStorageMb: number | null;
  clients: number;
  cases: number;
  documents: number;
  recentActivity: ActivityItem[];
}

export interface OnboardingStep { key: string; label: string; done: boolean; link: string }
export interface Onboarding { dismissed: boolean; complete: boolean; steps: OnboardingStep[] }

export interface Unit {
  id: string;
  name: string;
  cnpj: string | null;
  phone: string | null;
  email: string | null;
  address: Address | null;
  managerUid: string | null;
  managerName: string | null;
  active: boolean;
  members: number;
  cases: number;
  memberUids: string[];
}

export interface UnitRequest {
  name: string;
  cnpj: string | null;
  phone: string | null;
  email: string | null;
  address: Address | null;
  managerUid: string | null;
  active: boolean;
}

export type SubscriptionStatus = 'TRIAL' | 'ATIVA' | 'EM_ATRASO' | 'SUSPENSA' | 'CANCELADA';
export type BillingCycle = 'MENSAL' | 'ANUAL';
export type InvoiceStatus = 'ABERTA' | 'VENCIDA' | 'PAGA' | 'CANCELADA';

export const SUBSCRIPTION_LABELS: Record<SubscriptionStatus, string> = {
  TRIAL: 'Avaliação', ATIVA: 'Ativa', EM_ATRASO: 'Em atraso', SUSPENSA: 'Suspensa', CANCELADA: 'Cancelada',
};
export const INVOICE_LABELS: Record<InvoiceStatus, string> = {
  ABERTA: 'Em aberto', VENCIDA: 'Vencida', PAGA: 'Paga', CANCELADA: 'Cancelada',
};
export const CYCLE_LABELS: Record<BillingCycle, string> = { MENSAL: 'Mensal', ANUAL: 'Anual' };

export interface PlanInfo {
  id: string;
  code: string;
  name: string;
  description: string | null;
  monthlyPriceCents: number | null;
  yearlyPriceCents: number | null;
  maxUsers: number | null;
  maxStorageMb: number | null;
  modules: string[];
  trialDays: number;
  active: boolean;
  highlight: boolean;
  sortOrder: number;
}

export interface SubscriptionView {
  officeId: string;
  officeName: string;
  planCode: string | null;
  planName: string | null;
  status: SubscriptionStatus | null;
  billingCycle: BillingCycle | null;
  priceCents: number | null;
  trialEndsAt: string | null;
  nextInvoiceDate: string | null;
  graceDays: number | null;
  maxUsers: number | null;
  maxStorageMb: number | null;
  users: number;
  storageBytes: number;
  modules: string[];
  billingEmail: string | null;
  suspended: boolean;
  suspendedReason: string | null;
}

export interface SubscriptionInvoice {
  id: string;
  officeId: string;
  number: string;
  officeName: string;
  planCode: string;
  planName: string;
  cycle: BillingCycle;
  periodStart: string;
  periodEnd: string;
  amountCents: number;
  dueDate: string;
  status: InvoiceStatus;
  paidDate: string | null;
  paidAmountCents: number | null;
  paymentMethod: string | null;
  note: string | null;
  markedByName: string | null;
  cancelReason: string | null;
  createdAt: string | null;
}

export interface BillingSettings {
  issuerName: string | null;
  issuerDocument: string | null;
  pixKey: string | null;
  bankInfo: string | null;
  instructions: string | null;
  contactEmail: string | null;
}

export interface SubscriptionOverview {
  subscription: SubscriptionView;
  invoices: SubscriptionInvoice[];
  plans: PlanInfo[];
  payment: BillingSettings;
}

export type TicketStatus = 'ABERTO' | 'EM_ATENDIMENTO' | 'AGUARDANDO_ESCRITORIO' | 'RESOLVIDO';
export const TICKET_LABELS: Record<TicketStatus, string> = {
  ABERTO: 'Aberto', EM_ATENDIMENTO: 'Em atendimento', AGUARDANDO_ESCRITORIO: 'Aguardando o escritório', RESOLVIDO: 'Resolvido',
};
export const TICKET_CATEGORIES: { value: string; label: string }[] = [
  { value: 'DUVIDA', label: 'Dúvida' },
  { value: 'PROBLEMA', label: 'Problema / erro' },
  { value: 'PLANO', label: 'Plano e assinatura' },
  { value: 'FINANCEIRO', label: 'Cobrança' },
  { value: 'SUGESTAO', label: 'Sugestão' },
];
export const TICKET_PRIORITIES: { value: string; label: string }[] = [
  { value: 'BAIXA', label: 'Baixa' }, { value: 'NORMAL', label: 'Normal' }, { value: 'ALTA', label: 'Alta' },
];

export interface TicketMessage { at: string; authorName: string; fromPlatform: boolean; text: string }
export interface SupportTicket {
  id: string;
  officeId: string;
  number: string;
  officeName: string;
  subject: string;
  category: string;
  priority: string;
  status: TicketStatus;
  openedByName: string | null;
  openedByEmail: string | null;
  createdAt: string | null;
  lastMessageAt: string | null;
  awaitingPlatform: boolean;
  messages: TicketMessage[];
}

/** Rótulos dos módulos contratáveis (mesmos códigos do servidor). */
export const MODULE_LABELS: Record<string, string> = {
  FINANCEIRO: 'Financeiro e honorários', CONTRATOS: 'Contratos', CRM: 'CRM', QUADROS: 'Tarefas e quadros',
  RELATORIOS: 'Relatórios', LGPD: 'LGPD',
};

export function formatBytes(bytes: number | null | undefined): string {
  const b = bytes ?? 0;
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1).replace('.', ',')} KB`;
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
  return `${(b / 1024 / 1024 / 1024).toFixed(2).replace('.', ',')} GB`;
}

export function formatMb(mb: number | null | undefined): string {
  if (mb === null || mb === undefined) return 'ilimitado';
  return mb >= 1024 ? `${(mb / 1024).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} GB` : `${mb} MB`;
}

/** Configurações do escritório, unidades, plano/assinatura e suporte. */
@Injectable({ providedIn: 'root' })
export class OfficeService {
  private readonly api = inject(ApiService);
  private units$?: Observable<Unit[]>;
  /** Situação do escritório para o banner e a logo do menu (carregada uma vez por sessão). */
  readonly status = signal<OfficeSettings | null>(null);
  readonly logoUrl = signal<string | null>(null);

  settings(): Observable<OfficeSettings> {
    return this.api.get<OfficeSettings>('/office').pipe(tap((s) => this.status.set(s)));
  }

  /** Carrega situação e logo para o layout; silencioso (sem toast) para não atrapalhar quem não tem acesso. */
  loadStatus(): void {
    this.api.get<OfficeSettings>('/office', undefined, true).pipe(catchError(() => of(null))).subscribe((s) => {
      this.status.set(s);
      if (s?.hasLogo) this.refreshLogo(); else this.setLogo(null);
    });
  }

  refreshLogo(): void {
    this.api.getBlob('/office/logo').pipe(catchError(() => of(null))).subscribe((r: HttpResponse<Blob> | null) =>
      this.setLogo(r?.body ? URL.createObjectURL(r.body) : null));
  }

  private setLogo(url: string | null): void {
    const old = this.logoUrl();
    if (old) URL.revokeObjectURL(old);
    this.logoUrl.set(url);
  }

  update(body: Partial<OfficeSettings>): Observable<OfficeSettings> {
    return this.api.put<OfficeSettings>('/office', body).pipe(tap((s) => this.status.set(s)));
  }

  uploadLogo(file: File): Observable<OfficeSettings> {
    const form = new FormData();
    form.append('file', file);
    return this.api.postForm<OfficeSettings>('/office/logo', form).pipe(tap((s) => { this.status.set(s); this.refreshLogo(); }));
  }

  deleteLogo(): Observable<OfficeSettings> {
    return this.api.delete<OfficeSettings>('/office/logo').pipe(tap((s) => { this.status.set(s); this.setLogo(null); }));
  }

  setModules(enabled: string[]): Observable<OfficeSettings> {
    return this.api.put<OfficeSettings>('/office/modules', { enabled });
  }

  usage(): Observable<OfficeUsage> {
    return this.api.get('/office/usage');
  }

  onboarding(): Observable<Onboarding> {
    return this.api.get('/office/onboarding', undefined, true);
  }

  dismissOnboarding(): Observable<Onboarding> {
    return this.api.post('/office/onboarding/dismiss');
  }

  // ------------------------------------------------------------------ unidades

  units(): Observable<Unit[]> {
    return this.api.get('/units');
  }

  /** Unidades ativas para selects (cache da sessão; vazio quando o escritório não usa unidades). */
  unitOptions(): Signal<Unit[]> {
    this.units$ ??= this.api.get<Unit[]>('/units', undefined, true).pipe(catchError(() => of([])), shareReplay({ bufferSize: 1, refCount: false }));
    const s = signal<Unit[]>([]);
    this.units$.subscribe((u) => s.set(u.filter((x) => x.active)));
    return s.asReadonly();
  }

  invalidateUnits(): void {
    this.units$ = undefined;
  }

  saveUnit(id: string | null, body: UnitRequest): Observable<Unit> {
    this.invalidateUnits();
    return id ? this.api.put(`/units/${encodeURIComponent(id)}`, body) : this.api.post('/units', body);
  }

  setUnitMembers(id: string, uids: string[]): Observable<Unit> {
    return this.api.put(`/units/${encodeURIComponent(id)}/members`, { uids });
  }

  deleteUnit(id: string): Observable<void> {
    this.invalidateUnits();
    return this.api.delete(`/units/${encodeURIComponent(id)}`);
  }

  // ------------------------------------------------------------------ plano e assinatura

  subscription(): Observable<SubscriptionOverview> {
    return this.api.get('/subscription');
  }

  invoicePdf(id: string): Observable<HttpResponse<Blob>> {
    return this.api.getBlob(`/subscription/invoices/${encodeURIComponent(id)}/pdf`);
  }

  requestChange(planCode: string, billingCycle: BillingCycle, message: string | null): Observable<SupportTicket> {
    return this.api.post('/subscription/change-request', { planCode, billingCycle, message });
  }

  // ------------------------------------------------------------------ suporte

  tickets(): Observable<SupportTicket[]> {
    return this.api.get('/support/tickets');
  }

  openTicket(body: { subject: string; category: string; priority: string; message: string }): Observable<SupportTicket> {
    return this.api.post('/support/tickets', body);
  }

  reply(id: string, message: string | null, status: TicketStatus | null): Observable<SupportTicket> {
    return this.api.post(`/support/tickets/${encodeURIComponent(id)}/reply`, { message, status });
  }
}

