import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { catchError, switchMap, throwError } from 'rxjs';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';

/** Código devolvido quando o usuário desiste de informar a justificativa (não exibe mensagem de erro). */
export const JUSTIFICATION_CANCELLED = 'JUSTIFICATION_CANCELLED';

/**
 * Quando a política do escritório exige justificativa para exclusões, o servidor responde JUSTIFICATION_REQUIRED.
 * Este interceptador pede o motivo e reenvia a mesma requisição com o cabeçalho X-Audit-Justification, que vai para a
 * auditoria. Funciona para qualquer botão de exclusão do sistema, sem tratamento específico em cada tela.
 */
export const justificationInterceptor: HttpInterceptorFn = (req, next) => {
  const dialog = inject(MatDialog);
  return next(req).pipe(
    catchError((error: HttpErrorResponse) => {
      const code = (error.error as { code?: string } | null)?.code;
      if (code !== 'JUSTIFICATION_REQUIRED' || req.headers.has('X-Audit-Justification')) {
        return throwError(() => error);
      }
      const data: ConfirmData = {
        title: 'Justificativa da exclusão',
        message: 'A política do escritório exige uma justificativa para exclusões. Ela fica registrada na auditoria.',
        reasonLabel: 'Motivo da exclusão',
        reasonRequired: true,
        confirmLabel: 'Confirmar exclusão',
        danger: true,
      };
      return dialog.open(ConfirmDialogComponent, { data, width: '480px', maxWidth: '96vw' }).afterClosed().pipe(
        switchMap((reason: string | undefined | false) => {
          if (typeof reason !== 'string' || !reason.trim()) {
            return throwError(() => new HttpErrorResponse({
              status: 400, url: req.url,
              error: { code: JUSTIFICATION_CANCELLED, detail: 'Exclusão cancelada.' },
            }));
          }
          return next(req.clone({ setHeaders: { 'X-Audit-Justification': encodeURIComponent(reason.trim()) } }));
        }),
      );
    }),
  );
};
