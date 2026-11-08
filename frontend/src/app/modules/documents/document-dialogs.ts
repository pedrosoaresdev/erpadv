import { HttpResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatChipInputEvent, MatChipsModule } from '@angular/material/chips';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { catchError, debounceTime, filter, firstValueFrom, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { BoardsService } from '../boards/boards.service';
import { CasesService } from '../cases/cases.service';
import { saveBlob } from '../finance/finance.service';
import {
  ACCEPTED_EXTENSIONS,
  ACCEPTED_TYPES,
  ALWAYS_CONFIDENTIAL,
  AccessLogEntry,
  DOCUMENT_CATEGORY_LABELS,
  DocumentCategory,
  DocumentMeta,
  DocumentVersion,
  DocumentsService,
  FolderNode,
  MAX_DOCUMENT_BYTES,
  StoredDocument,
  fileExtension,
  folderTree,
  formatBytes,
  sha256Hex,
} from './documents.service';
import { FileViewerComponent } from './file-viewer';

export interface UploadDialogData {
  client?: { id: string; name: string } | null;
  legalCase?: { id: string; label: string } | null;
  category?: DocumentCategory;
  document?: StoredDocument | null;
  folderId?: string | null;
  files?: File[];
}

type FileState = 'checking' | 'ready' | 'duplicate' | 'invalid' | 'uploading' | 'done' | 'error';

interface PendingFile {
  file: File;
  title: string;
  state: FileState;
  message: string | null;
  sha256: string | null;
  /** Para duplicados: enviar mesmo assim. */
  force: boolean;
}

const STATE_ICONS: Record<FileState, string> = {
  checking: 'hourglass_top', ready: 'description', duplicate: 'content_copy', invalid: 'block', uploading: 'cloud_upload',
  done: 'check_circle', error: 'error',
};

// ======================================================================= envio múltiplo / metadados
@Component({
  selector: 'app-document-upload-dialog',
  imports: [ReactiveFormsModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatSlideToggleModule, MatAutocompleteModule, MatProgressBarModule, MatChipsModule, MatCheckboxModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ editing ? 'edit_document' : 'upload_file' }}</mat-icon>{{ editing ? 'Editar documento' : 'Enviar documentos' }}</h2>
    <mat-dialog-content>
      @if (saving()) { <mat-progress-bar mode="determinate" [value]="progress()" /> }
      <form [formGroup]="form" class="form-grid" id="docForm" (ngSubmit)="save()">
        @if (!editing) {
          <div class="drop" [class.over]="dragOver()" (click)="picker.click()" (dragover)="$event.preventDefault(); dragOver.set(true)"
            (dragleave)="dragOver.set(false)" (drop)="onDrop($event)" tabindex="0" (keydown.enter)="picker.click()" role="button"
            aria-label="Selecionar arquivos">
            <mat-icon>cloud_upload</mat-icon>
            <strong>Arraste os arquivos ou clique para selecionar</strong>
            <span class="muted">PDF, Word, Excel, PowerPoint, imagens (JPG/PNG) ou TXT · até {{ size(maxBytes()) }} por arquivo</span>
            <input #picker type="file" multiple [accept]="accepted" hidden (change)="onPick($event)" />
          </div>
          @if (files().length) {
            <ul class="files">
              @for (f of files(); track $index; let i = $index) {
                <li [class]="f.state">
                  <mat-icon class="st">{{ icons[f.state] }}</mat-icon>
                  <div class="fmain">
                    <input class="ftitle" [ngModel]="f.title" (ngModelChange)="setTitle(i, $event)" [ngModelOptions]="{ standalone: true }"
                      [disabled]="saving() || f.state === 'done'" maxlength="200" [attr.aria-label]="'Título de ' + f.file.name" />
                    <span class="small muted">{{ f.file.name }} · {{ size(f.file.size) }}
                      @if (f.message) { · <span class="msg">{{ f.message }}</span> }</span>
                    @if (f.state === 'duplicate') {
                      <mat-checkbox [checked]="f.force" (change)="setForce(i, $event.checked)" [disabled]="saving()">Enviar mesmo assim</mat-checkbox>
                    }
                  </div>
                  @if (!saving() && f.state !== 'done') {
                    <button mat-icon-button type="button" (click)="remove(i)" aria-label="Remover da lista"><mat-icon>close</mat-icon></button>
                  }
                </li>
              }
            </ul>
          }
        }
        @if (editing) {
          <mat-form-field class="c8">
            <mat-label>Título</mat-label>
            <input matInput formControlName="title" maxlength="200" />
            @if (form.controls.title.invalid) { <mat-error>Obrigatório</mat-error> }
          </mat-form-field>
        }
        <mat-form-field [class]="editing ? 'c4' : 'c6'">
          <mat-label>Categoria</mat-label>
          <mat-select formControlName="category">
            @for (c of categories; track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field [class]="editing ? 'c6' : 'c6'">
          <mat-label>Pasta</mat-label>
          <mat-select formControlName="folderId">
            <mat-option value="">Sem pasta (raiz)</mat-option>
            @for (f of folders(); track f.id) { <mat-option [value]="f.id"><span [style.padding-left.px]="f.depth * 14">{{ f.name }}</span></mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field [class]="editing ? 'c6' : 'c12'">
          <mat-label>Processo (opcional)</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ, cliente ou parte" />
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="displayCase" (optionSelected)="caseId = $event.option.value.id">
            @for (c of caseOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
          </mat-autocomplete>
          @if (data.client && !caseId) { <mat-hint>Será vinculado ao cliente {{ data.client.name }}</mat-hint> }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Etiquetas</mat-label>
          <mat-chip-grid #chips aria-label="Etiquetas">
            @for (t of tags(); track t) {
              <mat-chip-row (removed)="removeTag(t)">{{ t }}<button matChipRemove [attr.aria-label]="'Remover ' + t"><mat-icon>cancel</mat-icon></button></mat-chip-row>
            }
            <input placeholder="Digite e pressione Enter" [matChipInputFor]="chips" (matChipInputTokenEnd)="addTag($event)" maxlength="30" />
          </mat-chip-grid>
          <mat-hint>Até 10 etiquetas (ex.: urgente, assinado, original)</mat-hint>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Descrição</mat-label>
          <textarea matInput rows="2" formControlName="description" maxlength="2000"></textarea>
        </mat-form-field>
        <div class="toggles">
          <mat-slide-toggle formControlName="confidential" [disabled]="!canConfidential">Sigiloso (acesso restrito)</mat-slide-toggle>
          <mat-slide-toggle formControlName="sharedWithClient" [disabled]="form.controls.confidential.value">Liberar no portal do cliente</mat-slide-toggle>
        </div>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (!editing && files().length) { <span class="muted small summary">{{ sendable().length }} de {{ files().length }} prontos para envio</span> }
      <button mat-button mat-dialog-close type="button" [disabled]="saving()">{{ uploaded() ? 'Fechar' : 'Cancelar' }}</button>
      <button mat-flat-button type="submit" form="docForm" [disabled]="saving() || (!editing && sendable().length === 0)">
        <mat-icon>{{ editing ? 'save' : 'upload' }}</mat-icon>{{ editing ? 'Salvar' : 'Enviar ' + (sendable().length > 1 ? sendable().length + ' arquivos' : '') }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .drop { grid-column: span 12; border: 2px dashed var(--app-border); border-radius: 12px; padding: 22px; text-align: center; cursor: pointer;
      display: flex; flex-direction: column; align-items: center; gap: 4px; margin-bottom: 12px; transition: border-color .15s, background .15s; }
    .drop:hover, .drop.over { border-color: var(--app-gold); background: var(--app-gold-soft); }
    .drop mat-icon { font-size: 34px; width: 34px; height: 34px; color: var(--app-gold); }
    .files { grid-column: span 12; list-style: none; margin: 0 0 16px; padding: 0; border: 1px solid var(--app-border); border-radius: 10px; max-height: 260px; overflow: auto; }
    .files li { display: flex; gap: 10px; align-items: center; padding: 8px 10px; border-bottom: 1px solid var(--app-border); }
    .files li:last-child { border-bottom: 0; }
    .st { color: var(--app-gold); flex: none; }
    .duplicate .st, .duplicate .msg { color: var(--app-warning); }
    .invalid .st, .error .st, .invalid .msg, .error .msg { color: var(--app-danger); }
    .done .st { color: var(--app-success, #2e7d32); }
    .fmain { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .ftitle { border: 1px solid transparent; border-radius: 6px; padding: 4px 6px; background: transparent; color: var(--app-text); font: inherit; font-weight: 600; }
    .ftitle:hover, .ftitle:focus { border-color: var(--app-border); background: var(--app-surface-2); outline: 0; }
    .small { font-size: 12px; }
    .toggles { grid-column: span 12; display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 8px; }
    .summary { margin-right: auto; }
  `,
})
export class DocumentUploadDialogComponent {
  readonly data = inject<UploadDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DocumentUploadDialogComponent>);
  private readonly service = inject(DocumentsService);
  private readonly cases = inject(CasesService);
  private readonly toast = inject(ToastService);
  readonly editing = !!this.data.document;
  readonly canConfidential = inject(AuthService).has('DOCUMENTS_CONFIDENTIAL');
  readonly labels = DOCUMENT_CATEGORY_LABELS;
  readonly categories = (Object.keys(DOCUMENT_CATEGORY_LABELS) as DocumentCategory[]).filter(
    (c) => this.canConfidential || !ALWAYS_CONFIDENTIAL.includes(c),
  );
  readonly icons = STATE_ICONS;
  readonly accepted = ACCEPTED_TYPES;
  readonly size = formatBytes;
  readonly maxBytes = toSignal(this.service.settings().pipe(
    switchMap((s) => of(s.maxBytes)), catchError(() => of(MAX_DOCUMENT_BYTES))), { initialValue: MAX_DOCUMENT_BYTES });
  readonly folders = toSignal(this.service.folders().pipe(switchMap((f) => of(folderTree(f))), catchError(() => of([] as FolderNode[]))),
    { initialValue: [] as FolderNode[] });
  readonly files = signal<PendingFile[]>([]);
  readonly tags = signal<string[]>(this.data.document?.tags ?? []);
  readonly dragOver = signal(false);
  readonly saving = signal(false);
  readonly progress = signal(0);
  readonly uploaded = signal(0);
  readonly sendable = computed(() => this.files().filter((f) => f.state === 'ready' || (f.state === 'duplicate' && f.force) || f.state === 'error'));
  readonly caseOptions = signal<{ id: string; label: string }[]>([]);
  caseId: string | null = this.data.document?.caseId ?? this.data.legalCase?.id ?? null;
  readonly caseSearch = new FormControl<{ id: string; label: string } | string>(
    this.data.document?.caseId ? { id: this.data.document.caseId, label: this.data.document.caseNumber ?? 'Processo' }
      : this.data.legalCase ?? '');

  readonly form = inject(FormBuilder).nonNullable.group({
    title: [this.data.document?.title ?? '', this.editing ? [Validators.required, Validators.maxLength(200)] : []],
    category: [this.data.document?.category ?? this.data.category ?? ('PETICAO' as DocumentCategory), Validators.required],
    folderId: [this.data.document?.folderId ?? this.data.folderId ?? ''],
    description: [this.data.document?.description ?? ''],
    confidential: [this.data.document?.confidential ?? false],
    sharedWithClient: [this.data.document?.sharedWithClient ?? false],
  });

  constructor() {
    this.caseSearch.valueChanges.pipe(
      debounceTime(250),
      filter((v): v is string => typeof v === 'string'),
      switchMap((term) => {
        this.caseId = null;
        return term.trim().length >= 2
          ? this.cases.list({ search: term.trim() }, null, 8).pipe(catchError(() => of({ items: [] as LegalCase[], nextCursor: null })))
          : of({ items: [] as LegalCase[], nextCursor: null });
      }),
      takeUntilDestroyed(),
    ).subscribe((page) => this.caseOptions.set(page.items.map((c) => ({ id: c.id, label: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}` }))));
    this.form.controls.category.valueChanges.pipe(takeUntilDestroyed()).subscribe((c) => {
      if (ALWAYS_CONFIDENTIAL.includes(c)) this.form.controls.confidential.setValue(true);
    });
    if (this.data.files?.length) this.addFiles(this.data.files);
  }

  displayCase = (v: { id: string; label: string } | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  onPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.addFiles(Array.from(input.files ?? []));
    input.value = '';
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragOver.set(false);
    this.addFiles(Array.from(event.dataTransfer?.files ?? []));
  }

  addFiles(list: File[]): void {
    const start = this.files().length;
    const added: PendingFile[] = list.slice(0, 50).map((file) => {
      const ext = fileExtension(file.name);
      const invalid = !ACCEPTED_EXTENSIONS.includes(ext) ? 'Formato não aceito'
        : file.size > this.maxBytes() ? `Maior que ${formatBytes(this.maxBytes())}`
          : file.size === 0 ? 'Arquivo vazio' : null;
      return { file, title: file.name.replace(/\.[^.]+$/, '').slice(0, 200), state: invalid ? 'invalid' : 'checking', message: invalid, sha256: null, force: false };
    });
    this.files.update((f) => [...f, ...added]);
    added.forEach((p, i) => { if (p.state === 'checking') void this.check(start + i); });
  }

  /** Verifica duplicidade pelo SHA-256: no lote e no repositório do escritório. */
  private async check(index: number): Promise<void> {
    const item = this.files()[index];
    let sha: string | null = null;
    try {
      sha = await sha256Hex(item.file);
    } catch {
      sha = null;
    }
    const inBatch = sha ? this.files().findIndex((f, i) => i !== index && f.sha256 === sha) : -1;
    if (inBatch >= 0) {
      this.patch(index, { sha256: sha, state: 'duplicate', message: `Mesmo conteúdo de "${this.files()[inBatch].file.name}" neste envio` });
      return;
    }
    if (!sha) {
      this.patch(index, { state: 'ready', message: null });
      return;
    }
    const existing = await firstValueFrom(this.service.duplicates(sha).pipe(catchError(() => of([] as StoredDocument[]))));
    const live = existing.filter((d) => !d.deleted);
    this.patch(index, live.length || existing.length
      ? { sha256: sha, state: 'duplicate', message: `Já existe: "${(live[0] ?? existing[0]).title}"${live.length ? '' : ' (na lixeira)'}` }
      : { sha256: sha, state: 'ready', message: null });
  }

  private patch(index: number, change: Partial<PendingFile>): void {
    this.files.update((list) => list.map((f, i) => (i === index ? { ...f, ...change } : f)));
  }

  setTitle(index: number, title: string): void {
    this.patch(index, { title });
  }

  setForce(index: number, force: boolean): void {
    this.patch(index, { force });
  }

  remove(index: number): void {
    this.files.update((list) => list.filter((_, i) => i !== index));
  }

  addTag(event: MatChipInputEvent): void {
    const v = event.value.trim().toLowerCase();
    if (v && !this.tags().includes(v) && this.tags().length < 10) this.tags.update((t) => [...t, v.slice(0, 30)]);
    event.chipInput.clear();
  }

  removeTag(tag: string): void {
    this.tags.update((t) => t.filter((x) => x !== tag));
  }

  private meta(title: string): DocumentMeta {
    const v = this.form.getRawValue();
    return {
      title: title.trim(), description: v.description.trim() || null, category: v.category,
      caseId: this.caseId, clientId: this.caseId ? null : this.data.client?.id ?? this.data.document?.clientId ?? null,
      confidential: v.confidential || ALWAYS_CONFIDENTIAL.includes(v.category), sharedWithClient: v.sharedWithClient && !v.confidential,
      folderId: v.folderId || '', tags: this.tags(),
    };
  }

  async save(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.editing) {
      this.saving.set(true);
      this.service.update(this.data.document!.id, this.meta(this.form.controls.title.value)).subscribe({
        next: (d) => this.ref.close(d), error: () => this.saving.set(false),
      });
      return;
    }
    const queue = this.files().map((f, i) => ({ f, i })).filter(({ f }) => this.sendable().includes(f));
    if (!queue.length) return;
    if (queue.some(({ f }) => !f.title.trim())) {
      this.toast.error('Informe o título de todos os arquivos.');
      return;
    }
    this.saving.set(true);
    let done = 0;
    for (const { f, i } of queue) {
      this.patch(i, { state: 'uploading', message: null });
      try {
        await firstValueFrom(this.service.upload(this.meta(f.title), f.file));
        this.patch(i, { state: 'done', message: 'Enviado' });
        this.uploaded.update((n) => n + 1);
      } catch {
        this.patch(i, { state: 'error', message: 'Falha no envio — tente novamente' });
      }
      done++;
      this.progress.set(Math.round((done / queue.length) * 100));
    }
    this.saving.set(false);
    if (this.files().every((f) => f.state === 'done' || f.state === 'invalid' || (f.state === 'duplicate' && !f.force))) {
      this.ref.close(this.uploaded());
    }
  }
}

// ======================================================================= visualização, versões e acessos
const ACTION_LABELS: Record<AccessLogEntry['action'], string> = {
  UPLOAD: 'Enviou', NEW_VERSION: 'Nova versão', VIEW: 'Visualizou', DOWNLOAD: 'Baixou', UPDATE: 'Editou', ARCHIVE: 'Arquivou',
  UNARCHIVE: 'Desarquivou', MOVE: 'Moveu', TRASH: 'Enviou para a lixeira', RESTORE: 'Restaurou', SHARE: 'Compartilhou',
};

@Component({
  selector: 'app-document-viewer-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatTabsModule, MatProgressBarModule, MatFormFieldModule, MatInputModule,
    MatTooltipModule, ReactiveFormsModule, BrDatePipe, FileViewerComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>description</mat-icon><span class="t">{{ doc().title }}</span>
      <span class="badge">v{{ doc().currentVersion }}</span>
      @if (doc().confidential) { <span class="badge danger">Sigiloso</span> }
      @if (doc().deleted) { <span class="badge danger">Na lixeira</span> } @else if (doc().archived) { <span class="badge">Arquivado</span> }
      <span class="spacer"></span>
      @if (!doc().deleted) {
        <button mat-icon-button (click)="toggleFavorite()" [matTooltip]="doc().favorite ? 'Remover dos favoritos' : 'Favoritar'"
          [attr.aria-label]="doc().favorite ? 'Remover dos favoritos' : 'Favoritar'"><mat-icon [class.fav]="doc().favorite">{{ doc().favorite ? 'star' : 'star_border' }}</mat-icon></button>
      }
    </h2>
    <mat-dialog-content>
      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" (selectedTabChange)="onTab($event.index)">
        <mat-tab label="Visualizar">
          <div class="preview">
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            <app-file-viewer [blob]="blob()" [fileName]="doc().fileName" [contentType]="doc().contentType" (download)="download()" />
          </div>
        </mat-tab>
        <mat-tab label="Versões">
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Versão</th><th>Arquivo</th><th>Enviado por</th><th>Data</th><th></th></tr></thead>
              <tbody>
                @for (v of versions(); track v.id) {
                  <tr>
                    <td><strong>v{{ v.version }}</strong>@if (v.note) { <div class="muted small">{{ v.note }}</div> }</td>
                    <td>{{ v.fileName }}<div class="muted small mono" [title]="v.sha256">SHA-256 {{ v.sha256.slice(0, 12) }}…</div></td>
                    <td>{{ v.uploadedByName }}</td>
                    <td>{{ v.createdAt | brDate: true }}</td>
                    <td class="nowrap">
                      @if (v.version !== shownVersion()) { <button mat-icon-button (click)="show(v.version)" matTooltip="Visualizar esta versão" aria-label="Visualizar versão"><mat-icon>visibility</mat-icon></button> }
                      <button mat-icon-button (click)="download(v.version)" matTooltip="Baixar versão" aria-label="Baixar versão"><mat-icon>download</mat-icon></button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (canWrite && !doc().archived && !doc().deleted) {
            <div class="new-version">
              <mat-form-field subscriptSizing="dynamic"><mat-label>Observação da nova versão</mat-label><input matInput [formControl]="note" maxlength="300" /></mat-form-field>
              <button mat-stroked-button (click)="versionPicker.click()"><mat-icon>upload</mat-icon>Enviar nova versão</button>
              <input #versionPicker type="file" [accept]="accepted" hidden (change)="newVersion($event)" />
            </div>
          }
        </mat-tab>
        <mat-tab label="Histórico de acesso">
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Quando</th><th>Quem</th><th>Ação</th><th>Versão</th><th>IP</th></tr></thead>
              <tbody>
                @for (a of log(); track a.id) {
                  <tr><td>{{ a.createdAt | brDate: true }}</td><td>{{ a.userName }}</td><td>{{ actions[a.action] || a.action }}</td><td>v{{ a.version }}</td><td class="mono muted">{{ a.ip || '—' }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </mat-tab>
      </mat-tab-group>
    </mat-dialog-content>
    <mat-dialog-actions>
      <span class="muted small meta">{{ doc().categoryLabel }} · {{ doc().clientName || 'sem cliente' }}{{ doc().caseNumber ? ' · ' + doc().caseNumber : '' }}
        @if (shownVersion() !== doc().currentVersion) { · exibindo v{{ shownVersion() }} }</span>
      <span class="spacer"></span>
      <button mat-button mat-dialog-close>Fechar</button>
      <button mat-flat-button (click)="download()"><mat-icon>download</mat-icon>Baixar</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { min-height: 60vh; }
    .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
    .preview { height: 66vh; margin-top: 12px; display: flex; flex-direction: column; }
    .preview app-file-viewer { flex: 1; min-height: 0; }
    .small { font-size: 12px; }
    .nowrap { white-space: nowrap; }
    .new-version { display: flex; gap: 12px; align-items: center; padding: 16px 0; flex-wrap: wrap; }
    .new-version mat-form-field { flex: 1 1 260px; }
    .dialog-title .badge { margin-left: 6px; }
    .fav { color: var(--app-gold); }
    .meta { min-width: 0; }
    @media (max-width: 720px) { .preview { height: 72vh; } }
  `,
})
export class DocumentViewerDialogComponent {
  private readonly service = inject(DocumentsService);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');
  readonly doc = signal(inject<StoredDocument>(MAT_DIALOG_DATA));
  readonly actions = ACTION_LABELS;
  readonly accepted = ACCEPTED_TYPES;
  readonly blob = signal<Blob | null>(null);
  readonly shownVersion = signal(this.doc().currentVersion);
  readonly loading = signal(false);
  readonly versions = signal<DocumentVersion[]>([]);
  readonly log = signal<AccessLogEntry[]>([]);
  readonly note = new FormControl('', { nonNullable: true });

  constructor() {
    this.show();
  }

  /** Carrega o conteúdo autenticado (registra VIEW no histórico de acesso). */
  show(version?: number): void {
    this.loading.set(true);
    this.service.content(this.doc().id, false, version).subscribe({
      next: (r: HttpResponse<Blob>) => {
        this.blob.set(r.body);
        this.shownVersion.set(version ?? this.doc().currentVersion);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  onTab(index: number): void {
    if (index === 1) this.service.versions(this.doc().id).subscribe((v) => this.versions.set(v));
    if (index === 2) this.service.accessLog(this.doc().id).subscribe((p) => this.log.set(p.items));
  }

  download(version?: number): void {
    const v = version ?? (this.shownVersion() !== this.doc().currentVersion ? this.shownVersion() : undefined);
    this.service.content(this.doc().id, true, v).subscribe((r) => saveBlob(r, this.doc().fileName));
  }

  toggleFavorite(): void {
    this.service.favorite(this.doc().id, !this.doc().favorite).subscribe((d) => this.doc.set(d));
  }

  newVersion(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.service.newVersion(this.doc().id, file, this.note.value.trim() || null).subscribe((d) => {
      this.doc.set(d);
      this.note.setValue('');
      this.toast.success(`Versão ${d.currentVersion} enviada.`);
      this.onTab(1);
      this.show();
    });
  }
}

// ======================================================================= pastas
export interface FolderDialogData {
  folder?: { id: string; name: string; parentId: string | null; color: string | null } | null;
  parentId?: string | null;
}

const FOLDER_COLORS = ['', '#c9a227', '#12305a', '#2e7d32', '#c62828', '#6a1b9a', '#00838f', '#ef6c00'];

@Component({
  selector: 'app-folder-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>create_new_folder</mat-icon>{{ data.folder ? 'Editar pasta' : 'Nova pasta' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" id="folderForm" (ngSubmit)="save()" class="form-grid">
        <mat-form-field>
          <mat-label>Nome da pasta</mat-label>
          <input matInput formControlName="name" maxlength="80" cdkFocusInitial />
          @if (form.controls.name.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field>
          <mat-label>Dentro de</mat-label>
          <mat-select formControlName="parentId">
            <mat-option value="">Raiz (nenhuma)</mat-option>
            @for (f of options(); track f.id) { <mat-option [value]="f.id"><span [style.padding-left.px]="f.depth * 14">{{ f.name }}</span></mat-option> }
          </mat-select>
        </mat-form-field>
        <div class="colors" role="radiogroup" aria-label="Cor da pasta">
          @for (c of colors; track c) {
            <button type="button" class="sw" [class.on]="form.controls.color.value === c" [style.background]="c || 'var(--app-surface-2)'"
              (click)="form.controls.color.setValue(c)" role="radio" [attr.aria-checked]="form.controls.color.value === c" [attr.aria-label]="c || 'Sem cor'">
              @if (!c) { <mat-icon>block</mat-icon> }
            </button>
          }
        </div>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="folderForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .colors { grid-column: span 12; display: flex; gap: 8px; flex-wrap: wrap; }
    .sw { width: 30px; height: 30px; border-radius: 50%; border: 2px solid var(--app-border); cursor: pointer; display: grid; place-items: center; padding: 0; }
    .sw.on { outline: 3px solid var(--app-gold); outline-offset: 2px; }
    .sw mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--app-text-muted); }
  `,
})
export class FolderDialogComponent {
  readonly data = inject<FolderDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<FolderDialogComponent>);
  private readonly service = inject(DocumentsService);
  readonly colors = FOLDER_COLORS;
  readonly saving = signal(false);
  private readonly all = toSignal(this.service.folders().pipe(switchMap((f) => of(folderTree(f)))), { initialValue: [] as FolderNode[] });
  /** Exclui a própria pasta e suas subpastas (evita ciclos). */
  readonly options = computed(() => {
    const self = this.data.folder?.id;
    if (!self) return this.all();
    const blocked = new Set([self]);
    for (const f of this.all()) if (f.parentId && blocked.has(f.parentId)) blocked.add(f.id);
    return this.all().filter((f) => !blocked.has(f.id));
  });
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.data.folder?.name ?? '', [Validators.required, Validators.maxLength(80)]],
    parentId: [this.data.folder?.parentId ?? this.data.parentId ?? ''],
    color: [this.data.folder?.color ?? ''],
  });

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.saveFolder(this.data.folder?.id ?? null, { name: v.name.trim(), parentId: v.parentId || null, color: v.color || null })
      .subscribe({ next: (f) => this.ref.close(f), error: () => this.saving.set(false) });
  }
}

