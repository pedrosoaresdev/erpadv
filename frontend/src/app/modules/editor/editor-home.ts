import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, debounceTime } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { LegalTemplateDialogComponent, NewLegalDocumentDialogComponent } from './editor-dialogs';
import { LetterheadDialogComponent } from './letterhead-dialog';
import { Draft, DraftStatus, DraftSummary, LegalEditorService, LegalTemplate } from './legal-editor.service';

/** Editor Jurídico: documentos (rascunhos e finais) e biblioteca de modelos. */
@Component({
  selector: 'app-editor-home',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTabsModule,
    MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão documental" title="Editor Jurídico"
        subtitle="Peças e documentos a partir de modelos, com dados do cliente e do processo preenchidos automaticamente, histórico de versões e exportação em PDF/DOCX.">
        <button mat-stroked-button (click)="letterheads()"><mat-icon>description</mat-icon>Papel timbrado</button>
        @if (canWrite) { <button mat-flat-button (click)="newDocument()"><mat-icon>note_add</mat-icon>Novo documento</button> }
      </app-page-header>

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="tab.set($event)">
        <mat-tab label="Documentos">
          <div class="card">
            <div class="toolbar">
              <mat-button-toggle-group [value]="status()" (change)="status.set($event.value); load()" aria-label="Situação">
                <mat-button-toggle value="">Todos</mat-button-toggle>
                <mat-button-toggle value="RASCUNHO">Rascunhos</mat-button-toggle>
                <mat-button-toggle value="FINAL">Finalizados</mat-button-toggle>
              </mat-button-toggle-group>
              <div class="search"><mat-icon>search</mat-icon>
                <input type="search" placeholder="Título, tipo, cliente ou nº do processo" [ngModel]="search()" (ngModelChange)="search$.next($event)" aria-label="Buscar documentos" /></div>
              @if (clientId || caseId) { <a mat-button routerLink="/editor-juridico"><mat-icon>close</mat-icon>Remover filtro de {{ clientId ? 'cliente' : 'processo' }}</a> }
            </div>
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (!loading() && items().length === 0) {
              <app-empty-state icon="edit_document" title="Nenhum documento" text="Crie uma procuração, petição, contrato ou parecer a partir dos modelos." />
            } @else {
              <div class="table-wrap">
                <table class="data stack">
                  <thead><tr><th>Documento</th><th>Tipo</th><th>Cliente / processo</th><th>Situação</th><th>Atualizado</th><th></th></tr></thead>
                  <tbody>
                    @for (d of items(); track d.id) {
                      <tr class="clickable" (click)="open(d)" tabindex="0" (keydown.enter)="open(d)">
                        <td data-label="Documento"><strong>{{ d.title }}</strong><div class="small">v{{ d.version }}{{ d.lawyerName ? ' · ' + d.lawyerName : '' }}</div></td>
                        <td data-label="Tipo">{{ d.kindLabel }}</td>
                        <td data-label="Cliente / processo">{{ d.clientName || '—' }}@if (d.caseNumber) { <div class="small mono">{{ d.caseNumber }}</div> }</td>
                        <td data-label="Situação"><span class="badge" [class.ok]="d.status === 'FINAL'">{{ d.status === 'FINAL' ? 'Final' : 'Rascunho' }}</span>
                          @if (d.storedDocumentId) { <mat-icon class="flag" matTooltip="Salvo na central de documentos">inventory_2</mat-icon> }</td>
                        <td data-label="Atualizado"><div>{{ d.updatedAt | brDate: true }}</div><div class="small">{{ d.updatedByName }}</div></td>
                        <td class="actions" (click)="$event.stopPropagation()">
                          <button mat-icon-button [matMenuTriggerFor]="m" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                          <mat-menu #m="matMenu" xPosition="before">
                            <button mat-menu-item (click)="open(d)"><mat-icon>edit_document</mat-icon>{{ d.status === 'FINAL' || !canWrite ? 'Abrir' : 'Editar' }}</button>
                            @if (canWrite) {
                              <button mat-menu-item (click)="duplicate(d)"><mat-icon>content_copy</mat-icon>Duplicar</button>
                              @if (d.status === 'RASCUNHO') { <button mat-menu-item class="danger" (click)="remove(d)"><mat-icon>delete</mat-icon>Excluir rascunho</button> }
                            }
                          </mat-menu>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </mat-tab>
        <mat-tab label="Modelos">
          <div class="tpl-head">
            <p class="muted">Modelos do sistema são somente leitura — duplique para adaptar ao escritório. Modelos do escritório ficam disponíveis para toda a equipe.</p>
            @if (canWrite) { <button mat-stroked-button (click)="editTemplate(null)"><mat-icon>bookmark_add</mat-icon>Novo modelo</button> }
          </div>
          <div class="grid">
            @for (t of templates(); track t.id) {
              <div class="card tpl">
                <div class="tpl-top"><mat-icon>{{ t.system ? 'description' : 'bookmark' }}</mat-icon><span class="badge" [class.mine]="!t.system">{{ t.system ? 'Sistema' : 'Escritório' }}</span></div>
                <strong>{{ t.name }}</strong>
                <span class="muted small">{{ t.kindLabel }}{{ t.description ? ' — ' + t.description : '' }}</span>
                @if (!t.system && t.createdByName) { <span class="muted small">por {{ t.createdByName }}{{ t.updatedAt ? ' · ' : '' }}{{ t.updatedAt | brDate }}</span> }
                <div class="tpl-actions">
                  @if (canWrite) {
                    <button mat-flat-button (click)="newDocument(t.id)">Usar</button>
                    <button mat-button (click)="editTemplate(t)">{{ t.system ? 'Duplicar' : 'Editar' }}</button>
                    @if (!t.system) { <button mat-icon-button (click)="deleteTemplate(t)" aria-label="Excluir modelo" matTooltip="Excluir modelo"><mat-icon>delete</mat-icon></button> }
                  }
                </div>
              </div>
            }
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .search { flex: 1 1 240px; display: flex; align-items: center; gap: 6px; border: 1px solid var(--app-border); border-radius: 10px; padding: 0 10px; height: 38px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .badge.ok { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .badge.mine { background: var(--app-gold-soft); }
    .flag { font-size: 16px; width: 16px; height: 16px; color: var(--app-text-muted); vertical-align: middle; margin-left: 4px; }
    .actions { width: 56px; text-align: right; }
    .danger { color: var(--app-danger); }
    .tpl-head { display: flex; gap: 12px; align-items: center; justify-content: space-between; flex-wrap: wrap; margin: 12px 0; }
    .tpl-head p { margin: 0; flex: 1 1 320px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
    .tpl { padding: 14px; display: flex; flex-direction: column; gap: 6px; }
    .tpl-top { display: flex; align-items: center; justify-content: space-between; }
    .tpl-top mat-icon { color: var(--app-gold); }
    .tpl-actions { display: flex; gap: 6px; align-items: center; margin-top: auto; padding-top: 8px; }
  `,
})
export class EditorHomeComponent {
  private readonly service = inject(LegalEditorService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');
  readonly clientId = this.route.snapshot.queryParamMap.get('clientId');
  readonly caseId = this.route.snapshot.queryParamMap.get('caseId');
  readonly tab = signal(this.route.snapshot.queryParamMap.get('aba') === 'modelos' ? 1 : 0);
  readonly status = signal<DraftStatus | ''>('');
  readonly search = signal('');
  readonly search$ = new Subject<string>();
  readonly items = signal<DraftSummary[]>([]);
  readonly templates = signal<LegalTemplate[]>([]);
  readonly loading = signal(false);
  readonly hasTemplates = computed(() => this.templates().length > 0);

  constructor() {
    this.search$.pipe(debounceTime(350), takeUntilDestroyed()).subscribe((v) => { this.search.set(v); this.load(); });
    this.load();
    this.loadTemplates();
  }

  load(): void {
    this.loading.set(true);
    this.service.list({ status: this.status(), search: this.search().trim(), clientId: this.clientId, caseId: this.caseId }).subscribe({
      next: (list) => { this.items.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  loadTemplates(): void {
    this.service.templates().subscribe((t) => this.templates.set(t));
  }

  newDocument(templateId?: string): void {
    const q = this.route.snapshot.queryParamMap;
    this.dialog.open(NewLegalDocumentDialogComponent, {
      data: {
        templates: this.templates(), templateId: templateId ?? null,
        client: this.clientId ? { id: this.clientId, name: q.get('clientName') ?? 'Cliente selecionado' } : null,
        legalCase: this.caseId ? { id: this.caseId, label: q.get('caseLabel') ?? 'Processo selecionado' } : null,
      },
      width: '860px', maxWidth: '96vw',
    }).afterClosed().subscribe((d?: Draft) => { if (d) void this.router.navigate(['/editor-juridico', d.id]); });
  }

  open(d: DraftSummary): void {
    void this.router.navigate(['/editor-juridico', d.id]);
  }

  duplicate(d: DraftSummary): void {
    this.service.duplicate(d.id).subscribe((copy) => {
      this.toast.success('Documento duplicado.');
      void this.router.navigate(['/editor-juridico', copy.id]);
    });
  }

  remove(d: DraftSummary): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir rascunho', message: `Excluir “${d.title}” e suas ${d.version} versão(ões)? Esta ação não pode ser desfeita.`, confirmLabel: 'Excluir', danger: true },
      width: '440px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.delete(d.id).subscribe(() => { this.toast.success('Rascunho excluído.'); this.load(); });
    });
  }

  letterheads(): void {
    this.dialog.open(LetterheadDialogComponent, { width: '960px', maxWidth: '98vw' });
  }

  editTemplate(t: LegalTemplate | null): void {
    this.dialog.open(LegalTemplateDialogComponent, { data: { template: t }, width: '1000px', maxWidth: '98vw' })
      .afterClosed().subscribe((saved) => { if (saved) { this.toast.success('Modelo salvo.'); this.loadTemplates(); } });
  }

  deleteTemplate(t: LegalTemplate): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir modelo', message: `Excluir o modelo “${t.name}”? Documentos já criados não são afetados.`, confirmLabel: 'Excluir', danger: true },
      width: '440px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.deleteTemplate(t.id).subscribe(() => { this.toast.success('Modelo excluído.'); this.loadTemplates(); });
    });
  }
}
