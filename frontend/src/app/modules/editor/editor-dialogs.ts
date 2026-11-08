import { AfterViewInit, Component, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ClientSummary, LegalCase } from '../../core/models/models';
import { BoardsService } from '../boards/boards.service';
import { CasesService } from '../cases/cases.service';
import { ClientsService } from '../clients/clients.service';
import { KIND_LABELS, LegalDocKind, LegalEditorService, LegalTemplate } from './legal-editor.service';
import { RichTextEditorComponent } from './rich-text-editor';

export interface NewDocumentData {
  templates: LegalTemplate[];
  templateId?: string | null;
  client?: { id: string; name: string } | null;
  legalCase?: { id: string; label: string } | null;
}

type Option = { id: string; label: string };

// ======================================================================= novo documento
@Component({
  selector: 'app-new-legal-document-dialog',
  imports: [FormsModule, ReactiveFormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatAutocompleteModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>note_add</mat-icon>Novo documento</h2>
    <mat-dialog-content>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="kinds" role="tablist" aria-label="Tipo de documento">
        <button type="button" [class.on]="kind() === ''" (click)="kind.set('')">Todos</button>
        @for (k of kindsInUse(); track k) { <button type="button" [class.on]="kind() === k" (click)="kind.set(k)">{{ labels[k] }}</button> }
      </div>
      <div class="gallery" role="listbox" aria-label="Modelos">
        @for (t of visible(); track t.id) {
          <button type="button" class="tpl" [class.on]="templateId() === t.id" (click)="templateId.set(t.id)" role="option" [attr.aria-selected]="templateId() === t.id">
            <mat-icon>{{ t.system ? 'description' : 'bookmark' }}</mat-icon>
            <strong>{{ t.name }}</strong>
            <span class="muted small">{{ t.description || t.kindLabel }}</span>
            <span class="badge" [class.mine]="!t.system">{{ t.system ? 'Modelo do sistema' : 'Modelo do escritório' }}</span>
          </button>
        }
      </div>
      <div class="form-grid">
        <mat-form-field class="c6">
          <mat-label>Cliente</mat-label>
          <input matInput [formControl]="clientSearch" [matAutocomplete]="clientAuto" placeholder="Nome ou CPF/CNPJ" />
          <mat-autocomplete #clientAuto="matAutocomplete" [displayWith]="display" (optionSelected)="clientId = $event.option.value.id">
            @for (c of clientOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
          </mat-autocomplete>
          <mat-hint>Preenche nome, qualificação, documento e endereço</mat-hint>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Processo</mat-label>
          <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Nº CNJ, cliente ou parte" />
          <mat-autocomplete #caseAuto="matAutocomplete" [displayWith]="display" (optionSelected)="caseId = $event.option.value.id">
            @for (c of caseOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
          </mat-autocomplete>
          <mat-hint>Preenche número, vara, comarca e parte contrária</mat-hint>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Advogado(a) que assina</mat-label>
          <mat-select [(ngModel)]="lawyerUid">
            <mat-option [value]="null">Responsável pelo processo / eu</mat-option>
            @for (s of lawyers(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}{{ s.oabNumber ? ' · OAB ' + s.oabNumber : '' }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Título (opcional)</mat-label>
          <input matInput [(ngModel)]="title" maxlength="200" placeholder="Gerado a partir do tipo e do cliente" />
        </mat-form-field>
      </div>
      <p class="muted small">Os modelos são estruturas-base: revise todo o conteúdo e complete os trechos entre colchetes antes de usar.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button (click)="create()" [disabled]="!templateId() || saving()"><mat-icon>edit_document</mat-icon>Criar e abrir no editor</button>
    </mat-dialog-actions>
  `,
  styles: `
    .kinds { display: flex; gap: 6px; flex-wrap: wrap; margin: 4px 0 12px; }
    .kinds button { border: 1px solid var(--app-border); background: var(--app-surface); color: var(--app-text); border-radius: 999px; padding: 4px 12px; font: inherit; font-size: 13px; cursor: pointer; }
    .kinds button.on { background: var(--app-gold-soft); border-color: var(--app-gold); font-weight: 600; }
    .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; max-height: 300px; overflow: auto; margin-bottom: 16px; padding: 2px; }
    .tpl { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; text-align: left; border: 1px solid var(--app-border); border-radius: 10px;
      background: var(--app-surface); color: var(--app-text); padding: 12px; cursor: pointer; font: inherit; }
    .tpl:hover { border-color: var(--app-gold); }
    .tpl.on { border-color: var(--app-gold); box-shadow: 0 0 0 2px var(--app-gold-soft); }
    .tpl mat-icon { color: var(--app-gold); }
    .small { font-size: 12px; }
    .badge { font-size: 11px; }
    .badge.mine { background: var(--app-gold-soft); }
  `,
})
export class NewLegalDocumentDialogComponent {
  readonly data = inject<NewDocumentData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<NewLegalDocumentDialogComponent>);
  private readonly service = inject(LegalEditorService);
  private readonly cases = inject(CasesService);
  private readonly clients = inject(ClientsService);
  readonly labels = KIND_LABELS;
  readonly kind = signal<LegalDocKind | ''>('');
  readonly templateId = signal<string | null>(this.data.templateId ?? this.data.templates[0]?.id ?? null);
  readonly kindsInUse = computed(() => [...new Set(this.data.templates.map((t) => t.kind))]);
  readonly visible = computed(() => this.data.templates.filter((t) => !this.kind() || t.kind === this.kind()));
  readonly lawyers = toSignal(inject(BoardsService).staff(), { initialValue: [] });
  readonly saving = signal(false);
  readonly clientOptions = signal<Option[]>([]);
  readonly caseOptions = signal<Option[]>([]);
  clientId: string | null = this.data.client?.id ?? null;
  caseId: string | null = this.data.legalCase?.id ?? null;
  lawyerUid: string | null = null;
  title = '';
  readonly clientSearch = new FormControl<Option | string>(this.data.client ? { id: this.data.client.id, label: this.data.client.name } : '');
  readonly caseSearch = new FormControl<Option | string>(this.data.legalCase ?? '');

  constructor() {
    this.clientSearch.valueChanges.pipe(
      debounceTime(250), filter((v): v is string => typeof v === 'string'),
      switchMap((term) => {
        this.clientId = null;
        return term.trim().length >= 2
          ? this.clients.list({ search: term.trim() }, null, 8).pipe(catchError(() => of({ items: [] as ClientSummary[], nextCursor: null })))
          : of({ items: [] as ClientSummary[], nextCursor: null });
      }),
      takeUntilDestroyed(),
    ).subscribe((p) => this.clientOptions.set(p.items.map((c) => ({ id: c.id, label: `${c.name} · ${c.documentMasked}` }))));
    this.caseSearch.valueChanges.pipe(
      debounceTime(250), filter((v): v is string => typeof v === 'string'),
      switchMap((term) => {
        this.caseId = null;
        return term.trim().length >= 2
          ? this.cases.list({ search: term.trim() }, null, 8).pipe(catchError(() => of({ items: [] as LegalCase[], nextCursor: null })))
          : of({ items: [] as LegalCase[], nextCursor: null });
      }),
      takeUntilDestroyed(),
    ).subscribe((p) => this.caseOptions.set(p.items.map((c) => ({ id: c.id, label: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}` }))));
  }

  display = (v: Option | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  create(): void {
    const id = this.templateId();
    if (!id) return;
    this.saving.set(true);
    this.service.create({ title: this.title.trim() || null, kind: null, templateId: id, clientId: this.clientId, caseId: this.caseId, lawyerUid: this.lawyerUid })
      .subscribe({ next: (d) => this.ref.close(d), error: () => this.saving.set(false) });
  }
}

