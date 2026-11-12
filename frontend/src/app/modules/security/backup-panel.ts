import { HttpResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApiService } from '../../core/api/api.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';

interface Backup {
  id: string;
  trigger: 'AUTOMATICO' | 'MANUAL';
  status: 'EM_ANDAMENTO' | 'CONCLUIDO' | 'FALHOU';
  createdByName: string | null;
  createdAt: string;
  completedAt: string | null;
  sizeBytes: number;
  sha256: string | null;
  documents: number;
  collections: Record<string, number>;
  error: string | null;
}

interface CollectionDiff { collection: string; inBackup: number; missingNow: number; restorable: boolean }
interface Inspection { backupId: string; createdAt: string; collections: CollectionDiff[] }

const COLLECTION_LABELS: Record<string, string> = {
  clients: 'Clientes', 'clients/interactions': 'Atendimentos de clientes', cases: 'Processos', case_movements: 'Movimentações',
  deadlines: 'Prazos', appointments: 'Agenda', documents: 'Documentos (metadados)', document_versions: 'Versões de documentos',
  document_folders: 'Pastas', contracts: 'Contratos', financial_transactions: 'Lançamentos financeiros', leads: 'CRM (leads)',
  boards: 'Quadros', board_cards: 'Cartões', legal_documents: 'Editor jurídico', communications: 'Comunicação', publications: 'Publicações DJEN',
  audit_logs: 'Auditoria (imutável)', audit_meta: 'Auditoria — cadeia', '@users': 'Perfis da equipe', '@office': 'Dados do escritório',
  security_events: 'Eventos de segurança', notifications: 'Notificações',
};

