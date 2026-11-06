import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { CursorPage, Employee, Permission, PermissionInfo, Role, RoleInfo } from '../../core/models/models';

export interface InviteRequest {
  email: string;
  displayName: string;
  role: Role;
  oabNumber?: string | null;
  phone?: string | null;
  position?: string | null;
}

export interface UpdateEmployeeRequest {
  displayName: string;
  role: Role;
  oabNumber?: string | null;
  phone?: string | null;
  position?: string | null;
  permissionsGranted: Permission[];
  permissionsRevoked: Permission[];
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

  invite(body: InviteRequest): Observable<{ employee: Employee; passwordSetupLink: string }> {
    return this.api.post('/employees', body);
  }

  update(uid: string, body: UpdateEmployeeRequest): Observable<Employee> {
    return this.api.put(`/employees/${encodeURIComponent(uid)}`, body);
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
}