// ======================================================================= modelo do escritório
export interface TemplateDialogData {
  template?: LegalTemplate | null;
  /** Conteúdo inicial (ex.: "salvar documento como modelo"). */
  content?: string;
  kind?: LegalDocKind;
  name?: string;
}

@Component({
  selector: 'app-legal-template-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, RichTextEditorComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>bookmark_add</mat-icon>{{ editingId ? 'Editar modelo do escritório' : 'Novo modelo do escritório' }}</h2>
    <mat-dialog-content>
      <div class="form-grid">
        <mat-form-field class="c6"><mat-label>Nome do modelo</mat-label><input matInput [(ngModel)]="name" maxlength="120" required /></mat-form-field>
        <mat-form-field class="c6">
          <mat-label>Tipo</mat-label>
          <mat-select [(ngModel)]="kind">@for (k of kinds; track k) { <mat-option [value]="k">{{ labels[k] }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field><mat-label>Descrição</mat-label><input matInput [(ngModel)]="description" maxlength="300" /></mat-form-field>
      </div>
      <p class="muted small">Use “Inserir campo” para colocar dados que serão preenchidos automaticamente — ex.: <code>{{ example }}</code>.</p>
      <app-rich-text-editor class="tpl-editor" [fields]="fields()" [placeholderMode]="true" [compact]="true" label="Conteúdo do modelo" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="!name.trim() || saving()"><mat-icon>save</mat-icon>Salvar modelo</button>
    </mat-dialog-actions>
  `,
  styles: `
    .small { font-size: 12px; }
    .tpl-editor { height: 56vh; }
  `,
})
export class LegalTemplateDialogComponent implements AfterViewInit {
  readonly data = inject<TemplateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LegalTemplateDialogComponent>);
  private readonly service = inject(LegalEditorService);
  private readonly editor = viewChild.required(RichTextEditorComponent);
  readonly labels = KIND_LABELS;
  readonly kinds = Object.keys(KIND_LABELS) as LegalDocKind[];
  readonly example = '{{cliente.nome}}';
  readonly fields = toSignal(this.service.fields(null, null).pipe(switchMap((f) => of(f.catalog)), catchError(() => of([]))), { initialValue: [] });
  readonly saving = signal(false);
  readonly editingId = this.data.template && !this.data.template.system ? this.data.template.id : null;
  name = this.data.template ? (this.data.template.system ? `${this.data.template.name} (escritório)` : this.data.template.name) : this.data.name ?? '';
  kind: LegalDocKind = this.data.template?.kind ?? this.data.kind ?? 'OUTRO';
  description = this.data.template?.description ?? '';

  ngAfterViewInit(): void {
    this.editor().setHtml(this.data.template?.content ?? this.data.content ?? '<p><br></p>');
  }

  save(): void {
    this.saving.set(true);
    this.service.saveTemplate(this.editingId, {
      name: this.name.trim(), kind: this.kind, description: this.description.trim() || null, content: this.editor().getHtml(),
    }).subscribe({ next: (t) => this.ref.close(t), error: () => this.saving.set(false) });
  }
}

// ======================================================================= versão (somente leitura)
@Component({
  selector: 'app-legal-version-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, RichTextEditorComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>history</mat-icon>Versão {{ data.version }} — {{ data.title }}</h2>
    <mat-dialog-content>
      <app-rich-text-editor class="ver" [readonly]="true" [header]="data.header" [footer]="data.footer" [pageNumbers]="data.pageNumbers" label="Conteúdo da versão" />
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Fechar</button>
      @if (data.canRestore) { <button mat-flat-button [mat-dialog-close]="true"><mat-icon>restore</mat-icon>Restaurar esta versão</button> }
    </mat-dialog-actions>
  `,
  styles: `.ver { height: 66vh; }`,
})
export class LegalVersionDialogComponent implements AfterViewInit {
  readonly data = inject<{ version: number; title: string; content: string; header: string | null; footer: string | null; pageNumbers: boolean; canRestore: boolean }>(MAT_DIALOG_DATA);
  private readonly editor = viewChild.required(RichTextEditorComponent);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');

  ngAfterViewInit(): void {
    this.editor().setHtml(this.data.content);
  }
}
