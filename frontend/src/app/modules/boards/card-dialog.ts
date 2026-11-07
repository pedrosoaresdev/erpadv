import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ClientSummary, CursorPage, DeadlineType, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { DEADLINE_TYPE_LABELS } from '../../shared/labels';
import { RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { Board, BoardCard, BoardsService, CARD_PRIORITY_LABELS, CardActivity, CardPriority, CardRequest } from './boards.service';

export interface CardDialogData {
  board: Board;
  card?: BoardCard;
  columnId?: string;
}

/** Resultado devolvido ao quadro: cartão salvo/arquivado ou nada. */
export type CardDialogResult = { card: BoardCard } | undefined;

interface LinkOption { id: string; label: string; sub: string }

// ======================================================================= cartão
@Component({
  selector: 'app-card-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatCheckboxModule, MatAutocompleteModule, MatProgressBarModule, MatTooltipModule, RouterLink, RelativeTimePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title">
      <mat-icon>{{ card() ? 'style' : 'add_card' }}</mat-icon>{{ card() ? 'Cartão' : 'Novo cartão' }}
      <span class="spacer"></span>
      @if (card()?.completed) { <span class="badge success">Concluído</span> }
      @if (card()?.deadlineId) { <a class="badge info" routerLink="/prazos" mat-dialog-close>Prazo vinculado</a> }
    </h2>
    <mat-dialog-content>
      <div class="cols">
        <div class="main">
          <form [formGroup]="form" class="form-grid" id="cardForm" (ngSubmit)="save()">
            <mat-form-field appearance="outline">
              <mat-label>Título</mat-label>
              <input matInput formControlName="title" placeholder="Ex.: Contestação — Silva x Banco" />
              @if (form.controls.title.invalid) { <mat-error>Obrigatório</mat-error> }
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Descrição</mat-label>
              <textarea matInput rows="4" formControlName="description" placeholder="Contexto, teses, documentos necessários…"></textarea>
            </mat-form-field>
            @if (!card()) {
              <mat-form-field appearance="outline" class="c6">
                <mat-label>Coluna</mat-label>
                <mat-select formControlName="columnId">
                  @for (c of data.board.columns; track c.id) { <mat-option [value]="c.id">{{ c.name }}</mat-option> }
                </mat-select>
              </mat-form-field>
            }
            <mat-form-field appearance="outline" class="c6">
              <mat-label>Prioridade</mat-label>
              <mat-select formControlName="priority">
                @for (p of priorityKeys; track p) { <mat-option [value]="p">{{ priorities[p] }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="c6">
              <mat-label>Responsáveis</mat-label>
              <mat-select formControlName="assigneeUids" multiple>
                @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="c6">
              <mat-label>Etiquetas</mat-label>
              <mat-select formControlName="labelIds" multiple>
                @for (l of data.board.labels; track l.id) {
                  <mat-option [value]="l.id"><span class="dot" [style.background]="l.color"></span>{{ l.name }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            <mat-form-field appearance="outline" class="c4">
              <mat-label>Início</mat-label>
              <input matInput type="date" formControlName="startDate" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="c4">
              <mat-label>Vencimento</mat-label>
              <input matInput type="date" formControlName="dueDate" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="c4">
              <mat-label>Estimativa (h)</mat-label>
              <input matInput type="number" min="0" max="1000" formControlName="estimateHours" />
            </mat-form-field>
            <mat-form-field appearance="outline" class="c6">
              <mat-label>Cliente</mat-label>
              <input matInput [formControl]="clientSearch" [matAutocomplete]="clientAuto" placeholder="Buscar por nome ou CPF/CNPJ" />
              @if (form.controls.clientId.value) {
                <button mat-icon-button matSuffix type="button" (click)="clearClient()" aria-label="Remover cliente"><mat-icon>close</mat-icon></button>
              }
              <mat-autocomplete #clientAuto="matAutocomplete" (optionSelected)="pickClient($event.option.value)" [displayWith]="label">
                @for (o of clientOptions(); track o.id) { <mat-option [value]="o">{{ o.label }} <span class="muted small">{{ o.sub }}</span></mat-option> }
              </mat-autocomplete>
            </mat-form-field>
            <mat-form-field appearance="outline" class="c6">
              <mat-label>Processo</mat-label>
              <input matInput [formControl]="caseSearch" [matAutocomplete]="caseAuto" placeholder="Buscar por CNJ ou parte" />
              @if (form.controls.caseId.value) {
                <button mat-icon-button matSuffix type="button" (click)="clearCase()" aria-label="Remover processo"><mat-icon>close</mat-icon></button>
              }
              <mat-autocomplete #caseAuto="matAutocomplete" (optionSelected)="pickCase($event.option.value)" [displayWith]="label">
                @for (o of caseOptions(); track o.id) { <mat-option [value]="o">{{ o.label }} <span class="muted small">{{ o.sub }}</span></mat-option> }
              </mat-autocomplete>
            </mat-form-field>
          </form>

          <div class="form-section-title row">
            Checklist
            <span class="spacer"></span>
            @if (checklist().length) { <span class="muted small">{{ doneCount() }}/{{ checklist().length }}</span> }
          </div>
          @if (checklist().length) { <mat-progress-bar mode="determinate" [value]="progress()" class="progress" /> }
          @for (item of checklist(); track item.key; let i = $index) {
            <div class="check">
              <mat-checkbox [checked]="item.done" (change)="toggleItem(i, $event.checked)" />
              <input class="check-input" [value]="item.text" (input)="renameItem(i, $event)" [class.done]="item.done" aria-label="Item do checklist" />
              <button mat-icon-button type="button" (click)="removeItem(i)" aria-label="Remover item"><mat-icon>close</mat-icon></button>
            </div>
          }
          <div class="row add-item">
            <input class="check-input" placeholder="Adicionar item (Enter)" [formControl]="newItem" (keydown.enter)="$event.preventDefault(); addItem()" aria-label="Novo item do checklist" />
            <button mat-button type="button" (click)="addItem()"><mat-icon>add</mat-icon>Adicionar</button>
          </div>
        </div>

        @if (card()) {
          <div class="side">
            <div class="form-section-title">Atividade</div>
            <div class="comment-box">
              <textarea [formControl]="comment" rows="3" placeholder="Escreva um comentário…" aria-label="Comentário"></textarea>
              <div class="row">
                <mat-form-field appearance="outline" class="mention" subscriptSizing="dynamic">
                  <mat-label>Mencionar</mat-label>
                  <mat-select [formControl]="mentions" multiple>
                    @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
                  </mat-select>
                </mat-form-field>
                <button mat-flat-button type="button" (click)="send()" [disabled]="!comment.value.trim() || sending()">Comentar</button>
              </div>
            </div>
            <div class="activity">
              @for (a of activity(); track a.id) {
                <div class="act" [class.comment]="a.kind === 'COMMENT'">
                  <span class="av">{{ initials(a.actorName) }}</span>
                  <div>
                    <div><strong>{{ a.actorName }}</strong> @if (a.kind === 'EVENT') { <span>{{ a.text }}</span> }</div>
                    @if (a.kind === 'COMMENT') { <div class="bubble">{{ a.text }}</div> }
                    <div class="muted small">{{ a.at | relative }}</div>
                  </div>
                </div>
              }
            </div>
          </div>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (card(); as c) {
        @if (auth.has('DEADLINES_WRITE') && !c.deadlineId) {
          <button mat-stroked-button type="button" (click)="toDeadline(c)" matTooltip="Cria um prazo processual com contagem do calendário forense">
            <mat-icon>alarm_add</mat-icon>Gerar prazo
          </button>
        }
        <button mat-button type="button" (click)="archive(c)"><mat-icon>{{ c.archived ? 'unarchive' : 'archive' }}</mat-icon>{{ c.archived ? 'Restaurar' : 'Arquivar' }}</button>
      }
      <span class="spacer"></span>
      <button mat-button mat-dialog-close type="button">Fechar</button>
      <button mat-flat-button type="submit" form="cardForm" [disabled]="saving()">{{ card() ? 'Salvar' : 'Criar cartão' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 78vh; }
    .cols { display: grid; grid-template-columns: minmax(0, 1fr); gap: 24px; }
    .cols:has(.side) { grid-template-columns: minmax(0, 1.4fr) minmax(280px, 1fr); }
    @media (max-width: 900px) { .cols:has(.side) { grid-template-columns: 1fr; } }
    .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }
    .small { font-size: 12px; }
    .progress { margin-bottom: 8px; border-radius: 4px; }
    .check { display: flex; align-items: center; gap: 4px; }
    .check-input { flex: 1; border: 0; border-bottom: 1px dashed transparent; background: transparent; color: var(--app-text); font: inherit; padding: 6px 4px; outline: 0; }
    .check-input:focus { border-bottom-color: var(--app-gold); }
    .check-input.done { text-decoration: line-through; color: var(--app-text-muted); }
    .add-item { margin-top: 4px; }
    .add-item .check-input { border-bottom-color: var(--app-border); }
    .comment-box textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--app-border); border-radius: 10px; padding: 10px; font: inherit;
      background: var(--app-surface-2); color: var(--app-text); resize: vertical; }
    .mention { flex: 1; }
    .activity { margin-top: 12px; display: flex; flex-direction: column; gap: 12px; max-height: 46vh; overflow: auto; }
    .act { display: flex; gap: 10px; font-size: 13px; }
    .av { width: 28px; height: 28px; flex: none; border-radius: 8px; display: grid; place-items: center; font-size: 11px; font-weight: 700;
      background: linear-gradient(135deg, var(--app-navy), var(--app-navy-2)); color: var(--app-gold); }
    .bubble { margin: 4px 0; padding: 8px 10px; border-radius: 10px; background: var(--app-surface-2); border: 1px solid var(--app-border); white-space: pre-wrap; }
    mat-dialog-actions { display: flex; gap: 8px; flex-wrap: wrap; }
  `,
})
export class CardDialogComponent {
  readonly data = inject<CardDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CardDialogComponent>);
  private readonly service = inject(BoardsService);
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly auth = inject(AuthService);
  readonly priorities = CARD_PRIORITY_LABELS;
  readonly priorityKeys = Object.keys(CARD_PRIORITY_LABELS) as CardPriority[];
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly card = signal<BoardCard | null>(this.data.card ?? null);
  readonly activity = signal<CardActivity[]>([]);
  readonly saving = signal(false);
  readonly sending = signal(false);
  private keySeq = 0;

  readonly checklist = signal((this.data.card?.checklist ?? []).map((i) => ({ ...i, key: ++this.keySeq })));
  readonly doneCount = computed(() => this.checklist().filter((i) => i.done).length);
  readonly progress = computed(() => (this.checklist().length ? (this.doneCount() / this.checklist().length) * 100 : 0));

  private readonly c = this.data.card;
  readonly form = inject(FormBuilder).nonNullable.group({
    title: [this.c?.title ?? '', [Validators.required, Validators.maxLength(200)]],
    description: [this.c?.description ?? ''],
    columnId: [this.data.columnId ?? this.data.board.columns[0]?.id ?? ''],
    priority: [(this.c?.priority ?? 'MEDIA') as CardPriority],
    assigneeUids: [this.c?.assigneeUids ?? ([] as string[])],
    labelIds: [this.c?.labelIds ?? ([] as string[])],
    startDate: [this.c?.startDate ?? ''],
    dueDate: [this.c?.dueDate ?? ''],
    estimateHours: [this.c?.estimateHours ?? (null as number | null)],
    clientId: [this.c?.clientId ?? ''],
    caseId: [this.c?.caseId ?? ''],
  });
  readonly clientSearch = new FormControl<string | LinkOption>(this.c?.clientName ?? '', { nonNullable: true });
  readonly caseSearch = new FormControl<string | LinkOption>(this.c?.caseNumber ?? '', { nonNullable: true });
  readonly newItem = new FormControl('', { nonNullable: true });
  readonly comment = new FormControl('', { nonNullable: true });
  readonly mentions = new FormControl<string[]>([], { nonNullable: true });

  readonly clientOptions = toSignal(
    this.clientSearch.valueChanges.pipe(
      debounceTime(250), map((v) => (typeof v === 'string' ? v.trim() : '')), distinctUntilChanged(),
      switchMap((term) => term.length < 2 || !this.auth.has('CLIENTS_READ') ? of([]) :
        this.api.get<CursorPage<ClientSummary>>('/clients', { search: term, size: 8 }, true).pipe(
          map((p) => p.items.map((c) => ({ id: c.id, label: c.name, sub: c.documentMasked ?? '' }))), catchError(() => of([])))),
    ), { initialValue: [] as LinkOption[] });

  readonly caseOptions = toSignal(
    this.caseSearch.valueChanges.pipe(
      debounceTime(250), map((v) => (typeof v === 'string' ? v.trim() : '')), distinctUntilChanged(),
      switchMap((term) => term.length < 2 || !this.auth.has('CASES_READ') ? of([]) :
        this.api.get<CursorPage<LegalCase>>('/cases', { search: term, size: 8 }, true).pipe(
          map((p) => p.items.map((c) => ({ id: c.id, label: c.cnjNumber ?? 'Sem CNJ', sub: c.clientName }))), catchError(() => of([])))),
    ), { initialValue: [] as LinkOption[] });

  constructor() {
    if (this.c) {
      this.service.card(this.c.id).pipe(takeUntilDestroyed()).subscribe((d) => {
        this.card.set(d.card);
        this.activity.set(d.activity);
      });
    }
  }

  label = (o: LinkOption | string | null): string => (typeof o === 'string' ? o : o?.label ?? '');

  initials(name: string): string {
    return (name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  }

  pickClient(o: LinkOption): void {
    this.form.controls.clientId.setValue(o.id);
  }

  clearClient(): void {
    this.form.controls.clientId.setValue('');
    this.clientSearch.setValue('');
  }

  pickCase(o: LinkOption): void {
    this.form.controls.caseId.setValue(o.id);
  }

  clearCase(): void {
    this.form.controls.caseId.setValue('');
    this.caseSearch.setValue('');
  }

  addItem(): void {
    const text = this.newItem.value.trim();
    if (!text) return;
    this.checklist.set([...this.checklist(), { id: '', text, done: false, key: ++this.keySeq }]);
    this.newItem.setValue('');
  }

  toggleItem(i: number, done: boolean): void {
    this.checklist.set(this.checklist().map((x, idx) => (idx === i ? { ...x, done } : x)));
  }

  renameItem(i: number, event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.checklist.set(this.checklist().map((x, idx) => (idx === i ? { ...x, text } : x)));
  }

  removeItem(i: number): void {
    this.checklist.set(this.checklist().filter((_, idx) => idx !== i));
  }

  private request(): CardRequest {
    const v = this.form.getRawValue();
    return {
      columnId: this.card() ? null : v.columnId,
      title: v.title.trim(),
      description: v.description || null,
      assigneeUids: v.assigneeUids,
      labelIds: v.labelIds,
      priority: v.priority,
      startDate: v.startDate || null,
      dueDate: v.dueDate || null,
      estimateHours: v.estimateHours ?? null,
      clientId: v.clientId || null,
      caseId: v.caseId || null,
      checklist: this.checklist().filter((i) => i.text.trim()).map((i) => ({ id: i.id || null, text: i.text.trim(), done: i.done })),
    };
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const current = this.card();
    const request$ = current ? this.service.updateCard(current.id, this.request()) : this.service.createCard(this.data.board.id, this.request());
    request$.subscribe({ next: (card) => this.ref.close({ card }), error: () => this.saving.set(false) });
  }

  send(): void {
    const current = this.card();
    const text = this.comment.value.trim();
    if (!current || !text) return;
    this.sending.set(true);
    this.service.comment(current.id, text, this.mentions.value).subscribe({
      next: (a) => {
        this.activity.set([a, ...this.activity()]);
        this.card.set({ ...current, commentCount: current.commentCount + 1 });
        this.comment.setValue('');
        this.mentions.setValue([]);
        this.sending.set(false);
      },
      error: () => this.sending.set(false),
    });
  }

  archive(c: BoardCard): void {
    this.service.archiveCard(c.id, !c.archived).subscribe((card) => {
      this.toast.success(card.archived ? 'Cartão arquivado.' : 'Cartão restaurado.');
      this.ref.close({ card });
    });
  }

  toDeadline(c: BoardCard): void {
    this.dialog.open(CardDeadlineDialogComponent, { data: c, width: '640px', maxWidth: '96vw' })
      .afterClosed().subscribe((result?: { card: BoardCard; activity: CardActivity[] }) => {
        if (result) {
          this.card.set(result.card);
          this.activity.set(result.activity);
          this.form.controls.dueDate.setValue(result.card.dueDate ?? '');
          this.toast.success('Prazo processual criado e vinculado ao cartão.');
        }
      });
  }
}

// ======================================================================= cartão → prazo processual
@Component({
  selector: 'app-card-deadline-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>alarm_add</mat-icon>Gerar prazo processual</h2>
    <mat-dialog-content>
      <p class="muted small">O vencimento é calculado pelo calendário forense (dias úteis, feriados, recesso e suspensões do tribunal)
        e passa a contar nos alertas automáticos de prazos.</p>
      @if (!data.caseId) { <p class="warn small">Cartão sem processo vinculado: o prazo será criado sem processo.</p> }
      <form [formGroup]="form" class="form-grid" id="dlForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Descrição</mat-label>
          <input matInput formControlName="description" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">
            @for (t of types; track t) { <mat-option [value]="t">{{ typeLabels[t] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Responsável</mat-label>
          <mat-select formControlName="responsibleUid">
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          @if (form.controls.responsibleUid.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Início da contagem</mat-label>
          <input matInput type="date" formControlName="startDate" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Prazo (dias)</mat-label>
          <input matInput type="number" min="1" formControlName="days" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Contagem</mat-label>
          <mat-select formControlName="countingMode">
            <mat-option value="UTEIS">Dias úteis (CPC)</mat-option>
            <mat-option value="CORRIDOS">Dias corridos</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Prioridade</mat-label>
          <mat-select formControlName="priority">
            <mat-option value="BAIXA">Baixa</mat-option>
            <mat-option value="MEDIA">Média</mat-option>
            <mat-option value="ALTA">Alta</mat-option>
            <mat-option value="CRITICA">Crítica</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Tribunal (feriados locais)</mat-label>
          <input matInput formControlName="court" placeholder="Ex.: TJSP" />
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="dlForm" [disabled]="saving()">Gerar prazo</button>
    </mat-dialog-actions>
  `,
  styles: `.small { font-size: 13px; } .warn { color: var(--app-warning); }`,
})
export class CardDeadlineDialogComponent {
  readonly data = inject<BoardCard>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CardDeadlineDialogComponent>);
  private readonly service = inject(BoardsService);
  private readonly auth = inject(AuthService);
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly saving = signal(false);
  readonly typeLabels = DEADLINE_TYPE_LABELS as Record<string, string>;
  readonly types = Object.keys(DEADLINE_TYPE_LABELS) as DeadlineType[];
  readonly form = inject(FormBuilder).nonNullable.group({
    description: [this.data.title, [Validators.required, Validators.maxLength(300)]],
    type: [this.types[0], Validators.required],
    responsibleUid: [this.data.assigneeUids[0] ?? this.auth.me()?.uid ?? '', Validators.required],
    startDate: [new Date().toISOString().slice(0, 10), Validators.required],
    days: [15, [Validators.required, Validators.min(1), Validators.max(3650)]],
    countingMode: ['UTEIS' as 'UTEIS' | 'CORRIDOS'],
    priority: [this.data.priority === 'URGENTE' ? 'CRITICA' : this.data.priority],
    court: [''],
  });

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.toDeadline(this.data.id, {
      ...v, court: v.court || null, caseId: this.data.caseId, clientId: this.data.clientId, notes: 'Gerado a partir do quadro de tarefas.',
    }).subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
