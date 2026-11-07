import { Component, effect, inject, input, signal, untracked } from '@angular/core';
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
import { RouterLink } from '@angular/router';
import { Subject, debounceTime } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';
import { DocumentUploadDialogComponent, DocumentViewerDialogComponent } from './document-dialogs';
import { DOCUMENT_CATEGORY_LABELS, DocumentCategory, DocumentsService, StoredDocument, formatBytes } from './documents.service';

const ICONS: Record<string, string> = {
  'application/pdf': 'picture_as_pdf',
  'image/png': 'image',
  'image/jpeg': 'image',
};

/** Tabela do repositório documental, reutilizada na página geral, no cliente e no processo. */
@Component({
  selector: 'app-documents-table',
  imports: [FormsModule, RouterLink, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTooltipModule, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="card">
      <div class="toolbar">
        @if (!compact()) {
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon>
            <mat-label>Título, descrição, cliente ou nº do processo</mat-label>
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
        <mat-checkbox [ngModel]="includeArchived()" (ngModelChange)="includeArchived.set($event)">Incluir arquivados</mat-checkbox>
        <span class="spacer"></span>
        @if (auth.has('DOCUMENTS_WRITE')) {
          <button mat-flat-button (click)="upload()"><mat-icon>upload_file</mat-icon>Enviar documento</button>
        }
      </div>
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      @if (!loading() && items().length === 0) {
        <app-empty-state icon="folder_open" title="Nenhum documento" text="Envie procurações, petições, decisões e demais arquivos — com versionamento e histórico de acesso." />
      } @else {
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Documento</th><th>Categoria</th>@if (!clientId()) { <th>Cliente / processo</th> }<th>Versão</th><th>Atualizado</th><th></th></tr></thead>
            <tbody>
              @for (d of items(); track d.id) {
                <tr class="clickable" (click)="open(d)" tabindex="0" (keydown.enter)="open(d)">
                  <td>
                    <div class="doc">
                      <mat-icon class="ft">{{ icon(d) }}</mat-icon>
                      <div>
                        <div class="strong">{{ d.title }}
                          @if (d.confidential) { <mat-icon class="flag" matTooltip="Sigiloso">lock</mat-icon> }
                          @if (d.sharedWithClient) { <mat-icon class="flag" matTooltip="Liberado no portal do cliente">share</mat-icon> }
                          @if (d.archived) { <span class="badge">arquivado</span> }
                        </div>
                        <div class="small">{{ d.fileName }} · {{ size(d.size) }}</div>
                      </div>
                    </div>
                  </td>
                  <td>{{ d.categoryLabel }}</td>
                  @if (!clientId()) {
                    <td (click)="$event.stopPropagation()">
                      @if (d.clientId) { <a [routerLink]="['/clientes', d.clientId]">{{ d.clientName }}</a> } @else { — }
                      @if (d.caseId) { <div class="small"><a [routerLink]="['/processos', d.caseId]" class="mono">{{ d.caseNumber || 'processo' }}</a></div> }
                    </td>
                  }
                  <td>v{{ d.currentVersion }}</td>
                  <td><div>{{ d.updatedAt | brDate }}</div><div class="small">{{ d.uploadedByName }}</div></td>
                  <td class="actions" (click)="$event.stopPropagation()">
                    <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #menu="matMenu" xPosition="before">
                      <button mat-menu-item (click)="open(d)"><mat-icon>visibility</mat-icon>Visualizar, versões e acessos</button>
                      <button mat-menu-item (click)="download(d)"><mat-icon>download</mat-icon>Baixar</button>
                      @if (auth.has('DOCUMENTS_WRITE')) {
                        <button mat-menu-item (click)="edit(d)"><mat-icon>edit</mat-icon>Editar informações</button>
                        <button mat-menu-item (click)="toggleArchive(d)"><mat-icon>{{ d.archived ? 'unarchive' : 'archive' }}</mat-icon>{{ d.archived ? 'Desarquivar' : 'Arquivar' }}</button>
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
    </div>
  `,
  styles: `
    .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .grow { flex: 1 1 280px; }
    .f { width: 200px; }
    .doc { display: flex; gap: 12px; align-items: center; }
    .ft { color: var(--app-gold); }
    .strong { font-weight: 600; display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .flag { font-size: 16px; width: 16px; height: 16px; color: var(--app-text-muted); }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .actions { width: 56px; text-align: right; }
    .more { display: flex; justify-content: center; padding: 16px; }
  `,
})
export class DocumentsTableComponent {
  readonly clientId = input<string | null>(null);
  readonly client = input<{ id: string; name: string } | null>(null);
  readonly caseId = input<string | null>(null);
  readonly legalCase = input<{ id: string; label: string } | null>(null);
  readonly compact = input(false);

  readonly auth = inject(AuthService);
  private readonly service = inject(DocumentsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
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

  constructor() {
    this.search$.pipe(debounceTime(350), takeUntilDestroyed()).subscribe((v) => this.search.set(v));
    effect(() => {
      this.search();
      this.category();
      this.includeArchived();
      this.clientId();
      this.caseId();
      untracked(() => this.load(false));
    });
  }

  icon(d: StoredDocument): string {
    return ICONS[d.contentType] ?? 'article';
  }

  load(more: boolean): void {
    this.loading.set(true);
    this.service.list({
      search: this.search().trim() || undefined, category: this.category(), includeArchived: this.includeArchived(),
      clientId: this.clientId() ?? undefined, caseId: this.caseId() ?? undefined,
    }, more ? this.cursor() : null).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  upload(): void {
    this.dialog.open(DocumentUploadDialogComponent, {
      data: { client: this.client(), legalCase: this.legalCase() }, width: '760px', maxWidth: '96vw',
    }).afterClosed().subscribe((d) => {
      if (d) { this.toast.success('Documento enviado.'); this.load(false); }
    });
  }

  edit(d: StoredDocument): void {
    this.dialog.open(DocumentUploadDialogComponent, { data: { document: d }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe((u) => { if (u) { this.toast.success('Documento atualizado.'); this.load(false); } });
  }

  open(d: StoredDocument): void {
    this.dialog.open(DocumentViewerDialogComponent, { data: d, width: '1100px', maxWidth: '96vw' })
      .afterClosed().subscribe(() => this.load(false));
  }

  download(d: StoredDocument): void {
    this.service.content(d.id, true).subscribe((r) => saveBlob(r, d.fileName));
  }

  toggleArchive(d: StoredDocument): void {
    this.service.setArchived(d.id, !d.archived).subscribe(() => {
      this.toast.success(d.archived ? 'Documento desarquivado.' : 'Documento arquivado (permanece no histórico).');
      this.load(false);
    });
  }
}
