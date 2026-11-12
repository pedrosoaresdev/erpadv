import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { signal } from '@angular/core';
import { Me, Permission } from '../models/models';
import { AuthService } from './auth.service';
import { permissionGuard, staffGuard } from './guards';
import { toProblem } from './interceptors';

function me(partial: Partial<Me>): Me {
  return {
    uid: 'u1', email: 'a@b.test', name: 'Ana', emailVerified: true, onboardingRequired: false,
    emailVerificationRequired: false, officeId: 'o1', officeName: 'Escritório', role: 'ADVOGADO',
    roleLabel: 'Advogado', clientId: null, permissions: [], platformAdmin: false, customRoleName: null, ...partial,
  };
}

describe('guards e interceptors de autenticação', () => {
  it('master da plataforma sem escritório vai para o painel /plataforma', () => {
    const home = (AuthService.prototype.homePath as (m: Me | null) => string).call({}, me({
      role: 'MASTER', officeId: null, officeName: 'Plataforma ERPAdv', platformAdmin: true, permissions: ['PLATFORM_ADMIN'],
    }));
    expect(home).toBe('/plataforma');
  });

  const current = signal<Me | null>(null);
  const fakeAuth = {
    me: current,
    user: signal({ uid: 'u1' }),
    ready: () => Promise.resolve(),
    loadMe: () => Promise.resolve(current()),
    has: (p: Permission) => current()?.permissions.includes(p) ?? false,
    homePath: (m: Me | null) => (AuthService.prototype.homePath as (m: Me | null) => string).call({ mfaPending: () => false }, m),
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: fakeAuth },
        { provide: MatSnackBar, useValue: { open: () => undefined } },
      ],
    });
  });

  const run = (guard: typeof permissionGuard, data: Record<string, unknown> = {}) =>
    TestBed.runInInjectionContext(() =>
      guard({ data } as unknown as ActivatedRouteSnapshot, { url: '/x' } as RouterStateSnapshot),
    );

  it('permissionGuard libera quem tem a permissão', () => {
    current.set(me({ permissions: ['CLIENTS_READ'] }));
    expect(run(permissionGuard, { permission: 'CLIENTS_READ' })).toBeTrue();
  });

  it('permissionGuard redireciona quem não tem a permissão', () => {
    current.set(me({ permissions: [] }));
    const result = run(permissionGuard, { permission: 'AUDIT_READ' });
    expect(result instanceof UrlTree).toBeTrue();
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe('/dashboard');
  });

  it('staffGuard envia ao primeiro acesso quem ainda não tem escritório', async () => {
    current.set(me({ onboardingRequired: true, officeId: null, role: null }));
    const result = (await run(staffGuard)) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/primeiro-acesso');
  });

  it('staffGuard bloqueia clientes do portal na área interna', async () => {
    current.set(me({ role: 'CLIENTE' }));
    const result = (await run(staffGuard)) as UrlTree;
    expect(TestBed.inject(Router).serializeUrl(result)).toBe('/sem-acesso');
  });

  it('toProblem interpreta problem+json e falhas de rede', () => {
    const api = toProblem(new HttpErrorResponse({ status: 409, error: { code: 'CLIENT_DUPLICATE', detail: 'Duplicado' } }));
    expect(api.code).toBe('CLIENT_DUPLICATE');
    expect(api.detail).toBe('Duplicado');
    expect(toProblem(new HttpErrorResponse({ status: 0 })).code).toBe('NETWORK');
  });
});