// ======================================================================= mover / copiar
export interface MoveDialogData {
  documents: StoredDocument[];
  mode: 'move' | 'copy';
}

@Component({
  selector: 'app-document-move-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ data.mode === 'move' ? 'drive_file_move' : 'file_copy' }}</mat-icon>
      {{ data.mode === 'move' ? 'Mover' : 'Copiar' }} {{ data.documents.length > 1 ? data.documents.length + ' documentos' : '“' + data.documents[0].title + '”' }}</h2>
    <mat-dialog-content>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <p class="muted small">Escolha a pasta de destino{{ data.mode === 'copy' ? ' (a cópia leva a versão atual do arquivo)' : '' }}.</p>
      <ul class="tree" role="listbox" aria-label="Pastas">
        <li><button type="button" [class.on]="target() === ''" (click)="target.set('')" role="option" [attr.aria-selected]="target() === ''">
          <mat-icon>home</mat-icon>Raiz (sem pasta)</button></li>
        @for (f of folders(); track f.id) {
          <li><button type="button" [class.on]="target() === f.id" (click)="target.set(f.id)" [style.padding-left.px]="12 + f.depth * 18" role="option"
            [attr.aria-selected]="target() === f.id"><mat-icon [style.color]="f.color || null">folder</mat-icon>{{ f.name }}</button></li>
        }
      </ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving()">{{ data.mode === 'move' ? 'Mover' : 'Copiar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .small { font-size: 12px; }
    .tree { list-style: none; margin: 0; padding: 0; border: 1px solid var(--app-border); border-radius: 10px; max-height: 340px; overflow: auto; }
    .tree button { width: 100%; display: flex; gap: 8px; align-items: center; border: 0; background: none; color: var(--app-text); font: inherit; padding: 8px 12px; cursor: pointer; text-align: left; }
    .tree button:hover { background: var(--app-surface-2); }
    .tree button.on { background: var(--app-gold-soft); font-weight: 600; }
    .tree mat-icon { color: var(--app-gold); }
  `,
})
export class DocumentMoveDialogComponent {
  readonly data = inject<MoveDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DocumentMoveDialogComponent>);
  private readonly service = inject(DocumentsService);
  readonly folders = toSignal(this.service.folders().pipe(switchMap((f) => of(folderTree(f)))), { initialValue: [] as FolderNode[] });
  readonly target = signal(this.data.documents.length === 1 ? this.data.documents[0].folderId ?? '' : '');
  readonly saving = signal(false);

  async save(): Promise<void> {
    this.saving.set(true);
    const folder = this.target() || null;
    let ok = 0;
    for (const d of this.data.documents) {
      try {
        await firstValueFrom(this.data.mode === 'move' ? this.service.move(d.id, folder) : this.service.copy(d.id, folder));
        ok++;
      } catch {
        // erro já exibido pelo interceptor
      }
    }
    this.saving.set(false);
    this.ref.close(ok);
  }
}

// ======================================================================= compartilhamento interno
@Component({
  selector: 'app-document-share-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>share</mat-icon>Compartilhar com a equipe</h2>
    <mat-dialog-content>
      <p class="muted small">Os integrantes escolhidos recebem uma notificação com o link do documento “{{ data.title }}”.
        O acesso continua sujeito às permissões de cada um (documentos sigilosos exigem permissão específica).</p>
      <div class="form-grid">
        <mat-form-field>
          <mat-label>Integrantes</mat-label>
          <mat-select multiple [(ngModel)]="uids">
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Mensagem (opcional)</mat-label>
          <textarea matInput rows="3" [(ngModel)]="message" maxlength="500"></textarea>
        </mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button (click)="send()" [disabled]="!uids.length || saving()"><mat-icon>send</mat-icon>Compartilhar</button>
    </mat-dialog-actions>
  `,
  styles: `.small { font-size: 12px; }`,
})
export class DocumentShareDialogComponent {
  readonly data = inject<StoredDocument>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DocumentShareDialogComponent>);
  private readonly service = inject(DocumentsService);
  private readonly me = inject(AuthService);
  readonly staff = toSignal(inject(BoardsService).staff().pipe(
    switchMap((list) => of(list.filter((s) => s.uid !== this.me.user()?.uid)))), { initialValue: [] });
  readonly saving = signal(false);
  uids: string[] = [];
  message = '';

  send(): void {
    this.saving.set(true);
    this.service.share(this.data.id, this.uids, this.message.trim() || null).subscribe({
      next: () => this.ref.close(true), error: () => this.saving.set(false),
    });
  }
}
