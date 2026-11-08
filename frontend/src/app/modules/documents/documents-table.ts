import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subject, debounceTime, firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';
import {
  DocumentMoveDialogComponent, DocumentShareDialogComponent, DocumentUploadDialogComponent, DocumentViewerDialogComponent,
} from './document-dialogs';
import { SignatureDialogComponent } from '../integrations/signature-dialog';
import {
  DOCUMENT_CATEGORY_LABELS, DocumentCategory, DocumentQuery, DocumentsService, FolderNode, StoredDocument, fileExtension, formatBytes,
} from './documents.service';

const ICONS: Record<string, string> = {
  pdf: 'picture_as_pdf', png: 'image', jpg: 'image', jpeg: 'image', doc: 'description', docx: 'description',
  xls: 'table_chart', xlsx: 'table_chart', ppt: 'slideshow', pptx: 'slideshow', txt: 'notes',
};

/** Visão atual da central documental (definida pela barra lateral da página). */
export interface DocumentView {
  folderId?: string | null;
  favorites?: boolean;
  trash?: boolean;
  tag?: string | null;
}

/** Tabela do repositório documental, reutilizada na central, no cliente e no processo. */
@Component({
  selector: 'app-documents-table',
  imports: [FormsModule, RouterLink, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTooltipModule, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="card" [class.dropping]="dragOver()" (dragover)="onDragOver($event)" (dragleave)="dragOver.set(false)" (drop)="onDrop($event)">
      <div class="toolbar">
        @if (!compact()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon>
            <mat-label>Título, descrição, etiqueta, cliente ou nº do processo</mat-label>
            <input matInput [ngModel]="search()" (ngModelChange)="search$.next($event)" />
          </mat-form-field>
        }
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="f">
          <mat-label>Categoria</mat-label>
          <mat-select [ngModel]="category()" (ngModelChange)="category.set($event)">
            <mat-option value="">Todas</mat-option>
            @for (c of categories; track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        @if (!view().trash) {
          <mat-checkbox [ngModel]="includeArchived()" (ngModelChange)="includeArchived.set($event)">Incluir arquivados</mat-checkbox>
        }
        <span class="spacer"></span>
        @if (auth.has('DOCUMENTS_WRITE') && !view().trash) {
          <button mat-flat-button (click)="upload()"><mat-icon>upload_file</mat-icon>Enviar documentos</button>
        }
      </div>
      @if (selected().length) {
        <div class="bulk">
          <strong>{{ selected().length }} selecionado(s)</strong>
          @if (view().trash) {
            @if (auth.has('DOCUMENTS_WRITE')) { <button mat-stroked-button (click)="restoreSelected()"><mat-icon>restore_from_trash</mat-icon>Restaurar</button> }
          } @else if (auth.has('DOCUMENTS_WRITE')) {
            <button mat-stroked-button (click)="moveOrCopy(selected(), 'move')"><mat-icon>drive_file_move</mat-icon>Mover</button>
            <button mat-stroked-button (click)="moveOrCopy(selected(), 'copy')"><mat-icon>file_copy</mat-icon>Copiar</button>
            <button mat-stroked-button (click)="trashSelected()"><mat-icon>delete</mat-icon>Lixeira</button>
          }
          <span class="spacer"></span>
          <button mat-button (click)="selection.set([])">Limpar seleção</button>
        </div>
      }
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (!loading() && items().length === 0) {
        @if (view().trash) {
          <app-empty-state icon="delete_outline" title="Lixeira vazia" text="Documentos excluídos ficam aqui até serem restaurados ou eliminados definitivamente." />
        } @else if (view().favorites) {
          <app-empty-state icon="star_border" title="Nenhum favorito" text="Marque documentos com a estrela para encontrá-los rapidamente aqui." />
        } @else {
          <app-empty-state icon="folder_open" title="Nenhum documento" text="Arraste arquivos para esta área ou use “Enviar documentos” — com versionamento e histórico de acesso." />
        }
      } @else {
        <div class="table-wrap">
          <table class="data stack">
            <thead><tr>
              <th class="sel"><mat-checkbox [checked]="allSelected()" [indeterminate]="selected().length > 0 && !allSelected()" (change)="toggleAll($event.checked)" aria-label="Selecionar todos" /></th>
              <th>Documento</th><th>Categoria</th>@if (!clientId() && !caseId()) { <th>Cliente / processo</th> }<th>Versão</th>
              <th>{{ view().trash ? 'Excluído' : 'Atualizado' }}</th><th></th>
            </tr></thead>
            <tbody>
              @for (d of items(); track d.id) {
                <tr class="clickable" (click)="open(d)" tabindex="0" (keydown.enter)="open(d)" [class.picked]="isSelected(d)">
                  <td class="sel" (click)="$event.stopPropagation()"><mat-checkbox [checked]="isSelected(d)" (change)="toggle(d)" [attr.aria-label]="'Selecionar ' + d.title" /></td>
                  <td data-label="Documento">
                    <div class="doc">
                      <mat-icon class="ft">{{ icon(d) }}</mat-icon>
                      <div class="min0">
                        <div class="strong">{{ d.title }}
                          @if (d.favorite) { <mat-icon class="flag fav" matTooltip="Favorito">star</mat-icon> }
                          @if (d.confidential) { <mat-icon class="flag" matTooltip="Sigiloso">lock</mat-icon> }
                          @if (d.sharedWithClient) { <mat-icon class="flag" matTooltip="Liberado no portal do cliente">share</mat-icon> }
                          @if (d.archived && !d.deleted) { <span class="badge">arquivado</span> }
                        </div>
                        <div class="small">{{ d.fileName }} · {{ size(d.size) }}
                          @if (folderName(d); as f) { · <mat-icon class="mini">folder</mat-icon>{{ f }} }
                        </div>
                        @if (d.tags.length) {
                          <div class="tags">@for (t of d.tags; track t) { <button type="button" class="tag" (click)="$event.stopPropagation(); tagClick.emit(t)">#{{ t }}</button> }</div>
                        }
                      </div>
                    </div>
                  </td>
                  <td data-label="Categoria">{{ d.categoryLabel }}</td>
                  @if (!clientId() && !caseId()) {
                    <td data-label="Cliente / processo" (click)="$event.stopPropagation()">
                      @if (d.clientId) { <a [routerLink]="['/clientes', d.clientId]">{{ d.clientName }}</a> } @else { — }
                      @if (d.caseId) { <div class="small"><a [routerLink]="['/processos', d.caseId]" class="mono">{{ d.caseNumber || 'processo' }}</a></div> }
                    </td>
                  }
                  <td data-label="Versão">v{{ d.currentVersion }}</td>
                  <td [attr.data-label]="view().trash ? 'Excluído' : 'Atualizado'">
                    @if (view().trash) { <div>{{ d.deletedAt | brDate: true }}</div><div class="small">{{ d.deletedByName }}</div> }
                    @else { <div>{{ d.updatedAt | brDate }}</div><div class="small">{{ d.uploadedByName }}</div> }
                  </td>
                  <td class="actions" (click)="$event.stopPropagation()">
                    <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #menu="matMenu" xPosition="before">
                      <button mat-menu-item (click)="open(d)"><mat-icon>visibility</mat-icon>Visualizar, imprimir e versões</button>
                      <button mat-menu-item (click)="download(d)"><mat-icon>download</mat-icon>Baixar</button>
                      @if (d.deleted) {
                        @if (auth.has('DOCUMENTS_WRITE')) { <button mat-menu-item (click)="restore(d)"><mat-icon>restore_from_trash</mat-icon>Restaurar</button> }
                        @if (auth.has('DOCUMENTS_DELETE') && !d.contractId) { <button mat-menu-item class="danger" (click)="purge(d)"><mat-icon>delete_forever</mat-icon>Excluir definitivamente</button> }
                      } @else {
                        <button mat-menu-item (click)="favorite(d)"><mat-icon>{{ d.favorite ? 'star' : 'star_border' }}</mat-icon>{{ d.favorite ? 'Remover dos favoritos' : 'Favoritar' }}</button>
                        <button mat-menu-item (click)="share(d)"><mat-icon>share</mat-icon>Compartilhar com a equipe</button>
                        @if (auth.has('DOCUMENTS_WRITE')) {
                          <button mat-menu-item (click)="edit(d)"><mat-icon>edit</mat-icon>Renomear / editar informações</button>
                          <button mat-menu-item (click)="moveOrCopy([d], 'move')"><mat-icon>drive_file_move</mat-icon>Mover para pasta</button>
                          <button mat-menu-item (click)="moveOrCopy([d], 'copy')"><mat-icon>file_copy</mat-icon>Fazer uma cópia</button>
                          @if (d.contentType === 'application/pdf') { <button mat-menu-item (click)="sign(d)"><mat-icon>draw</mat-icon>Assinatura eletrônica</button> }
                          <button mat-menu-item (click)="toggleArchive(d)"><mat-icon>{{ d.archived ? 'unarchive' : 'archive' }}</mat-icon>{{ d.archived ? 'Desarquivar' : 'Arquivar' }}</button>
                          @if (!d.contractId) { <button mat-menu-item (click)="trash(d)"><mat-icon>delete</mat-icon>Enviar para a lixeira</button> }
                        }
                      }
                    </mat-menu>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
      }
      @if (dragOver()) { <div class="drop-hint"><mat-icon>cloud_upload</mat-icon>Solte para enviar{{ view().folderId ? ' nesta pasta' : '' }}</div> }
    </div>
  `,
  styles: `
    :host { display: block; }
    .card { position: relative; }
    .card.dropping { outline: 2px dashed var(--app-gold); outline-offset: -2px; }
    .drop-hint { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; gap: 8px; font-weight: 600;
      background: color-mix(in srgb, var(--app-surface) 80%, transparent); color: var(--app-gold); border-radius: inherit; pointer-events: none; }
    .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .bulk { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 8px 16px; background: var(--app-gold-soft); border-bottom: 1px solid var(--app-border); }
    .grow { flex: 1 1 280px; }
    .f { width: 200px; }
    .sel { width: 40px; }
    .doc { display: flex; gap: 12px; align-items: center; }
    .min0 { min-width: 0; }
    .ft { color: var(--app-gold); flex: none; }
    .strong { font-weight: 600; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .flag { font-size: 16px; width: 16px; height: 16px; color: var(--app-text-muted); }
    .flag.fav { color: var(--app-gold); }
    .mini { font-size: 13px; width: 13px; height: 13px; vertical-align: -2px; margin-right: 2px; }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 3px; }
    .tag { border: 0; background: var(--app-surface-2); color: var(--app-text-muted); border-radius: 6px; font-size: 11px; padding: 1px 6px; cursor: pointer; }
    .tag:hover { color: var(--app-gold); }
    tr.picked td { background: var(--app-gold-soft); }
    .actions { width: 56px; text-align: right; }
    .more { display: flex; justify-content: center; padding: 16px; }
    .danger { color: var(--app-danger); }
  `,
})
export class DocumentsTableComponent {
  readonly clientId = input<string | null>(null);
  readonly client = input<{ id: string; name: string } | null>(null);
  readonly caseId = input<string | null>(null);
  readonly legalCase = input<{ id: string; label: string } | null>(null);
  readonly compact = input(false);
  readonly view = input<DocumentView>({});
  readonly folders = input<FolderNode[]>([]);
  /** Mudanças que afetam a barra lateral (contagens, pastas). */
  readonly changed = output<void>();
  readonly tagClick = output<string>();

  readonly auth = inject(AuthService);
  private readonly service = inject(DocumentsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  readonly labels = DOCUMENT_CATEGORY_LABELS;
  readonly categories = Object.keys(DOCUMENT_CATEGORY_LABELS) as DocumentCategory[];
  readonly size = formatBytes;
  readonly search = signal('');
  readonly search$ = new Subject<string>();
  readonly category = signal<DocumentCategory | ''>('');
  readonly includeArchived = signal(false);
  readonly items = signal<StoredDocument[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly dragOver = signal(false);
  readonly selection = signal<string[]>([]);
  readonly selected = computed(() => this.items().filter((d) => this.selection().includes(d.id)));
  readonly allSelected = computed(() => this.items().length > 0 && this.selected().length === this.items().length);
  private readonly folderNames = computed(() => new Map(this.folders().map((f) => [f.id, f.path])));

  constructor() {
    // Link direto (ex.: pesquisa global, notificação de compartilhamento): /documentos?doc=<id> abre o visualizador.
    const docId = this.route.snapshot.queryParamMap.get('doc');
    if (docId && !this.clientId() && !this.caseId()) {
      this.service.get(docId).subscribe({ next: (d) => this.open(d), error: () => undefined });
    }
    this.search$.pipe(debounceTime(350), takeUntilDestroyed()).subscribe((v) => this.search.set(v));
    effect(() => {
      this.search();
      this.category();
      this.includeArchived();
      this.clientId();
      this.caseId();
      this.view();
      untracked(() => this.load(false));
    });
  }

  icon(d: StoredDocument): string {
    return ICONS[fileExtension(d.fileName)] ?? 'article';
  }

  folderName(d: StoredDocument): string | null {
    if (!d.folderId || this.view().folderId === d.folderId) return null;
    return this.folderNames().get(d.folderId) ?? null;
  }

  private query(): DocumentQuery {
    const v = this.view();
    return {
      search: this.search().trim() || undefined, category: this.category(), includeArchived: this.includeArchived(),
      clientId: this.clientId() ?? undefined, caseId: this.caseId() ?? undefined,
      folderId: v.folderId ?? undefined, favorites: v.favorites || undefined, trash: v.trash || undefined, tag: v.tag ?? undefined,
    };
  }

  load(more: boolean): void {
    this.loading.set(true);
    if (!more) this.selection.set([]);
    this.service.list(this.query(), more ? this.cursor() : null).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private refresh(): void {
    this.load(false);
    this.changed.emit();
  }

  // ------------------------------------------------------------------ seleção

  isSelected(d: StoredDocument): boolean {
    return this.selection().includes(d.id);
  }

  toggle(d: StoredDocument): void {
    this.selection.update((s) => (s.includes(d.id) ? s.filter((x) => x !== d.id) : [...s, d.id]));
  }

  toggleAll(on: boolean): void {
    this.selection.set(on ? this.items().map((d) => d.id) : []);
  }

  // ------------------------------------------------------------------ envio (inclusive arrastar e soltar)

  onDragOver(event: DragEvent): void {
    if (!this.canDrop(event)) return;
    event.preventDefault();
    this.dragOver.set(true);
  }

  onDrop(event: DragEvent): void {
    if (!this.canDrop(event)) return;
    event.preventDefault();
    this.dragOver.set(false);
    const files = Array.from(event.dataTransfer?.files ?? []);
    if (files.length) this.upload(files);
  }

  private canDrop(event: DragEvent): boolean {
    return this.auth.has('DOCUMENTS_WRITE') && !this.view().trash && !!event.dataTransfer?.types?.includes('Files');
  }

  upload(files?: File[]): void {
    this.dialog.open(DocumentUploadDialogComponent, {
      data: { client: this.client(), legalCase: this.legalCase(), folderId: this.view().folderId ?? null, files },
      width: '820px', maxWidth: '96vw',
    }).afterClosed().subscribe((n) => {
      if (n) { this.toast.success(n === 1 ? 'Documento enviado.' : `${n} documentos enviados.`); this.refresh(); }
    });
  }

  edit(d: StoredDocument): void {
    this.dialog.open(DocumentUploadDialogComponent, { data: { document: d }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe((u) => { if (u) { this.toast.success('Documento atualizado.'); this.refresh(); } });
  }

  open(d: StoredDocument): void {
    this.dialog.open(DocumentViewerDialogComponent, { data: d, width: '1180px', maxWidth: '98vw', autoFocus: false })
      .afterClosed().subscribe(() => this.refresh());
  }

  download(d: StoredDocument): void {
    this.service.content(d.id, true).subscribe((r) => saveBlob(r, d.fileName));
  }

  // ------------------------------------------------------------------ organização

  favorite(d: StoredDocument): void {
    this.service.favorite(d.id, !d.favorite).subscribe(() => {
      this.toast.success(d.favorite ? 'Removido dos favoritos.' : 'Adicionado aos favoritos.');
      this.refresh();
    });
  }

  moveOrCopy(docs: StoredDocument[], mode: 'move' | 'copy'): void {
    this.dialog.open(DocumentMoveDialogComponent, { data: { documents: docs, mode }, width: '520px', maxWidth: '96vw' })
      .afterClosed().subscribe((n) => {
        if (n) { this.toast.success(mode === 'move' ? `${n} documento(s) movido(s).` : `${n} cópia(s) criada(s).`); this.refresh(); }
      });
  }

  sign(d: StoredDocument): void {
    this.dialog.open(SignatureDialogComponent, { data: { documentId: d.id, title: d.title }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe(() => this.refresh());
  }

  share(d: StoredDocument): void {
    this.dialog.open(DocumentShareDialogComponent, { data: d, width: '520px', maxWidth: '96vw' })
      .afterClosed().subscribe((ok) => { if (ok) this.toast.success('Documento compartilhado — a equipe foi notificada.'); });
  }

  toggleArchive(d: StoredDocument): void {
    this.service.setArchived(d.id, !d.archived).subscribe(() => {
      this.toast.success(d.archived ? 'Documento desarquivado.' : 'Documento arquivado (permanece no histórico).');
      this.refresh();
    });
  }

  trash(d: StoredDocument): void {
    this.service.trash(d.id).subscribe(() => {
      this.toast.success('Documento enviado para a lixeira — pode ser restaurado.');
      this.refresh();
    });
  }

  async trashSelected(): Promise<void> {
    const docs = this.selected().filter((d) => !d.contractId);
    let n = 0;
    for (const d of docs) {
      try { await firstValueFrom(this.service.trash(d.id)); n++; } catch { /* erro exibido pelo interceptor */ }
    }
    if (n) this.toast.success(`${n} documento(s) enviado(s) para a lixeira.`);
    if (docs.length < this.selected().length) this.toast.info('Documentos vinculados a contratos não vão para a lixeira.');
    this.refresh();
  }

  restore(d: StoredDocument): void {
    this.service.restore(d.id).subscribe(() => { this.toast.success('Documento restaurado.'); this.refresh(); });
  }

  async restoreSelected(): Promise<void> {
    let n = 0;
    for (const d of this.selected()) {
      try { await firstValueFrom(this.service.restore(d.id)); n++; } catch { /* erro exibido pelo interceptor */ }
    }
    if (n) this.toast.success(`${n} documento(s) restaurado(s).`);
    this.refresh();
  }

  purge(d: StoredDocument): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Excluir definitivamente', danger: true, confirmLabel: 'Excluir definitivamente', reasonLabel: 'Motivo', reasonRequired: true,
        message: `“${d.title}” e todas as ${d.currentVersion} versão(ões) serão apagados do armazenamento. Esta ação não pode ser desfeita; o registro da exclusão permanece na auditoria.`,
      },
      width: '480px',
    }).afterClosed().subscribe((reason) => {
      if (typeof reason !== 'string' || !reason.trim()) return;
      this.service.purge(d.id, reason.trim()).subscribe(() => { this.toast.success('Documento excluído definitivamente.'); this.refresh(); });
    });
  }
}
