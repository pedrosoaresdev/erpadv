import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Permission } from '../models/models';
import { ToastService } from '../services/toast.service';
import { AuthService } from './auth.service';

/** Exige usuário autenticado no Firebase. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ready();
  if (!auth.user()) {
    return router.createUrlTree(['/login'], { queryParams: { voltar: state.url } });
  }
  return true;
};

/** Área interna: e-mail verificado (quando exigido), escritório criado e perfil de equipe. */
export const staffGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ready();
  let me = auth.me();
  if (!me && auth.user()) {
    me = await auth.loadMe().catch(() => null);
  }
  const home = auth.homePath(me);
  return home === '/dashboard' ? true : router.parseUrl(home);
};

/** Painel master da plataforma: somente administradores da plataforma (PLATFORM_ADMIN). */
export const platformGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ready();
  let me = auth.me();
  if (!me && auth.user()) {
    me = await auth.loadMe().catch(() => null);
  }
  return me?.platformAdmin ? true : router.parseUrl(auth.homePath(me));
};

/** Telas públicas (login, cadastro): quem já está dentro vai para a página inicial. */
export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.ready();
  return auth.user() && auth.me() ? router.parseUrl(auth.homePath()) : true;
};

/** Permissão exigida pela rota: data: { permission: 'CLIENTS_READ' }. */
export const permissionGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const toast = inject(ToastService);
  const required = route.data['permission'] as Permission | undefined;
  if (!required || auth.has(required)) {
    return true;
  }
  toast.error('Você não tem permissão para acessar este módulo.');
  return router.parseUrl('/dashboard');
};