/** Backups do escritório: geração manual, histórico, download, comparação com o banco atual e recuperação não destrutiva. */
@Component({
  selector: 'app-backup-panel',
  imports: [FormsModule, MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, EmptyStateComponent, BrDatePipe],
  template: `
    <section class="card">
      <div class="head">
        <div>
          <h3><mat-icon>backup</mat-icon>Backups do escritório</h3>
          <p class="muted small">Cópia de todos os registros, compactada e cifrada (AES-256), gerada todos os dias às 02:30 e mantida pelo prazo
            definido na política. Os arquivos da central documental ficam no armazenamento com versões.</p>
        </div>
        <span class="spacer"></span>
        <button mat-flat-button (click)="create()" [disabled]="busy()"><mat-icon>add_circle</mat-icon>Gerar backup agora</button>
      </div>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      @if (items().length === 0 && !busy()) { <app-empty-state icon="backup" title="Nenhum backup ainda" text="O primeiro backup automático é gerado na próxima madrugada." /> } @else {
        <div class="table-wrap">
          <table class="data stack">
            <thead><tr><th>Data</th><th>Origem</th><th>Situação</th><th>Registros</th><th>Tamanho</th><th></th></tr></thead>
            <tbody>
              @for (b of items(); track b.id) {
                <tr>
                  <td data-label="Data" class="nowrap">{{ b.createdAt | brDate: true }}</td>
                  <td data-label="Origem">{{ b.trigger === 'MANUAL' ? 'Manual · ' + (b.createdByName || '') : 'Automático' }}</td>
                  <td data-label="Situação">
                    <span class="badge" [class.success]="b.status === 'CONCLUIDO'" [class.danger]="b.status === 'FALHOU'">{{ statusLabel(b) }}</span>
                    @if (b.error) { <div class="muted small">{{ b.error }}</div> }
                  </td>
                  <td data-label="Registros">{{ b.documents }}</td>
                  <td data-label="Tamanho">{{ size(b.sizeBytes) }}</td>
                  <td class="actions">
                    @if (b.status === 'CONCLUIDO') {
                      <button mat-button (click)="inspect(b)"><mat-icon>manage_search</mat-icon>Comparar</button>
                      <button mat-button (click)="download(b)"><mat-icon>download</mat-icon>Baixar</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    @if (inspection(); as i) {
      <section class="card pad">
        <div class="head"><h3><mat-icon>restore</mat-icon>Recuperação a partir do backup de {{ i.createdAt | brDate: true }}</h3><span class="spacer"></span>
          <button mat-icon-button (click)="inspection.set(null)" aria-label="Fechar"><mat-icon>close</mat-icon></button></div>
        <p class="muted small">A recuperação recria somente os registros que <strong>não existem mais</strong> no sistema. Nada do que existe hoje é
          alterado ou sobrescrito. A auditoria e os perfis da equipe não são recriados por aqui.</p>
        <div class="table-wrap">
          <table class="data stack">
            <thead><tr><th></th><th>Coleção</th><th>No backup</th><th>Ausentes hoje</th></tr></thead>
            <tbody>
              @for (c of i.collections; track c.collection) {
                <tr>
                  <td><mat-checkbox [disabled]="!c.restorable || c.missingNow === 0" [(ngModel)]="selected[c.collection]" [attr.aria-label]="label(c.collection)" /></td>
                  <td data-label="Coleção">{{ label(c.collection) }}</td>
                  <td data-label="No backup">{{ c.inBackup }}</td>
                  <td data-label="Ausentes hoje"><strong [class.warn]="c.missingNow > 0">{{ c.missingNow }}</strong></td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <div class="row"><span class="spacer"></span>
          <button mat-flat-button (click)="restore(i)" [disabled]="busy() || !anySelected()"><mat-icon>settings_backup_restore</mat-icon>Recuperar selecionados</button></div>
      </section>
    }
  `,
  styles: `
    .card { margin-top: 16px; }
    .head { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; padding: 16px 20px; }
    .pad .head { padding: 0 0 8px; }
    .pad { padding: 16px 20px; }
    h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 4px; font-size: 16px; }
    .small { font-size: 12px; max-width: 720px; }
    .nowrap { white-space: nowrap; }
    .actions { white-space: nowrap; text-align: right; }
    .row { display: flex; gap: 8px; margin-top: 12px; }
    .warn { color: var(--app-warning, #b26a00); }
    .badge.success { background: var(--app-success-bg); color: var(--app-success); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
  `,
})
export class BackupPanelComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  readonly items = signal<Backup[]>([]);
  readonly busy = signal(false);
  readonly inspection = signal<Inspection | null>(null);
  selected: Record<string, boolean> = {};

  constructor() {
    this.load();
  }

  load(): void {
    this.api.get<Backup[]>('/security/backups').subscribe((l) => this.items.set(l));
  }

  label(c: string): string {
    return COLLECTION_LABELS[c] ?? c;
  }

  statusLabel(b: Backup): string {
    return b.status === 'CONCLUIDO' ? 'Concluído' : b.status === 'FALHOU' ? 'Falhou' : 'Em andamento';
  }

  size(bytes: number): string {
    return bytes >= 1048576 ? (bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(bytes / 1024)) + ' KB';
  }

  anySelected(): boolean {
    return Object.values(this.selected).some(Boolean);
  }

  create(): void {
    this.busy.set(true);
    this.api.post<Backup>('/security/backups', {}).subscribe({
      next: (b) => { this.busy.set(false); this.toast.success(`Backup gerado: ${b.documents} registro(s).`); this.load(); },
      error: () => { this.busy.set(false); this.load(); },
    });
  }

  download(b: Backup): void {
    this.api.getBlob(`/security/backups/${b.id}/download`).subscribe((r: HttpResponse<Blob>) => saveBlob(r, `erpadv-backup-${b.id}.jsonl.gz`));
  }

  inspect(b: Backup): void {
    this.busy.set(true);
    this.selected = {};
    this.api.get<Inspection>(`/security/backups/${b.id}/inspect`).subscribe({
      next: (i) => { this.busy.set(false); this.inspection.set(i); },
      error: () => this.busy.set(false),
    });
  }

  restore(i: Inspection): void {
    const collections = Object.entries(this.selected).filter(([, v]) => v).map(([k]) => k);
    const total = i.collections.filter((c) => collections.includes(c.collection)).reduce((s, c) => s + c.missingNow, 0);
    const data: ConfirmData = { title: 'Recuperar registros', danger: true, confirmLabel: 'Recuperar',
      message: `Recriar ${total} registro(s) ausente(s) de: ${collections.map((c) => this.label(c)).join(', ')}? A operação fica registrada na auditoria.` };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.busy.set(true);
      this.api.post<{ total: number }>(`/security/backups/${i.backupId}/restore`, { collections }).subscribe({
        next: (r) => {
          this.busy.set(false);
          this.toast.success(`${r.total} registro(s) recuperado(s).`);
          this.inspect({ id: i.backupId } as Backup);
        },
        error: () => this.busy.set(false),
      });
    });
  }
}
