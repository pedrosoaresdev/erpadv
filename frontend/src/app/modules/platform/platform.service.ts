import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { Role, UserStatus } from '../../core/models/models';

export type Plan = 'ESSENCIAL' | 'PROFISSIONAL' | 'ENTERPRISE';

export const PLAN_LABELS: Record<Plan, string> = {
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
}
