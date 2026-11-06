import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { from, switchMap, throwError, catchError } from 'rxjs';
import { ConfigService } from '../config/config.service';
import { SILENT_ERRORS } from '../api/api.service';
import { ApiProblem } from '../models/models';
import { ToastService } from '../services/toast.service';
import { AuthService } from './auth.service';

/** Anexa o ID token do Firebase às chamadas da API (somente para a própria API). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const config = inject(ConfigService);
  const auth = inject(AuthService);
  if (!req.url.startsWith(config.apiUrl)) {
    return next(req);
  }
  return from(auth.token()).pipe(
    switchMap((token) => next(token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req)),
  );
};

const SESSION_CODES = new Set(['INVALID_TOKEN', 'SESSION_REVOKED', 'PROFILE_MISMATCH', 'UNAUTHENTICATED', 'ACCOUNT_BLOCKED']);

/** Padroniza erros (problem+json), exibe mensagens e encerra sessões inválidas. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const toast = inject(ToastService);
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const problem = toProblem(error);
      if (SESSION_CODES.has(problem.code) && auth.user()) {
        toast.error(problem.detail);
        void auth.logout();
      } else if (!req.context.get(SILENT_ERRORS)) {
        toast.error(problem.fields ? `${problem.detail} Verifique os campos destacados.` : problem.detail);
      }
      return throwError(() => problem);
    }),
  );
};

export function toProblem(error: HttpErrorResponse): ApiProblem {
  const body = error.error as Partial<ApiProblem> | null;
  if (body && typeof body === 'object' && body.code) {
    return {
      status: error.status,
      code: body.code,
      detail: body.detail ?? 'Erro inesperado.',
      traceId: body.traceId,
      fields: body.fields,
    };
  }
  if (error.status === 0) {
    return { status: 0, code: 'NETWORK', detail: 'Servidor indisponível. Verifique sua conexão.' };
  }
  if (error.status === 429) {
    return { status: 429, code: 'RATE_LIMITED', detail: 'Muitas requisições. Aguarde alguns instantes.' };
  }
  return { status: error.status, code: 'HTTP_' + error.status, detail: 'Erro inesperado no servidor.' };
}
