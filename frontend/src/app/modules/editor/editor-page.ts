import { Component, HostListener, OnDestroy, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ClientSummary, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { CasesService } from '../cases/cases.service';
import { ClientsService } from '../clients/clients.service';
import { saveBlob } from '../finance/finance.service';
import { LegalTemplateDialogComponent, LegalVersionDialogComponent } from './editor-dialogs';
import {
  Draft, FieldInfo, KIND_LABELS, LegalDocKind, LegalEditorService, Letterhead, VersionSummary, escapeHtml,
} from './legal-editor.service';
import { LetterheadDialogComponent } from './letterhead-dialog';
import { printPdf } from '../documents/file-viewer';
import { RichTextEditorComponent } from './rich-text-editor';

type Option = { id: string; label: string };
type SaveState = 'saved' | 'dirty' | 'saving' | 'error';

/** Edição de um documento jurídico: folha A4, campos automáticos, versões, finalização e exportação. */
@Component({
  selector: 'app-editor-page',
  imports: [FormsModule, ReactiveFormsModule, RouterLink, MatAutocompleteModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatSlideToggleModule, MatTabsModule, MatTooltipModule, BrDatePipe, RichTextEditorComponent],
  template: `
    <div class="ed">
      <div class="ed-top">
        <a mat-icon-button routerLink="/editor-juridico" aria-label="Voltar para o Editor Jurídico" matTooltip="Voltar"><mat-icon>arrow_back</mat-icon></a>
        @if (draft(); as d) {
          <input class="ed-title" [ngModel]="title()" (ngModelChange)="title.set($event); markDirty()" [readonly]="locked()" maxlength="200" aria-label="Título do documento" />
          <span class="badge" [class.ok]="d.status === 'FINAL'">{{ d.status === 'FINAL' ? 'Final' : 'Rascunho' }} · v{{ d.version }}</span>
          <span class="ed-save muted small" aria-live="polite">
            @switch (saveState()) {
              @case ('saving') { Salvando… }
              @case ('dirty') { Alterações não salvas }
              @case ('error') { <span class="err">Falha ao salvar — tente novamente</span> }
              @default { {{ locked() ? 'Somente leitura' : 'Salvo' }}{{ savedAt() ? ' às ' + savedAt() : '' }} }
            }
          </span>
          <span class="spacer"></span>
          @if (canWrite && d.status === 'RASCUNHO') {
            <button mat-stroked-button (click)="snapshot()" matTooltip="Registrar uma versão no histórico"><mat-icon>bookmark_added</mat-icon><span class="hide-sm">Salvar versão</span></button>
          }
          <button mat-stroked-button [matMenuTriggerFor]="exp"><mat-icon>ios_share</mat-icon><span class="hide-sm">Exportar</span></button>
          <mat-menu #exp="matMenu" xPosition="before">
            <button mat-menu-item (click)="print()"><mat-icon>print</mat-icon>Imprimir</button>
            <button mat-menu-item (click)="download('pdf')"><mat-icon>picture_as_pdf</mat-icon>Baixar PDF</button>
            <button mat-menu-item (click)="download('docx')"><mat-icon>description</mat-icon>Baixar DOCX (Word)</button>
            @if (canWrite) {
              <button mat-menu-item (click)="toCentral('pdf')"><mat-icon>inventory_2</mat-icon>Salvar PDF na central de documentos</button>
              <button mat-menu-item (click)="toCentral('docx')"><mat-icon>inventory_2</mat-icon>Salvar DOCX na central de documentos</button>
            }
          </mat-menu>
          @if (canWrite) {
            @if (d.status === 'RASCUNHO') {
              <button mat-flat-button (click)="finalize()"><mat-icon>task</mat-icon><span class="hide-sm">Finalizar</span></button>
            } @else {
              <button mat-flat-button (click)="reopen()"><mat-icon>edit</mat-icon><span class="hide-sm">Reabrir edição</span></button>
            }
            <button mat-icon-button [matMenuTriggerFor]="more" aria-label="Mais ações"><mat-icon>more_vert</mat-icon></button>
            <mat-menu #more="matMenu" xPosition="before">
              <button mat-menu-item (click)="duplicate()"><mat-icon>content_copy</mat-icon>Duplicar documento</button>
              <button mat-menu-item (click)="saveAsTemplate()"><mat-icon>bookmark_add</mat-icon>Salvar como modelo do escritório</button>
              @if (d.storedDocumentId) { <a mat-menu-item [routerLink]="['/documentos']" [queryParams]="{ doc: d.storedDocumentId }"><mat-icon>folder_open</mat-icon>Abrir na central de documentos</a> }
              @if (d.status === 'RASCUNHO') { <button mat-menu-item class="danger" (click)="remove()"><mat-icon>delete</mat-icon>Excluir rascunho</button> }
            </mat-menu>
          }
        }
      </div>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      @if (draft(); as d) {
        @if (d.status === 'FINAL') {
          <div class="ed-banner"><mat-icon>verified</mat-icon>Documento finalizado por {{ d.finalizedByName }} em {{ d.finalizedAt | brDate: true }}. Para alterar, reabra a edição — a versão final permanece no histórico.</div>
        } @else if (missing().length) {
          <div class="ed-banner warn"><mat-icon>data_object</mat-icon>{{ missing().length }} campo(s) sem dado: {{ missingLabels() }}.
            <button mat-button (click)="fillPending()">Preencher com os dados atuais</button></div>
        }
      }
      <div class="ed-body">
        <app-rich-text-editor class="ed-editor" [readonly]="locked()" [fields]="catalog()" [fieldValues]="values()"
          [header]="header()" [footer]="footer()" [pageNumbers]="pageNumbers()" [background]="letterheadUrl()" [margins]="letterheadMargins()"
          (changed)="onContent($event)" />
        <aside class="ed-side card" [class.open]="sideOpen()">
          <button mat-icon-button class="ed-side-toggle" (click)="sideOpen.set(!sideOpen())" [attr.aria-label]="sideOpen() ? 'Recolher painel' : 'Abrir painel'">
            <mat-icon>{{ sideOpen() ? 'chevron_right' : 'tune' }}</mat-icon></button>
          @if (sideOpen() && draft(); as d) {
            <mat-tab-group animationDuration="100ms" mat-stretch-tabs="true" (selectedIndexChange)="onSideTab($event)">
              <mat-tab label="Documento">
                <div class="side-pad">
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Tipo</mat-label>
                    <mat-select [ngModel]="kind()" (ngModelChange)="kind.set($event); markDirty()" [disabled]="locked()">
                      @for (k of kinds; track k) { <mat-option [value]="k">{{ labels[k] }}</mat-option> }
                    </mat-select>
                  </mat-form-field>
                  <h4>Vínculos</h4>
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Cliente</mat-label>
                    <input matInput [formControl]="clientSearch" [matAutocomplete]="ca" [readonly]="locked()" />
                    <mat-autocomplete #ca="matAutocomplete" [displayWith]="display" (optionSelected)="relink($event.option.value.id, null)">
                      @for (c of clientOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
                    </mat-autocomplete>
                  </mat-form-field>
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Processo</mat-label>
                    <input matInput [formControl]="caseSearch" [matAutocomplete]="pa" [readonly]="locked()" />
                    <mat-autocomplete #pa="matAutocomplete" [displayWith]="display" (optionSelected)="relink(null, $event.option.value.id)">
                      @for (c of caseOptions(); track c.id) { <mat-option [value]="c">{{ c.label }}</mat-option> }
                    </mat-autocomplete>
                  </mat-form-field>
                  @if ((d.clientId || d.caseId) && !locked()) { <button mat-button (click)="relink(null, null)"><mat-icon>link_off</mat-icon>Remover vínculos</button> }
                  <h4>Papel timbrado</h4>
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Papel</mat-label>
                    <mat-select [ngModel]="letterheadId()" (ngModelChange)="setLetterhead($event)" [disabled]="locked()">
                      <mat-option value="">Folha em branco</mat-option>
                      @for (l of letterheads(); track l.id) { <mat-option [value]="l.id">{{ l.name }}{{ l.isDefault ? ' (padrão)' : '' }}</mat-option> }
                    </mat-select>
                  </mat-form-field>
                  <button mat-button (click)="manageLetterheads()"><mat-icon>tune</mat-icon>Gerenciar papéis timbrados</button>
                  <h4>Página</h4>
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Cabeçalho</mat-label>
                    <textarea matInput rows="2" [ngModel]="header()" (ngModelChange)="header.set($event); markDirty()" [readonly]="locked()" maxlength="600"></textarea>
                  </mat-form-field>
                  <mat-form-field subscriptSizing="dynamic">
                    <mat-label>Rodapé</mat-label>
                    <textarea matInput rows="2" [ngModel]="footer()" (ngModelChange)="footer.set($event); markDirty()" [readonly]="locked()" maxlength="600"></textarea>
                  </mat-form-field>
                  <mat-slide-toggle [ngModel]="pageNumbers()" (ngModelChange)="pageNumbers.set($event); markDirty()" [disabled]="locked()">Numerar páginas (“Página X de Y”)</mat-slide-toggle>
                  <p class="muted small info">Modelo: {{ d.templateName || '—' }}<br>Criado por {{ d.createdByName }} em {{ d.createdAt | brDate: true }}</p>
                </div>
              </mat-tab>
              <mat-tab label="Versões">
                <div class="side-pad">
                  @if (versions().length === 0) { <p class="muted small">Carregando…</p> }
                  <ul class="versions">
                    @for (v of versions(); track v.id) {
                      <li>
                        <div class="vmain">
                          <strong>v{{ v.version }}</strong>@if (v.status === 'FINAL') { <span class="badge ok">final</span> }
                          <div class="small muted">{{ v.createdAt | brDate: true }} · {{ v.createdByName }} · {{ v.words }} palavras</div>
                          @if (v.note) { <div class="small">{{ v.note }}</div> }
                        </div>
                        <button mat-icon-button (click)="viewVersion(v)" matTooltip="Ver / restaurar" [attr.aria-label]="'Ver versão ' + v.version"><mat-icon>visibility</mat-icon></button>
                      </li>
                    }
                  </ul>
                </div>
              </mat-tab>
            </mat-tab-group>
          }
        </aside>
      </div>
    </div>
  `,
  styles: `
    :host { display: block; }
    .ed { display: flex; flex-direction: column; height: calc(100vh - 64px); padding: 12px 16px 16px; box-sizing: border-box; gap: 8px; }
    .ed-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .ed-title { flex: 1 1 260px; min-width: 0; font: 600 18px/1.3 inherit; color: var(--app-text); border: 1px solid transparent; border-radius: 8px; padding: 6px 8px; background: transparent; }
    .ed-title:hover, .ed-title:focus { border-color: var(--app-border); background: var(--app-surface); outline: 0; }
    .badge.ok { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .small { font-size: 12px; }
    .err { color: var(--app-danger); }
    .danger { color: var(--app-danger); }
    .ed-banner { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 6px 12px; border-radius: 10px; background: var(--app-success-bg, #e3f4e5); color: var(--app-text); font-size: 13px; }
    .ed-banner.warn { background: var(--app-warning-bg); }
    .ed-banner mat-icon { color: var(--app-gold); }
    .ed-body { flex: 1; display: flex; gap: 12px; min-height: 0; }
    .ed-editor { flex: 1; min-width: 0; height: 100%; }
    .ed-side { width: 52px; flex: none; position: relative; overflow: hidden; display: flex; flex-direction: column; transition: width .15s; }
    .ed-side.open { width: 330px; overflow: auto; }
    .ed-side-toggle { align-self: flex-start; margin: 4px; }
    .side-pad { padding: 12px; display: flex; flex-direction: column; gap: 10px; }
    .side-pad h4 { margin: 8px 0 0; font-size: 12px; text-transform: uppercase; letter-spacing: .06em; color: var(--app-text-muted); }
    .info { margin-top: 8px; }
    .versions { list-style: none; margin: 0; padding: 0; }
    .versions li { display: flex; align-items: center; gap: 6px; padding: 8px 0; border-bottom: 1px solid var(--app-border); }
    .vmain { flex: 1; min-width: 0; }
    @media (max-width: 900px) {
      .ed { height: auto; min-height: calc(100vh - 120px); padding: 8px; }
      .ed-body { flex-direction: column; }
      .ed-editor { height: 75vh; }
      .ed-side, .ed-side.open { width: 100%; }
      .hide-sm { display: none; }
    }
  `,
})
export class EditorPageComponent implements OnDestroy {
  private readonly service = inject(LegalEditorService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly clients = inject(ClientsService);
  private readonly cases = inject(CasesService);
  private readonly editor = viewChild.required(RichTextEditorComponent);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');
  readonly labels = KIND_LABELS;
  readonly kinds = Object.keys(KIND_LABELS) as LegalDocKind[];

  readonly draft = signal<Draft | null>(null);
  readonly title = signal('');
  readonly kind = signal<LegalDocKind>('OUTRO');
  readonly header = signal<string | null>(null);
  readonly footer = signal<string | null>(null);
  readonly pageNumbers = signal(true);
  readonly letterheads = signal<Letterhead[]>([]);
  readonly letterheadId = signal('');
  readonly letterheadUrl = signal<string | null>(null);
  readonly letterheadMargins = computed(() => {
    const l = this.letterheads().find((x) => x.id === this.letterheadId());
    return l ? { top: l.marginTop, bottom: l.marginBottom, left: l.marginLeft, right: l.marginRight } : null;
  });
  readonly missing = signal<string[]>([]);
  readonly catalog = signal<FieldInfo[]>([]);
  readonly values = signal<Record<string, string | null>>({});
  readonly versions = signal<VersionSummary[]>([]);
  readonly saveState = signal<SaveState>('saved');
  readonly savedAt = signal<string | null>(null);
  readonly busy = signal(false);
  readonly sideOpen = signal(typeof window !== 'undefined' && window.innerWidth > 1200);
  readonly clientOptions = signal<Option[]>([]);
  readonly caseOptions = signal<Option[]>([]);
  readonly locked = computed(() => !this.canWrite || this.draft()?.status === 'FINAL');
  readonly missingLabels = computed(() => {
    const byKey = new Map(this.catalog().map((f) => [f.key, `${f.group}: ${f.label}`]));
    return this.missing().map((k) => byKey.get(k) ?? k).join(', ');
  });
  readonly clientSearch = new FormControl<Option | string>('');
  readonly caseSearch = new FormControl<Option | string>('');
  private content = '';
  private readonly save$ = new Subject<void>();
  private sideTab = 0;

  constructor() {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.busy.set(true);
    this.service.get(id).subscribe({
      next: (d) => {
        this.apply(d, true);
        this.busy.set(false);
        this.loadFields();
        this.loadLetterheads();
      },
      error: () => { this.busy.set(false); void this.router.navigate(['/editor-juridico']); },
    });
    this.save$.pipe(debounceTime(2500), takeUntilDestroyed()).subscribe(() => this.save());
    this.clientSearch.valueChanges.pipe(
      debounceTime(250), filter((v): v is string => typeof v === 'string' && v.trim().length >= 2),
      switchMap((t) => this.clients.list({ search: t.trim() }, null, 8).pipe(catchError(() => of({ items: [] as ClientSummary[], nextCursor: null })))),
      takeUntilDestroyed(),
    ).subscribe((p) => this.clientOptions.set(p.items.map((c) => ({ id: c.id, label: `${c.name} · ${c.documentMasked}` }))));
    this.caseSearch.valueChanges.pipe(
      debounceTime(250), filter((v): v is string => typeof v === 'string' && v.trim().length >= 2),
      switchMap((t) => this.cases.list({ search: t.trim() }, null, 8).pipe(catchError(() => of({ items: [] as LegalCase[], nextCursor: null })))),
      takeUntilDestroyed(),
    ).subscribe((p) => this.caseOptions.set(p.items.map((c) => ({ id: c.id, label: `${c.cnjNumber ?? 'Sem CNJ'} · ${c.clientName}` }))));
  }

  display = (v: Option | string | null): string => (typeof v === 'string' ? v : v?.label ?? '');

  private apply(d: Draft, replaceContent: boolean): void {
    this.draft.set(d);
    this.missing.set(d.missingFields);
    if (replaceContent) {
      this.title.set(d.title);
      this.kind.set(d.kind);
      this.header.set(d.header);
      this.footer.set(d.footer);
      this.pageNumbers.set(d.pageNumbers);
      this.letterheadId.set(d.letterheadId ?? '');
      this.refreshLetterheadImage();
      this.content = d.content;
      this.editor().setHtml(d.content);
      this.clientSearch.setValue(d.clientId ? { id: d.clientId, label: d.clientName ?? 'Cliente' } : '', { emitEvent: false });
      this.caseSearch.setValue(d.caseId ? { id: d.caseId, label: d.caseNumber ?? 'Processo sem CNJ' } : '', { emitEvent: false });
    }
    this.savedAt.set(new Date(d.updatedAt).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }));
  }

  private loadLetterheads(): void {
    this.service.letterheads().subscribe((list) => {
      this.letterheads.set(list);
      if (this.letterheadId() && !list.some((l) => l.id === this.letterheadId())) this.letterheadId.set('');
      this.refreshLetterheadImage();
    });
  }

  private refreshLetterheadImage(): void {
    const id = this.letterheadId();
    if (!id) {
      this.letterheadUrl.set(null);
      return;
    }
    this.service.letterheadImage(id).subscribe({ next: (url) => { if (this.letterheadId() === id) this.letterheadUrl.set(url); }, error: () => this.letterheadUrl.set(null) });
  }

  setLetterhead(id: string): void {
    this.letterheadId.set(id);
    this.refreshLetterheadImage();
    // Com timbrado, o cabeçalho de texto costuma ser redundante.
    if (id && this.header()) this.toast.info('O cabeçalho de texto continua sendo impresso — apague-o se o papel timbrado já identifica o escritório.');
    this.markDirty();
  }

  manageLetterheads(): void {
    this.dialog.open(LetterheadDialogComponent, { width: '960px', maxWidth: '98vw' }).afterClosed().subscribe(() => this.loadLetterheads());
  }

  private loadFields(): void {
    const d = this.draft();
    if (!d) return;
    this.service.fields(d.clientId, d.caseId, d.lawyerUid).subscribe((f) => {
      this.catalog.set(f.catalog);
      this.values.set(f.values);
    });
  }

  onContent(html: string): void {
    this.content = html;
    this.markDirty();
  }

  markDirty(): void {
    if (this.locked()) return;
    this.saveState.set('dirty');
    this.save$.next();
  }

  private body() {
    const d = this.draft()!;
    return {
      title: this.title().trim() || 'Documento sem título', kind: this.kind(), content: this.content, header: this.header() || null,
      footer: this.footer() || null, pageNumbers: this.pageNumbers(), clientId: d.clientId, caseId: d.caseId,
      letterheadId: this.letterheadId(),
    };
  }

  save(after?: () => void): void {
    const d = this.draft();
    if (!d || this.locked() || this.saveState() === 'saved') { after?.(); return; }
    this.saveState.set('saving');
    this.service.update(d.id, this.body()).subscribe({
      next: (r) => {
        this.apply(r, false);
        if (this.saveState() === 'saving') this.saveState.set('saved');
        after?.();
      },
      error: () => this.saveState.set('error'),
    });
  }

  relink(clientId: string | null, caseId: string | null): void {
    const d = this.draft();
    if (!d || this.locked()) return;
    this.busy.set(true);
    this.service.update(d.id, { ...this.body(), clientId, caseId }).subscribe({
      next: (r) => {
        this.apply(r, false);
        this.clientSearch.setValue(r.clientId ? { id: r.clientId, label: r.clientName ?? 'Cliente' } : '', { emitEvent: false });
        this.caseSearch.setValue(r.caseId ? { id: r.caseId, label: r.caseNumber ?? 'Processo sem CNJ' } : '', { emitEvent: false });
        this.saveState.set('saved');
        this.busy.set(false);
        this.loadFields();
        this.toast.success('Vínculos atualizados — use “Preencher com os dados atuais” para completar os campos.');
      },
      error: () => this.busy.set(false),
    });
  }

  /** Substitui marcadores [grupo.campo] pelos dados atuais do cliente/processo/escritório. */
  fillPending(): void {
    const d = this.draft();
    if (!d) return;
    this.service.fields(d.clientId, d.caseId, d.lawyerUid).subscribe((f) => {
      this.values.set(f.values);
      let filled = 0;
      this.editor().element().querySelectorAll('span.field').forEach((span) => {
        const key = (span.textContent ?? '').replace(/^\[|\]$/g, '');
        const value = f.values[key];
        if (value) {
          span.replaceWith(document.createTextNode(value));
          filled++;
        }
      });
      if (filled) {
        this.editor().notifyChange();
        this.toast.success(`${filled} campo(s) preenchido(s).`);
      } else {
        this.toast.info('Nenhum dos campos pendentes tem dado cadastrado — complete o cadastro do cliente/processo ou edite o texto.');
      }
    });
  }

  snapshot(): void {
    const d = this.draft();
    if (!d) return;
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Salvar versão', message: 'Registra o conteúdo atual no histórico de versões.', confirmLabel: 'Salvar versão', reasonLabel: 'Observação (opcional)', reasonRequired: false },
      width: '440px',
    }).afterClosed().subscribe((r) => {
      if (r === false || r === undefined || r === null) return;
      this.save(() => this.service.saveVersion(d.id, typeof r === 'string' ? r.trim() || null : null).subscribe((x) => {
        this.apply(x, false);
        this.toast.success(`Versão ${x.version} registrada.`);
        if (this.sideTab === 1) this.loadVersions();
      }));
    });
  }

  finalize(): void {
    const d = this.draft();
    if (!d) return;
    const pending = this.missing().length;
    this.dialog.open(ConfirmDialogComponent, {
      data: {
        title: 'Finalizar documento',
        message: (pending ? `Atenção: ainda há ${pending} campo(s) sem dado. ` : '') + 'O documento ficará bloqueado para edição e a versão final será registrada no histórico.',
        confirmLabel: 'Finalizar',
      },
      width: '460px',
    }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.save(() => this.service.finalize(d.id).subscribe((x) => {
        this.apply(x, false);
        this.saveState.set('saved');
        this.toast.success('Documento finalizado.');
        if (this.sideTab === 1) this.loadVersions();
      }));
    });
  }

  reopen(): void {
    const d = this.draft();
    if (!d) return;
    this.service.reopen(d.id).subscribe((x) => { this.apply(x, false); this.toast.success('Documento reaberto para edição.'); });
  }

  download(format: 'pdf' | 'docx'): void {
    const d = this.draft();
    if (!d) return;
    this.busy.set(true);
    this.save(() => this.service.export(d.id, format).subscribe({
      next: (r) => { saveBlob(r, `${this.title() || 'documento'}.${format}`); this.busy.set(false); },
      error: () => this.busy.set(false),
    }));
  }

  /** Imprime exatamente o PDF gerado (papel timbrado, cabeçalho, rodapé e numeração). */
  print(): void {
    const d = this.draft();
    if (!d) return;
    this.busy.set(true);
    this.save(() => this.service.export(d.id, 'pdf').subscribe({
      next: (r) => {
        printPdf(r.body as Blob, this.title()).catch(() => this.toast.error('Não foi possível preparar a impressão.'))
          .finally(() => this.busy.set(false));
      },
      error: () => this.busy.set(false),
    }));
  }

  toCentral(format: 'pdf' | 'docx'): void {
    const d = this.draft();
    if (!d) return;
    this.busy.set(true);
    this.save(() => this.service.saveToCentral(d.id, format).subscribe({
      next: (doc) => {
        this.busy.set(false);
        this.draft.update((x) => (x ? { ...x, storedDocumentId: doc.id } : x));
        this.toast.success(`Salvo na central de documentos (${doc.categoryLabel}, v${doc.currentVersion}).`);
      },
      error: () => this.busy.set(false),
    }));
  }

  duplicate(): void {
    const d = this.draft();
    if (!d) return;
    this.save(() => this.service.duplicate(d.id).subscribe((copy) => {
      this.toast.success('Documento duplicado.');
      void this.router.navigate(['/editor-juridico', copy.id]).then(() => this.apply(copy, true));
    }));
  }

  saveAsTemplate(): void {
    // Dados do cliente viram campos novamente para o modelo ser reutilizável.
    let html = this.content;
    const values = this.values();
    for (const [key, value] of Object.entries(values)) {
      if (value && value.length >= 4 && key !== 'advogado.uid') html = html.split(escapeHtml(value)).join(`{{${key}}}`);
    }
    html = html.replace(/<span class="field">\[([A-Za-z0-9_.]+)\]<\/span>/g, '{{$1}}');
    this.dialog.open(LegalTemplateDialogComponent, {
      data: { content: html, kind: this.kind(), name: this.title() }, width: '1000px', maxWidth: '98vw',
    }).afterClosed().subscribe((t) => { if (t) this.toast.success('Modelo do escritório criado.'); });
  }

  remove(): void {
    const d = this.draft();
    if (!d) return;
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir rascunho', message: `Excluir “${d.title}” e todas as versões? Esta ação não pode ser desfeita.`, confirmLabel: 'Excluir', danger: true },
      width: '440px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.delete(d.id).subscribe(() => {
        this.saveState.set('saved');
        this.toast.success('Rascunho excluído.');
        void this.router.navigate(['/editor-juridico']);
      });
    });
  }

  onSideTab(index: number): void {
    this.sideTab = index;
    if (index === 1) this.loadVersions();
  }

  private loadVersions(): void {
    const d = this.draft();
    if (d) this.service.versions(d.id).subscribe((v) => this.versions.set(v));
  }

  viewVersion(v: VersionSummary): void {
    const d = this.draft();
    if (!d) return;
    this.service.version(d.id, v.version).subscribe((full) => {
      this.dialog.open(LegalVersionDialogComponent, {
        data: { ...full, canRestore: !this.locked() && v.version !== d.version }, width: '960px', maxWidth: '98vw',
      }).afterClosed().subscribe((restore) => {
        if (!restore) return;
        this.save(() => this.service.restore(d.id, v.version).subscribe((x) => {
          this.apply(x, true);
          this.saveState.set('saved');
          this.toast.success(`Versão ${v.version} restaurada (registrada como v${x.version}).`);
          this.loadVersions();
        }));
      });
    });
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (this.saveState() === 'dirty' || this.saveState() === 'saving') {
      event.preventDefault();
    }
  }

  ngOnDestroy(): void {
    if (this.saveState() === 'dirty') this.save();
  }
}
