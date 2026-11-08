import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { BoardCard, BoardsService, CARD_PRIORITY_LABELS, CardPriority, CardRequest, MyCard, dueState } from './boards.service';
import { CardDialogComponent } from './card-dialog';

type Scope = 'mine' | 'all';
type State = 'open' | 'overdue' | 'done' | 'all';

/** Módulo Tarefas: lista única das tarefas (cartões de todos os quadros), com criação rápida e conclusão. */
@Component({
  selector: 'app-tasks-page',
  imports: [FormsModule, ReactiveFormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatIconModule,
    MatProgressBarModule, MatSelectModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Escritório" title="Tarefas" subtitle="Todas as tarefas da equipe em uma lista — as mesmas dos quadros Kanban.">
        <a mat-stroked-button routerLink="/quadros"><mat-icon>view_kanban</mat-icon>Ver em quadros</a>
      </app-page-header>

      <form class="card quick" [formGroup]="form" (ngSubmit)="add()">
        <mat-icon>add_task</mat-icon>
        <input class="qi grow" formControlName="title" placeholder="Nova tarefa — ex.: Ligar para o cliente sobre documentos" aria-label="Título da nova tarefa" />
        <input class="qi" type="date" formControlName="dueDate" aria-label="Vencimento" />
        <mat-select class="qs" formControlName="priority" aria-label="Prioridade">
          @for (p of priorityKeys; track p) { <mat-option [value]="p">{{ priorities[p] }}</mat-option> }
        </mat-select>
        <mat-select class="qs" formControlName="assignee" aria-label="Responsável" placeholder="Responsável">
          @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
        </mat-select>
        <button mat-flat-button type="submit" [disabled]="form.invalid || saving()">Adicionar</button>
        @if (context(); as c) { <span class="ctx muted small">vinculada a {{ c }}</span> }
      </form>

      <div class="card">
        <div class="toolbar">
          <mat-button-toggle-group [value]="scope()" (change)="scope.set($event.value); load()" aria-label="Escopo">
            <mat-button-toggle value="mine">Minhas</mat-button-toggle>
            <mat-button-toggle value="all">Da equipe</mat-button-toggle>
          </mat-button-toggle-group>
          <mat-button-toggle-group [value]="state()" (change)="state.set($event.value); load()" aria-label="Situação">
            <mat-button-toggle value="open">Abertas</mat-button-toggle>
            <mat-button-toggle value="overdue">Atrasadas</mat-button-toggle>
            <mat-button-toggle value="done">Concluídas</mat-button-toggle>
            <mat-button-toggle value="all">Todas</mat-button-toggle>
          </mat-button-toggle-group>
          <div class="search"><mat-icon>search</mat-icon>
            <input type="search" placeholder="Filtrar" [ngModel]="term()" (ngModelChange)="term.set($event)" aria-label="Filtrar tarefas" /></div>
          @if (context()) { <a mat-button routerLink="/tarefas"><mat-icon>close</mat-icon>Remover vínculo</a> }
        </div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && filtered().length === 0) {
          <app-empty-state icon="task_alt" title="Nenhuma tarefa aqui" text="Crie uma tarefa acima ou pelos quadros Kanban." />
        } @else {
          <ul class="list">
            @for (t of filtered(); track t.card.id) {
              <li [class.done]="t.card.completed">
                <mat-checkbox [checked]="t.card.completed" (change)="toggle(t, $event.checked)" [attr.aria-label]="'Concluir ' + t.card.title" />
                <button class="body" type="button" (click)="open(t)">
                  <span class="title">{{ t.card.title }}</span>
                  <span class="meta">
                    <span class="pill" [style.background]="t.boardColor || '#12305a'"></span>{{ t.boardName }} · {{ t.columnName }}
                    @if (t.card.clientName) { · {{ t.card.clientName }} }
                    @if (t.card.caseNumber) { · <span class="mono">{{ t.card.caseNumber }}</span> }
                    @if (t.card.checklist.length) { · {{ checkDone(t.card) }}/{{ t.card.checklist.length }} itens }
                  </span>
                </button>
                @if (t.card.priority === 'URGENTE' || t.card.priority === 'ALTA') { <span class="prio" [class]="t.card.priority">{{ priorities[t.card.priority] }}</span> }
                @if (t.card.dueDate) { <span class="due" [class]="due(t.card)">{{ t.card.dueDate | brDate }}</span> }
              </li>
            }
          </ul>
        }
      </div>
    </div>
  `,
  styles: `
    .quick { display: flex; gap: 8px; align-items: center; padding: 10px 14px; margin-bottom: 16px; flex-wrap: wrap; }
    .quick > mat-icon { color: var(--app-gold); }
    .qi { border: 1px solid var(--app-border); border-radius: 8px; padding: 9px 10px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .qi.grow { flex: 1 1 260px; min-width: 0; }
    .qs { width: 150px; border: 1px solid var(--app-border); border-radius: 8px; padding: 8px 10px; box-sizing: border-box; background: var(--app-surface-2); }
    .ctx { flex-basis: 100%; }
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .search { flex: 1 1 200px; display: flex; align-items: center; gap: 6px; border: 1px solid var(--app-border); border-radius: 10px; padding: 0 10px; height: 38px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .list { list-style: none; margin: 0; padding: 0; }
    .list li { display: flex; gap: 10px; align-items: center; padding: 10px 16px; border-bottom: 1px solid var(--app-border); }
    .list li.done .title { text-decoration: line-through; color: var(--app-text-muted); }
    .body { flex: 1; min-width: 0; text-align: left; border: 0; background: none; color: inherit; font: inherit; cursor: pointer; display: flex; flex-direction: column; gap: 2px; padding: 0; }
    .title { font-weight: 600; }
    .meta { font-size: 12px; color: var(--app-text-muted); }
    .pill { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
    .prio { font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 6px; }
    .prio.URGENTE { background: var(--app-danger-bg); color: var(--app-danger); }
    .prio.ALTA { background: var(--app-warning-bg); color: var(--app-warning); }
    .due { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--app-surface-2); white-space: nowrap; }
    .due.overdue { background: var(--app-danger-bg); color: var(--app-danger); }
    .due.today, .due.soon { background: var(--app-warning-bg); color: var(--app-warning); }
    .small { font-size: 12px; }
  `,
})
export class TasksPageComponent {
  private readonly service = inject(BoardsService);
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  readonly priorities = CARD_PRIORITY_LABELS;
  readonly priorityKeys = Object.keys(CARD_PRIORITY_LABELS) as CardPriority[];
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly scope = signal<Scope>('mine');
  readonly state = signal<State>('open');
  readonly term = signal('');
  readonly items = signal<MyCard[]>([]);
  readonly loading = signal(false);
  readonly saving = signal(false);
  private readonly clientId = this.route.snapshot.queryParamMap.get('clientId');
  private readonly caseId = this.route.snapshot.queryParamMap.get('caseId');
  readonly context = signal<string | null>(this.route.snapshot.queryParamMap.get('label')
    ?? (this.clientId ? 'cliente' : this.caseId ? 'processo' : null));

  readonly form = inject(FormBuilder).nonNullable.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    dueDate: [''],
    priority: ['MEDIA' as CardPriority],
    assignee: [''],
  });

  readonly filtered = computed(() => {
    const t = this.term().trim().toLowerCase();
    return this.items().filter((m) => !t || [m.card.title, m.boardName, m.card.clientName, m.card.caseNumber]
      .some((x) => (x ?? '').toLowerCase().includes(t)));
  });

  constructor() {
    if (this.clientId || this.caseId) this.scope.set('all');
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.get<MyCard[]>('/tasks', { scope: this.scope(), state: this.state(), clientId: this.clientId, caseId: this.caseId }).subscribe({
      next: (list) => { this.items.set(list); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  add(): void {
    const v = this.form.getRawValue();
    if (!v.title.trim()) return;
    const body: CardRequest = {
      title: v.title.trim(), priority: v.priority, dueDate: v.dueDate || null, assigneeUids: v.assignee ? [v.assignee] : [],
      labelIds: [], checklist: [], clientId: this.clientId, caseId: this.caseId,
    };
    this.saving.set(true);
    (this.api.post('/tasks', body) as Observable<BoardCard>).subscribe({
      next: () => {
        this.toast.success('Tarefa criada.');
        this.form.reset({ title: '', dueDate: '', priority: 'MEDIA', assignee: '' });
        this.saving.set(false);
        this.load();
      },
      error: () => this.saving.set(false),
    });
  }

  toggle(t: MyCard, done: boolean): void {
    this.api.post<BoardCard>(`/tasks/${encodeURIComponent(t.card.id)}/${done ? 'complete' : 'reopen'}`, {}).subscribe({
      next: () => { this.toast.success(done ? 'Tarefa concluída.' : 'Tarefa reaberta.'); this.load(); },
      error: () => this.load(),
    });
  }

  open(t: MyCard): void {
    this.service.view(t.card.boardId).subscribe((v) => {
      this.dialog.open(CardDialogComponent, { data: { board: v.board, card: t.card }, width: '1080px', maxWidth: '96vw' })
        .afterClosed().subscribe(() => this.load());
    });
  }

  checkDone(card: BoardCard): number {
    return card.checklist.filter((i) => i.done).length;
  }

  due(card: BoardCard): string {
    return dueState(card.dueDate, card.completed);
  }
}
