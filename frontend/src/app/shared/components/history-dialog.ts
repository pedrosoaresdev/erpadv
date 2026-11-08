import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Observable } from 'rxjs';
import { AuditLog, CursorPage } from '../../core/models/models';
import { BrDatePipe } from '../pipes/format.pipes';

export interface HistoryDialogData {
  title: string;
  load: () => Observable<CursorPage<AuditLog>>;
}

/** Histórico de alterações de um registro (trilha de auditoria), com valores anteriores → novos. */
@Component({
  selector: 'app-history-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatProgressBarModule, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>history</mat-icon>{{ data.title }}</h2>
    <mat-dialog-content>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @for (h of items(); track h.id) {
        <div class="entry">
          <div class="head"><strong>{{ h.summary }}</strong><span class="muted small">{{ h.at | brDate: true }}</span></div>
          <div class="muted small">{{ h.actorEmail || h.actorUid }}{{ h.ip ? ' · ' + h.ip : '' }}</div>
          @if (detailKeys(h).length) {
            <dl>
              @for (k of detailKeys(h); track k) {
                <dt>{{ label(k) }}</dt><dd>{{ pretty(h.details[k]) }}</dd>
              }
            </dl>
          }
        </div>
      } @empty {
        @if (!loading()) { <p class="muted">Nenhum registro.</p> }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-flat-button mat-dialog-close>Fechar</button></mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { min-width: min(560px, 86vw); max-height: 70vh; }
    .entry { padding: 10px 0; border-bottom: 1px solid var(--app-border); }
    .head { display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }
    .small { font-size: 12px; }
    dl { display: grid; grid-template-columns: 130px 1fr; gap: 4px 10px; margin: 8px 0 0; font-size: 13px; }
    dt { color: var(--app-text-muted); }
    dd { margin: 0; overflow-wrap: anywhere; }
  `,
})
export class HistoryDialogComponent {
  readonly data = inject<HistoryDialogData>(MAT_DIALOG_DATA);
  readonly items = signal<AuditLog[]>([]);
  readonly loading = signal(true);
  private static readonly LABELS: Record<string, string> = {
    dueDate: 'Vencimento', responsible: 'Responsável', description: 'Descrição', reason: 'Motivo', fields: 'Campos',
    status: 'Situação', title: 'Título', start: 'Início', folder: 'Pasta', name: 'Nome', version: 'Versão', format: 'Formato',
  };

  constructor() {
    this.data.load().subscribe({
      next: (p) => { this.items.set(p.items); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  detailKeys(h: AuditLog): string[] {
    return Object.keys(h.details ?? {}).filter((k) => h.details[k] !== '' && k !== 'sha256');
  }

  label(k: string): string {
    return HistoryDialogComponent.LABELS[k] ?? k;
  }

  /** "a->b" vira "a → b" (alteração anterior → posterior). */
  pretty(v: string): string {
    return (v ?? '').replace('->', ' → ');
  }
}
