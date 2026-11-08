import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { PageHeaderComponent } from '../../shared/components/ui';
import { FolderDialogComponent } from './document-dialogs';
import { DocumentView, DocumentsTableComponent } from './documents-table';
import { DocumentsService, FolderNode, folderTree } from './documents.service';

type Section = 'all' | 'favorites' | 'trash' | 'folder' | 'tag';

/** Central documental: pastas e subpastas, favoritos, etiquetas e lixeira, com o repositório ao lado. */
@Component({
  selector: 'app-documents-page',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, PageHeaderComponent, DocumentsTableComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão documental" title="Central de documentos"
        subtitle="Pastas, etiquetas, favoritos e lixeira — com versionamento, controle de sigilo e histórico de quem visualizou ou baixou cada arquivo." />
      <div class="layout">
        <nav class="side card" aria-label="Organização dos documentos">
          <button type="button" class="nav" [class.on]="section() === 'all'" (click)="go('all')"><mat-icon>folder_copy</mat-icon>Todos os documentos</button>
          <button type="button" class="nav" [class.on]="section() === 'favorites'" (click)="go('favorites')"><mat-icon>star</mat-icon>Favoritos</button>
          <button type="button" class="nav" [class.on]="section() === 'trash'" (click)="go('trash')"><mat-icon>delete</mat-icon>Lixeira</button>
          <div class="head">
            <span>Pastas</span>
            @if (canWrite) { <button mat-icon-button (click)="newFolder(null)" matTooltip="Nova pasta" aria-label="Nova pasta"><mat-icon>create_new_folder</mat-icon></button> }
          </div>
          @if (tree().length === 0) { <p class="muted small pad">Nenhuma pasta. Crie pastas para organizar por assunto, cliente ou tipo.</p> }
          @for (f of visibleTree(); track f.id) {
            <div class="frow" [class.on]="section() === 'folder' && folderId() === f.id" [style.padding-left.px]="6 + f.depth * 14">
              @if (hasChildren(f.id)) {
                <button type="button" class="tw" (click)="toggleExpand(f.id)" [attr.aria-label]="(expanded().has(f.id) ? 'Recolher ' : 'Expandir ') + f.name"
                  [attr.aria-expanded]="expanded().has(f.id)"><mat-icon>{{ expanded().has(f.id) ? 'expand_more' : 'chevron_right' }}</mat-icon></button>
              } @else { <span class="tw"></span> }
              <button type="button" class="fname" (click)="openFolder(f.id)"><mat-icon [style.color]="f.color || null">{{ section() === 'folder' && folderId() === f.id ? 'folder_open' : 'folder' }}</mat-icon><span>{{ f.name }}</span></button>
              @if (canWrite) {
                <button mat-icon-button class="fmenu" [matMenuTriggerFor]="fm" [attr.aria-label]="'Ações da pasta ' + f.name"><mat-icon>more_horiz</mat-icon></button>
                <mat-menu #fm="matMenu">
                  <button mat-menu-item (click)="newFolder(f.id)"><mat-icon>create_new_folder</mat-icon>Nova subpasta</button>
                  <button mat-menu-item (click)="editFolder(f)"><mat-icon>edit</mat-icon>Renomear / mover</button>
                  <button mat-menu-item (click)="deleteFolder(f)"><mat-icon>delete</mat-icon>Excluir pasta</button>
                </mat-menu>
              }
            </div>
          }
        </nav>

        <section class="main">
          <div class="crumbs" aria-label="Local atual">
            @switch (section()) {
              @case ('favorites') { <mat-icon>star</mat-icon><strong>Favoritos</strong><span class="muted small">— somente os seus</span> }
              @case ('trash') { <mat-icon>delete</mat-icon><strong>Lixeira</strong><span class="muted small">— restaure ou elimine definitivamente</span> }
              @case ('tag') { <mat-icon>sell</mat-icon><strong>#{{ tag() }}</strong><button mat-button (click)="go('all')"><mat-icon>close</mat-icon>Limpar etiqueta</button> }
              @case ('folder') {
                <button type="button" class="crumb" (click)="go('all')"><mat-icon>home</mat-icon>Documentos</button>
                @for (c of breadcrumb(); track c.id; let last = $last) {
                  <mat-icon class="sep">chevron_right</mat-icon>
                  @if (last) { <strong>{{ c.name }}</strong> } @else { <button type="button" class="crumb" (click)="openFolder(c.id)">{{ c.name }}</button> }
                }
              }
              @default { <mat-icon>folder_copy</mat-icon><strong>Todos os documentos</strong> }
            }
          </div>
          @if (section() === 'folder' && children().length) {
            <div class="subfolders">
              @for (c of children(); track c.id) {
                <button type="button" class="sub card" (click)="openFolder(c.id)"><mat-icon [style.color]="c.color || null">folder</mat-icon>{{ c.name }}</button>
              }
            </div>
          }
          <app-documents-table [view]="view()" [folders]="tree()" (changed)="loadFolders()" (tagClick)="openTag($event)" />
        </section>
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 16px; align-items: start; }
    .side { padding: 8px; position: sticky; top: 76px; max-height: calc(100vh - 100px); overflow: auto; }
    .nav, .fname { display: flex; align-items: center; gap: 10px; width: 100%; border: 0; background: none; color: var(--app-text); font: inherit;
      padding: 8px 10px; border-radius: 8px; cursor: pointer; text-align: left; }
    .nav:hover, .frow:hover { background: var(--app-surface-2); }
    .nav.on, .frow.on { background: var(--app-gold-soft); font-weight: 600; }
    .nav mat-icon, .fname mat-icon { color: var(--app-gold); flex: none; }
    .head { display: flex; align-items: center; justify-content: space-between; padding: 12px 10px 4px; font-size: 12px; font-weight: 700;
      text-transform: uppercase; letter-spacing: .06em; color: var(--app-text-muted); border-top: 1px solid var(--app-border); margin-top: 8px; }
    .frow { display: flex; align-items: center; border-radius: 8px; }
    .frow .fname { padding: 6px 4px; min-width: 0; flex: 1; }
    .frow .fname span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .tw { width: 24px; height: 24px; flex: none; border: 0; background: none; color: var(--app-text-muted); cursor: pointer; padding: 0; display: grid; place-items: center; }
    .tw mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .fmenu { opacity: .55; }
    .frow:hover .fmenu, .frow.on .fmenu { opacity: 1; }
    .pad { padding: 4px 10px; }
    .small { font-size: 12px; }
    .crumbs { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; min-height: 36px; }
    .crumbs > mat-icon { color: var(--app-gold); }
    .crumb { border: 0; background: none; color: var(--app-text-muted); font: inherit; cursor: pointer; display: inline-flex; align-items: center; gap: 4px; padding: 4px; border-radius: 6px; }
    .crumb:hover { color: var(--app-text); background: var(--app-surface-2); }
    .sep { color: var(--app-text-muted); font-size: 18px; width: 18px; height: 18px; }
    .subfolders { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 10px; margin-bottom: 14px; }
    .sub { display: flex; align-items: center; gap: 8px; padding: 12px; border: 1px solid var(--app-border); cursor: pointer; font: inherit; color: var(--app-text); text-align: left; }
    .sub mat-icon { color: var(--app-gold); }
    .sub:hover { border-color: var(--app-gold); }
    @media (max-width: 900px) {
      .layout { grid-template-columns: 1fr; }
      .side { position: static; max-height: 260px; }
    }
  `,
})
export class DocumentsPageComponent {
  private readonly service = inject(DocumentsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');

  readonly tree = signal<FolderNode[]>([]);
  readonly section = signal<Section>('all');
  readonly folderId = signal<string | null>(null);
  readonly tag = signal<string | null>(null);
  readonly expanded = signal<Set<string>>(new Set());

  readonly view = computed<DocumentView>(() => {
    switch (this.section()) {
      case 'favorites': return { favorites: true };
      case 'trash': return { trash: true };
      case 'folder': return { folderId: this.folderId() };
      case 'tag': return { tag: this.tag() };
      default: return {};
    }
  });

  /** Somente pastas cujos ancestrais estão expandidos. */
  readonly visibleTree = computed(() => {
    const byId = new Map(this.tree().map((f) => [f.id, f]));
    const open = this.expanded();
    return this.tree().filter((f) => {
      let p = f.parentId ? byId.get(f.parentId) : undefined;
      while (p) {
        if (!open.has(p.id)) return false;
        p = p.parentId ? byId.get(p.parentId) : undefined;
      }
      return true;
    });
  });

  readonly breadcrumb = computed(() => {
    const byId = new Map(this.tree().map((f) => [f.id, f]));
    const out: FolderNode[] = [];
    let f = this.folderId() ? byId.get(this.folderId()!) : undefined;
    while (f && out.length < 30) {
      out.unshift(f);
      f = f.parentId ? byId.get(f.parentId) : undefined;
    }
    return out;
  });

  readonly children = computed(() => this.tree().filter((f) => f.parentId === this.folderId()));

  constructor() {
    const q = this.route.snapshot.queryParamMap;
    if (q.get('pasta')) { this.section.set('folder'); this.folderId.set(q.get('pasta')); }
    else if (q.get('vista') === 'favoritos') this.section.set('favorites');
    else if (q.get('vista') === 'lixeira') this.section.set('trash');
    else if (q.get('tag')) { this.section.set('tag'); this.tag.set(q.get('tag')); }
    this.loadFolders();
  }

  loadFolders(): void {
    this.service.folders().subscribe((list) => {
      const tree = folderTree(list);
      this.tree.set(tree);
      // Expande o caminho da pasta aberta.
      const open = new Set(this.expanded());
      for (const c of this.breadcrumb()) open.add(c.id);
      this.expanded.set(open);
      if (this.section() === 'folder' && !tree.some((f) => f.id === this.folderId())) this.go('all');
    });
  }

  hasChildren(id: string): boolean {
    return this.tree().some((f) => f.parentId === id);
  }

  toggleExpand(id: string): void {
    const next = new Set(this.expanded());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.expanded.set(next);
  }

  go(section: Exclude<Section, 'folder' | 'tag'>): void {
    this.section.set(section);
    this.folderId.set(null);
    this.tag.set(null);
    this.syncUrl({ vista: section === 'favorites' ? 'favoritos' : section === 'trash' ? 'lixeira' : null });
  }

  openFolder(id: string): void {
    this.section.set('folder');
    this.folderId.set(id);
    const open = new Set(this.expanded());
    for (const c of this.breadcrumb()) open.add(c.id);
    this.expanded.set(open);
    this.syncUrl({ pasta: id });
  }

  openTag(tag: string): void {
    this.section.set('tag');
    this.tag.set(tag);
    this.syncUrl({ tag });
  }

  private syncUrl(params: Record<string, string | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route, replaceUrl: true,
      queryParams: { pasta: null, vista: null, tag: null, doc: null, ...params },
    });
  }

  newFolder(parentId: string | null): void {
    this.dialog.open(FolderDialogComponent, { data: { parentId }, width: '460px', maxWidth: '96vw' }).afterClosed().subscribe((f) => {
      if (!f) return;
      this.toast.success('Pasta criada.');
      if (parentId) this.expanded.set(new Set([...this.expanded(), parentId]));
      this.loadFolders();
    });
  }

  editFolder(f: FolderNode): void {
    this.dialog.open(FolderDialogComponent, { data: { folder: f }, width: '460px', maxWidth: '96vw' }).afterClosed().subscribe((r) => {
      if (r) { this.toast.success('Pasta atualizada.'); this.loadFolders(); }
    });
  }

  deleteFolder(f: FolderNode): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir pasta', message: `Excluir a pasta “${f.name}”? Ela precisa estar vazia (sem documentos e sem subpastas).`, confirmLabel: 'Excluir', danger: true },
      width: '440px',
    }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.service.deleteFolder(f.id).subscribe(() => {
        this.toast.success('Pasta excluída.');
        if (this.folderId() === f.id) this.go('all');
        this.loadFolders();
      });
    });
  }
}
