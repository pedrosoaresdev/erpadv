import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CursorPage, Employee, Permission, PermissionInfo, Role, RoleInfo } from '../../core/models/models';

export interface InviteRequest {
  email: string;
  displayName: string;
  role?: Role | null;
  customRoleId?: string | null;
  oabNumber?: string | null;
  cpf?: string | null;
  phone?: string | null;
  position?: string | null;
  password?: string | null;
}

export interface UpdateEmployeeRequest {
  displayName: string;
  role: Role;
  customRoleId?: string | null;
  cpf?: string | null;
  oabNumber?: string | null;
  phone?: string | null;
  position?: string | null;
  permissionsGranted: Permission[];
  permissionsRevoked: Permission[];
}

/** Cargo personalizado do escritório (ex.: "Advogado júnior"). */
export interface CustomRole {
  id: string;
  name: string;
  description: string | null;
  color: string | null;
  baseRole: Role;
  baseRoleLabel: string;
  permissions: Permission[];
  users: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface CustomRoleRequest {
  name: string;
  description?: string | null;
  color?: string | null;
  baseRole: Role;
  permissions: Permission[];
}

export interface RolePreset {
  name: string;
  description: string;
  baseRole: Role;
  color: string;
  permissions: Permission[];
}

@Injectable({ providedIn: 'root' })
export class EmployeesService {
  private readonly api = inject(ApiService);

  list(cursor: string | null): Observable<CursorPage<Employee>> {
    return this.api.get('/employees', { cursor, size: 50 });
  }

  roles(): Observable<RoleInfo[]> {
    return this.api.get('/employees/roles');
  }

  permissions(): Observable<PermissionInfo[]> {
    return this.api.get('/employees/permissions');
  }

  invite(body: InviteRequest): Observable<{ employee: Employee; passwordSetupLink: string | null }> {
    return this.api.post('/employees', body);
  }

  update(uid: string, body: UpdateEmployeeRequest): Observable<Employee> {
    return this.api.put(`/employees/${encodeURIComponent(uid)}`, body);
  }

  setPassword(uid: string, password: string): Observable<Employee> {
    return this.api.post(`/employees/${encodeURIComponent(uid)}/password`, { password });
  }

  block(uid: string, reason: string): Observable<Employee> {
    return this.api.post(`/employees/${encodeURIComponent(uid)}/block`, { reason });
  }

  unblock(uid: string): Observable<Employee> {
    return this.api.post(`/employees/${encodeURIComponent(uid)}/unblock`);
  }

  revokeSessions(uid: string): Observable<Employee> {
    return this.api.post(`/employees/${encodeURIComponent(uid)}/revoke-sessions`);
  }

  resetAccess(uid: string): Observable<{ passwordResetLink: string }> {
    return this.api.post(`/employees/${encodeURIComponent(uid)}/reset-access`);
  }

  // ------------------------------------------------------------------ cargos personalizados

  customRoles(): Observable<CustomRole[]> {
    return this.api.get('/roles');
  }

  presets(): Observable<RolePreset[]> {
    return this.api.get('/roles/presets');
  }

  createRole(body: CustomRoleRequest): Observable<CustomRole> {
    return this.api.post('/roles', body);
  }

  updateRole(id: string, body: CustomRoleRequest): Observable<CustomRole> {
    return this.api.put(`/roles/${encodeURIComponent(id)}`, body);
  }

  deleteRole(id: string): Observable<void> {
    return this.api.delete(`/roles/${encodeURIComponent(id)}`);
  }
}
