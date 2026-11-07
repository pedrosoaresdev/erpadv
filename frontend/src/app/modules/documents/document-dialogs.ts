import { Component, OnDestroy, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { CasesService } from '../cases/cases.service';
import { saveBlob } from '../finance/finance.service';
import {
  ACCEPTED_TYPES,
  ALWAYS_CONFIDENTIAL,
  AccessLogEntry,
  DOCUMENT_CATEGORY_LABELS,
  DocumentCategory,
  DocumentVersion,
  DocumentsService,
  MAX_DOCUMENT_BYTES,
  StoredDocument,
  formatBytes,
} from './documents.service';

export interface UploadDialogData {
  client?: { id: string; name: string } | null;
  legalCase?: { id: string; label: string } | null;
  category?: DocumentCategory;
  document?: StoredDocument | null;
}

// ======================================================================= envio / metadados
@Component({
  selector: 'app-document-upload-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule,
    MatIconModule, MatSlideToggleModule, MatAutocompleteModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ editing ? 'edit_document' : 'upload_file' }}</mat-icon>{{ editing ? 'Editar documento' : 'Enviar documento' }}</h2>
    <mat-dialog-content>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <form [formGroup]="form" class="form-grid" id="docForm" (ngSubmit)="save()">
        @if (!editing) {
          <div class="drop" [class.has]="file()" (click)="picker.click()" (dragover)="$event.preventDefault()" (drop)="onDrop($event)" tabindex="0" (keydown.enter)="picker.click()">
            <mat-icon>{{ file() ? 'description' : 'cloud_upload' }}</mat-icon>
            @if (file(); as f) { <strong>{{ f.name }}</strong><span class="muted">{{ size(f.size) }}</span> }
            @else { <strong>Arraste o arquivo ou clique para selecionar</strong><span class="muted">PDF, DOCX, PNG ou JPEG · até 25 MB</span> }
            <input #picker type="file" [accept]="accepted" hidden (change)="onPick($event)" />
          </div>
        }
        <mat-form-field class="c8">
          <mat-label>Título</mat-label>
          <input matInput formControlName="title" maxlength="200" />
          @if (form.controls.title.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field class="c4">
          <mat-label>Categoria</mat-label>
          <mat-select formControlName="category">
            @for (c of categories; track c) { <mat-option [value]="c">{{ labels[c] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field>
          <mat-label>Processo (opcional)</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ, cliente ou parte" />
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="displayCase" (optionSelected)="caseId = $event.option.value.id">
            @for (c of caseOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
          </mat-autocomplete>
          @if (data.client && !caseId) { <mat-hint>Será vinculado ao cliente {{ data.client.name }}</mat-hint> }
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
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="docForm" [disabled]="saving()"><mat-icon>save</mat-icon>{{ editing ? 'Salvar' : 'Enviar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    .drop { grid-column: span 12; border: 2px dashed var(--app-border); border-radius: 12px; padding: 22px; text-align: center; cursor: pointer;
      display: flex; flex-direction: column; align-items: center; gap: 4px; margin-bottom: 16px; transition: border-color .15s, background .15s; }
    .drop:hover, .drop.has { border-color: var(--app-gold); background: var(--app-gold-soft); }
    .drop mat-icon { font-size: 34px; width: 34px; height: 34px; color: var(--app-gold); }
    .toggles { grid-column: span 12; display: flex; gap: 24px; flex-wrap: wrap; margin-bottom: 8px; }
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
  readonly accepted = ACCEPTED_TYPES;
  readonly size = formatBytes;
  readonly file = signal<File | null>(null);
  readonly saving = signal(false);
  readonly caseOptions = signal<{ id: string; label: string }[]>([]);
  caseId: string | null = this.data.document?.caseId ?? this.data.legalCase?.id ?? null;
  readonly caseSearch = new FormControl<{ id: string; label: string } | string>(
    this.data.document?.caseId ? { id: this.data.document.caseId, label: this.data.document.caseNumber ?? 'Processo' }
      : this.data.legalCase ?? '');

  readonly form = inject(FormBuilder).nonNullable.group({
    title: [this.data.document?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    category: [this.data.document?.category ?? this.data.category ?? ('PETICAO' as DocumentCategory), Validators.required],
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
  }

  displayCase = (v: { id: string; label: string } | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  onPick(event: Event): void {
    this.setFile((event.target as HTMLInputElement).files?.[0] ?? null);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.setFile(event.dataTransfer?.files?.[0] ?? null);
  }

  private setFile(f: File | null): void {
    if (f && f.size > MAX_DOCUMENT_BYTES) {
      this.toast.error('Arquivo maior que 25 MB.');
      return;
    }
    this.file.set(f);
    if (f && !this.form.controls.title.value) {
      this.form.controls.title.setValue(f.name.replace(/\.[^.]+$/, ''));
    }
  }

  save(): void {
    if (!this.editing && !this.file()) {
      this.toast.error('Selecione o arquivo.');
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const meta = {
      title: v.title.trim(), description: v.description.trim() || null, category: v.category,
      caseId: this.caseId, clientId: this.caseId ? null : this.data.client?.id ?? this.data.document?.clientId ?? null,
      confidential: v.confidential || ALWAYS_CONFIDENTIAL.includes(v.category), sharedWithClient: v.sharedWithClient && !v.confidential,
    };
    this.saving.set(true);
    const req = this.editing ? this.service.update(this.data.document!.id, meta) : this.service.upload(meta, this.file()!);
    req.subscribe({ next: (d) => this.ref.close(d), error: () => this.saving.set(false) });
  }
}

// ======================================================================= visualização, versões e acessos
const ACTION_LABELS: Record<AccessLogEntry['action'], string> = {
  UPLOAD: 'Enviou', NEW_VERSION: 'Nova versão', VIEW: 'Visualizou', DOWNLOAD: 'Baixou', UPDATE: 'Editou', ARCHIVE: 'Arquivou', UNARCHIVE: 'Desarquivou',
};

@Component({
  selector: 'app-document-viewer-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, MatTabsModule, MatProgressBarModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>description</mat-icon>{{ doc().title }}
      <span class="badge">v{{ doc().currentVersion }}</span>
      @if (doc().confidential) { <span class="badge danger">Sigiloso</span> }
      @if (doc().archived) { <span class="badge">Arquivado</span> }
    </h2>
    <mat-dialog-content>
      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" (selectedTabChange)="onTab($event.index)">
        <mat-tab label="Visualizar">
          <div class="preview">
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (previewUrl(); as url) {
              @if (isImage()) { <img [src]="url" alt="Pré-visualização" /> }
              @else { <iframe [src]="url" title="Pré-visualização do documento"></iframe> }
            } @else if (!loading()) {
              <div class="no-preview"><mat-icon>visibility_off</mat-icon><p>Pré-visualização indisponível para {{ doc().fileName }}. Use “Baixar”.</p></div>
            }
          </div>
        </mat-tab>
        <mat-tab label="Versões">
          <table class="data">
            <thead><tr><th>Versão</th><th>Arquivo</th><th>Enviado por</th><th>Data</th><th></th></tr></thead>
            <tbody>
              @for (v of versions(); track v.id) {
                <tr>
                  <td><strong>v{{ v.version }}</strong>@if (v.note) { <div class="muted small">{{ v.note }}</div> }</td>
                  <td>{{ v.fileName }}<div class="muted small mono" [title]="v.sha256">SHA-256 {{ v.sha256.slice(0, 12) }}…</div></td>
                  <td>{{ v.uploadedByName }}</td>
                  <td>{{ v.createdAt | brDate: true }}</td>
                  <td><button mat-icon-button (click)="download(v.version)" aria-label="Baixar versão"><mat-icon>download</mat-icon></button></td>
                </tr>
              }
            </tbody>
          </table>
          @if (canWrite && !doc().archived) {
            <div class="new-version">
              <mat-form-field subscriptSizing="dynamic"><mat-label>Observação da nova versão</mat-label><input matInput [formControl]="note" maxlength="300" /></mat-form-field>
              <button mat-stroked-button (click)="versionPicker.click()"><mat-icon>upload</mat-icon>Enviar nova versão</button>
              <input #versionPicker type="file" [accept]="accepted" hidden (change)="newVersion($event)" />
            </div>
          }
        </mat-tab>
        <mat-tab label="Histórico de acesso">
          <table class="data">
            <thead><tr><th>Quando</th><th>Quem</th><th>Ação</th><th>Versão</th><th>IP</th></tr></thead>
            <tbody>
              @for (a of log(); track a.id) {
                <tr><td>{{ a.createdAt | brDate: true }}</td><td>{{ a.userName }}</td><td>{{ actions[a.action] }}</td><td>v{{ a.version }}</td><td class="mono muted">{{ a.ip || '—' }}</td></tr>
              }
            </tbody>
          </table>
        </mat-tab>
      </mat-tab-group>
    </mat-dialog-content>
    <mat-dialog-actions>
      <span class="muted small">{{ doc().categoryLabel }} · {{ doc().clientName || 'sem cliente' }}{{ doc().caseNumber ? ' · ' + doc().caseNumber : '' }}</span>
      <span class="spacer"></span>
      <button mat-button mat-dialog-close>Fechar</button>
      <button mat-flat-button (click)="download()"><mat-icon>download</mat-icon>Baixar</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { min-height: 60vh; }
    .preview { height: 62vh; display: flex; align-items: stretch; justify-content: center; background: var(--app-surface-2); border-radius: 10px; margin-top: 12px; overflow: auto; flex-direction: column; }
    iframe { flex: 1; border: 0; width: 100%; background: #fff; }
    img { max-width: 100%; margin: auto; display: block; }
    .no-preview { margin: auto; text-align: center; color: var(--app-text-muted); }
    .small { font-size: 12px; }
    .new-version { display: flex; gap: 12px; align-items: center; padding: 16px 0; flex-wrap: wrap; }
    .new-version mat-form-field { flex: 1 1 260px; }
    .dialog-title .badge { margin-left: 6px; }
  `,
})
export class DocumentViewerDialogComponent implements OnDestroy {
  private readonly service = inject(DocumentsService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');
  readonly doc = signal(inject<StoredDocument>(MAT_DIALOG_DATA));
  readonly actions = ACTION_LABELS;
  readonly accepted = ACCEPTED_TYPES;
  readonly previewUrl = signal<SafeResourceUrl | null>(null);
  readonly loading = signal(false);
  readonly versions = signal<DocumentVersion[]>([]);
  readonly log = signal<AccessLogEntry[]>([]);
  readonly note = new FormControl('', { nonNullable: true });
  private objectUrl: string | null = null;

  constructor() {
    this.loadPreview();
  }

  isImage(): boolean {
    return this.doc().contentType.startsWith('image/');
  }

  private loadPreview(): void {
    const d = this.doc();
    if (!(d.contentType === 'application/pdf' || d.contentType.startsWith('image/'))) return;
    this.loading.set(true);
    this.service.content(d.id, false).subscribe({
      next: (r) => {
        this.revoke();
        this.objectUrl = URL.createObjectURL(r.body as Blob);
        this.previewUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(this.objectUrl));
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
    this.service.content(this.doc().id, true, version).subscribe((r) => saveBlob(r, this.doc().fileName));
  }

  newVersion(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > MAX_DOCUMENT_BYTES) {
      this.toast.error('Arquivo maior que 25 MB.');
      return;
    }
    this.service.newVersion(this.doc().id, file, this.note.value.trim() || null).subscribe((d) => {
      this.doc.set(d);
      this.note.setValue('');
      this.toast.success(`Versão ${d.currentVersion} enviada.`);
      this.onTab(1);
      this.loadPreview();
    });
  }

  private revoke(): void {
    if (this.objectUrl) URL.revokeObjectURL(this.objectUrl);
  }

  ngOnDestroy(): void {
    this.revoke();
  }
}
