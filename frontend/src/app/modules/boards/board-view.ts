import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragPlaceholder, CdkDropList, CdkDropListGroup } from '@angular/cdk/drag-drop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ChartConfiguration } from 'chart.js';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ChartComponent } from '../../shared/components/chart';
import { BrDatePipe, RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { BoardColumnsDialogComponent, BoardLabelsDialogComponent } from './board-settings';
import { BoardFormDialogComponent } from './boards-list';
import {
  Board, BoardCard, BoardColumn, BoardInsights, BoardView, BoardsService, CARD_PRIORITY_LABELS, CardPriority,
  daysSince, dueState, positionBetween,
} from './boards.service';
import { CardDialogComponent, CardDialogResult } from './card-dialog';

const STALE_DAYS = 7;

@Component({
  selector: 'app-board-view',
  imports: [FormsModule, CdkDropListGroup, CdkDropList, CdkDrag, CdkDragPlaceholder, MatButtonModule, MatButtonToggleModule, MatIconModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTooltipModule, RouterLink, BrDatePipe, RelativeTimePipe, ChartComponent],
  template: `
    @if (view(); as v) {
      <div class="board-page" [style.--c]="v.board.color || '#12305a'">
        <header class="head">
          <a mat-icon-button routerLink="/quadros" aria-label="Voltar para quadros"><mat-icon>arrow_back</mat-icon></a>
          <div class="title-block">
            <h1>{{ v.board.name }} @if (v.board.archived) { <span class="badge">arquivado</span> }</h1>
            <span class="muted small">{{ v.board.description || 'Dono: ' + v.board.ownerName }} · {{ openCount() }} abertos · {{ v.board.visibility === 'MEMBERS' ? 'somente membros' : 'todo o escritório' }}</span>
          </div>
          <span class="spacer"></span>
          <mat-button-toggle-group [value]="mode()" (change)="mode.set($event.value)" aria-label="Modo de exibição">
            <mat-button-toggle value="board" matTooltip="Kanban"><mat-icon>view_kanban</mat-icon></mat-button-toggle>
            <mat-button-toggle value="list" matTooltip="Lista"><mat-icon>view_list</mat-icon></mat-button-toggle>
          </mat-button-toggle-group>
          <button mat-stroked-button (click)="toggleInsights()"><mat-icon>insights</mat-icon>Indicadores</button>
          @if (v.canManage) {
            <button mat-icon-button [matMenuTriggerFor]="settings" aria-label="Configurações do quadro"><mat-icon>tune</mat-icon></button>
            <mat-menu #settings="matMenu" xPosition="before">
              <button mat-menu-item (click)="editBoard(v.board)"><mat-icon>edit</mat-icon>Nome, membros e visibilidade</button>
              <button mat-menu-item (click)="editColumns(v.board)"><mat-icon>view_column</mat-icon>Colunas, WIP e automações</button>
              <button mat-menu-item (click)="editLabels(v.board)"><mat-icon>sell</mat-icon>Etiquetas</button>
              <button mat-menu-item (click)="showArchived()"><mat-icon>inventory_2</mat-icon>Cartões arquivados</button>
            </mat-menu>
          }
          <button mat-flat-button (click)="newCard()" [disabled]="v.board.archived"><mat-icon>add</mat-icon>Cartão</button>
        </header>

        <div class="filters">
          <div class="search">
            <mat-icon>search</mat-icon>
            <input type="search" placeholder="Filtrar cartões, clientes, processos" [ngModel]="term()" (ngModelChange)="term.set($event)" aria-label="Filtrar cartões" />
          </div>
          <mat-select class="fsel" placeholder="Responsável" [ngModel]="assignee()" (ngModelChange)="assignee.set($event)" aria-label="Responsável">
            <mat-option value="">Todos os responsáveis</mat-option>
            <mat-option value="__me">Somente meus</mat-option>
            <mat-option value="__none">Sem responsável</mat-option>
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          <mat-select class="fsel" placeholder="Etiqueta" [ngModel]="label()" (ngModelChange)="label.set($event)" aria-label="Etiqueta">
            <mat-option value="">Todas as etiquetas</mat-option>
            @for (l of v.board.labels; track l.id) { <mat-option [value]="l.id">{{ l.name }}</mat-option> }
          </mat-select>
          <mat-select class="fsel" placeholder="Prioridade" [ngModel]="priority()" (ngModelChange)="priority.set($event)" aria-label="Prioridade">
            <mat-option value="">Todas as prioridades</mat-option>
            @for (p of priorityKeys; track p) { <mat-option [value]="p">{{ priorities[p] }}</mat-option> }
          </mat-select>
          <button class="chip" [class.on]="onlyLate()" (click)="onlyLate.set(!onlyLate())"><mat-icon>alarm</mat-icon>Atrasados</button>
          <button class="chip" [class.on]="onlyStale()" (click)="onlyStale.set(!onlyStale())"><mat-icon>hourglass_bottom</mat-icon>Parados há {{ staleDays }}+ dias</button>
          @if (hasFilters()) { <button mat-button (click)="clearFilters()">Limpar</button> }
        </div>

        @if (insightsOpen() && insights(); as i) {
          <section class="insights card">
            <div class="kpis">
              <div><strong>{{ i.open }}</strong><span>abertos</span></div>
              <div [class.bad]="i.overdue"><strong>{{ i.overdue }}</strong><span>atrasados</span></div>
              <div><strong>{{ i.dueThisWeek }}</strong><span>vencem em 7 dias</span></div>
              <div [class.warn]="i.stale"><strong>{{ i.stale }}</strong><span>parados 7+ dias</span></div>
              <div><strong>{{ i.completed7d }}</strong><span>concluídos na semana</span></div>
              <div><strong>{{ i.avgCycleDays }}</strong><span>dias de ciclo médio</span></div>
            </div>
            <div class="ins-grid">
              <div>
                <div class="card-title"><mat-icon>show_chart</mat-icon>Vazão semanal (concluídos)</div>
                <app-chart [config]="throughputChart()" [height]="170" />
              </div>
              <div>
                <div class="card-title"><mat-icon>tips_and_updates</mat-icon>Alertas inteligentes</div>
                @for (a of i.alerts; track a) { <div class="alert"><mat-icon>bolt</mat-icon>{{ a }}</div> }
                @empty { <p class="muted small">Fluxo saudável: nenhum gargalo, atraso ou cartão sem responsável.</p> }
                <div class="card-title load-title"><mat-icon>groups</mat-icon>Carga por pessoa</div>
                @for (m of i.members; track m.uid) {
                  <div class="load"><span>{{ m.name }}</span><span class="bar"><span [style.width.%]="loadPct(m.open, i)"></span></span>
                    <span class="mono small">{{ m.open }}@if (m.overdue) { <span class="late"> · {{ m.overdue }} atras.</span> }</span></div>
                }
              </div>
            </div>
          </section>
        }

        @if (mode() === 'board') {
          <div class="kanban" cdkDropListGroup>
            @for (col of v.board.columns; track col.id) {
              <section class="column" [class.done-col]="col.done">
                <header class="col-head" [style.borderTopColor]="col.color || 'var(--c)'">
                  <span class="col-name">{{ col.name }}</span>
                  <span class="count" [class.limit]="atLimit(col)" [class.over]="overLimit(col)"
                    [matTooltip]="col.wipLimit ? 'Limite WIP: ' + col.wipLimit : 'Sem limite WIP'">
                    {{ countIn(col.id) }}{{ col.wipLimit ? ' / ' + col.wipLimit : '' }}
                  </span>
                  @if (hasAutomation(col)) { <mat-icon class="auto" [matTooltip]="automationText(col)">bolt</mat-icon> }
                  <span class="spacer"></span>
                  <button mat-icon-button class="mini" (click)="newCard(col.id)" [disabled]="v.board.archived" [attr.aria-label]="'Novo cartão em ' + col.name"><mat-icon>add</mat-icon></button>
                </header>
                <div class="cards" cdkDropList [id]="col.id" [cdkDropListData]="col.id" (cdkDropListDropped)="drop($event)">
                  @for (card of cardsIn(col.id); track card.id) {
                    <article class="kcard" cdkDrag [cdkDragData]="card" (click)="openCard(card)" [class.completed]="card.completed"
                      [class.urgent]="card.priority === 'URGENTE'" tabindex="0" (keydown.enter)="openCard(card)">
                      <div class="kcard-ph" *cdkDragPlaceholder></div>
                      @if (card.labelIds.length) {
                        <div class="labels">@for (l of labelsOf(card); track l.id) { <span class="lab" [style.background]="l.color">{{ l.name }}</span> }</div>
                      }
                      <div class="ktitle">{{ card.title }}</div>
                      @if (card.clientName || card.caseNumber) {
                        <div class="link muted"><mat-icon>link</mat-icon>{{ card.caseNumber || card.clientName }}</div>
                      }
                      <div class="kmeta">
                        @if (card.priority === 'URGENTE' || card.priority === 'ALTA') { <span class="prio" [class]="card.priority">{{ priorities[card.priority] }}</span> }
                        @if (card.dueDate) { <span class="due" [class]="due(card)"><mat-icon>event</mat-icon>{{ card.dueDate | brDate }}</span> }
                        @if (card.checklist.length) {
                          <span class="chk" [class.full]="checkDone(card) === card.checklist.length"><mat-icon>checklist</mat-icon>{{ checkDone(card) }}/{{ card.checklist.length }}</span>
                        }
                        @if (card.commentCount) { <span><mat-icon>chat_bubble_outline</mat-icon>{{ card.commentCount }}</span> }
                        @if (card.deadlineId) { <span matTooltip="Prazo processual vinculado"><mat-icon>gavel</mat-icon></span> }
                        @if (!col.done && age(card) >= staleDays) { <span class="stale" [matTooltip]="'Na coluna há ' + age(card) + ' dias'"><mat-icon>hourglass_bottom</mat-icon>{{ age(card) }}d</span> }
                        <span class="spacer"></span>
                        <span class="avs">@for (uid of card.assigneeUids.slice(0, 3); track uid) { <span class="av" [matTooltip]="nameOf(uid)">{{ initialsOf(uid) }}</span> }</span>
                      </div>
                    </article>
                  } @empty {
                    <div class="empty-col muted">Solte cartões aqui</div>
                  }
                </div>
              </section>
            }
          </div>
        } @else {
          <div class="card list">
            <div class="table-wrap">
              <table class="data">
                <thead><tr><th>Cartão</th><th>Coluna</th><th>Responsáveis</th><th>Prioridade</th><th>Vencimento</th><th>Checklist</th><th>Atividade</th></tr></thead>
                <tbody>
                  @for (card of listCards(); track card.id) {
                    <tr class="clickable" (click)="openCard(card)">
                      <td><strong>{{ card.title }}</strong><div class="muted small">{{ card.caseNumber || card.clientName || '' }}</div></td>
                      <td>{{ columnName(card.columnId) }}</td>
                      <td class="small">{{ assigneeNames(card) || '—' }}</td>
                      <td><span class="prio" [class]="card.priority">{{ priorities[card.priority] }}</span></td>
                      <td>@if (card.dueDate) { <span class="due" [class]="due(card)">{{ card.dueDate | brDate }}</span> } @else { — }</td>
                      <td class="mono">{{ card.checklist.length ? checkDone(card) + '/' + card.checklist.length : '—' }}</td>
                      <td class="muted small">{{ card.lastActivityAt ? (card.lastActivityAt | relative) : '' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        }

        @if (archived(); as list) {
          <section class="card archived">
            <div class="card-title"><mat-icon>inventory_2</mat-icon>Cartões arquivados ({{ list.length }}) <span class="spacer"></span>
              <button mat-icon-button (click)="archived.set(null)" aria-label="Fechar arquivados"><mat-icon>close</mat-icon></button></div>
            @for (c of list; track c.id) {
              <div class="arch-row"><span>{{ c.title }}</span><span class="spacer"></span>
                <button mat-button (click)="restore(c)"><mat-icon>unarchive</mat-icon>Restaurar</button></div>
            } @empty { <p class="muted small">Nenhum cartão arquivado.</p> }
          </section>
        }
      </div>
    } @else {
      <mat-progress-bar mode="indeterminate" />
    }
  `,
  styles: `
    .board-page { padding: 18px 24px 32px; display: flex; flex-direction: column; gap: 14px; min-height: calc(100vh - 64px); box-sizing: border-box; }
    .head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .title-block h1 { font-size: 22px; display: flex; gap: 8px; align-items: center; }
    .small { font-size: 12px; }
    .filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .search { flex: 1 1 240px; display: flex; align-items: center; gap: 8px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 0 12px; height: 38px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .fsel { width: 190px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; }
    .chip { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--app-border); background: var(--app-surface); color: var(--app-text); border-radius: 999px; padding: 6px 12px; cursor: pointer; font: inherit; font-size: 13px; }
    .chip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .chip.on { background: var(--app-gold-soft); border-color: var(--app-gold); }

    .kanban { display: flex; gap: 14px; overflow-x: auto; align-items: flex-start; padding-bottom: 12px; flex: 1; }
    .column { flex: 0 0 296px; background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 14px; display: flex; flex-direction: column; max-height: calc(100vh - 240px); }
    .column.done-col { background: color-mix(in srgb, var(--app-success-bg) 45%, var(--app-surface-2)); }
    .col-head { display: flex; align-items: center; gap: 6px; padding: 10px 10px 8px 14px; border-top: 4px solid; border-radius: 14px 14px 0 0; }
    .col-name { font-weight: 700; font-size: 14px; }
    .count { font-size: 12px; padding: 1px 8px; border-radius: 999px; background: var(--app-surface); border: 1px solid var(--app-border); }
    .count.limit { background: var(--app-warning-bg); color: var(--app-warning); border-color: transparent; }
    .count.over { background: var(--app-danger-bg); color: var(--app-danger); border-color: transparent; }
    .auto { font-size: 16px; width: 16px; height: 16px; color: var(--app-gold); }
    .mini { width: 32px; height: 32px; padding: 4px; }
    .cards { padding: 4px 10px 12px; display: flex; flex-direction: column; gap: 8px; overflow-y: auto; min-height: 60px; flex: 1; }
    .kcard { background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; cursor: pointer; box-shadow: var(--app-shadow);
      transition: box-shadow .12s, transform .12s; border-left: 3px solid transparent; }
    .kcard:hover, .kcard:focus-visible { box-shadow: var(--app-shadow-lg); outline: none; }
    .kcard.urgent { border-left-color: var(--app-danger); }
    .kcard.completed .ktitle { text-decoration: line-through; color: var(--app-text-muted); }
    .kcard-ph { min-height: 56px; border: 2px dashed var(--app-gold); border-radius: 10px; background: var(--app-gold-soft); }
    .cdk-drag-preview.kcard { box-shadow: var(--app-shadow-lg); transform: rotate(2deg); }
    .cdk-drag-animating { transition: transform 200ms cubic-bezier(0, 0, .2, 1); }
    .cards.cdk-drop-list-dragging .kcard:not(.cdk-drag-placeholder) { transition: transform 200ms cubic-bezier(0, 0, .2, 1); }
    .labels { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
    .lab { color: #fff; font-size: 10px; font-weight: 700; padding: 2px 7px; border-radius: 999px; letter-spacing: .2px; }
    .ktitle { font-weight: 600; font-size: 14px; line-height: 1.35; }
    .link { display: flex; align-items: center; gap: 4px; font-size: 12px; margin-top: 4px; }
    .link mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .kmeta { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 12px; color: var(--app-text-muted); flex-wrap: wrap; }
    .kmeta span { display: inline-flex; align-items: center; gap: 2px; }
    .kmeta mat-icon { font-size: 14px; width: 14px; height: 14px; }
    .prio { font-size: 11px; font-weight: 700; padding: 1px 6px; border-radius: 6px; background: var(--app-surface-2); }
    .prio.URGENTE { background: var(--app-danger-bg); color: var(--app-danger); }
    .prio.ALTA { background: var(--app-warning-bg); color: var(--app-warning); }
    .due { padding: 1px 6px; border-radius: 6px; }
    .due.overdue { background: var(--app-danger-bg); color: var(--app-danger); }
    .due.today, .due.soon { background: var(--app-warning-bg); color: var(--app-warning); }
    .due.done { background: var(--app-success-bg); color: var(--app-success); }
    .chk.full { color: var(--app-success); }
    .stale { color: var(--app-warning); }
    .avs { display: inline-flex; }
    .av { width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; font-size: 9px; font-weight: 700; margin-left: -4px;
      background: linear-gradient(135deg, var(--app-navy), var(--app-navy-2)); color: var(--app-gold); border: 2px solid var(--app-surface); }
    .empty-col { font-size: 12px; text-align: center; padding: 18px 0; border: 1px dashed var(--app-border); border-radius: 10px; }

    .insights { padding: 16px; }
    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; }
    .kpis div { background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px 12px; }
    .kpis strong { display: block; font-size: 22px; }
    .kpis span { font-size: 12px; color: var(--app-text-muted); }
    .kpis .bad strong { color: var(--app-danger); }
    .kpis .warn strong { color: var(--app-warning); }
    .ins-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 20px; margin-top: 14px; }
    @media (max-width: 900px) { .ins-grid { grid-template-columns: 1fr; } }
    .alert { display: flex; gap: 6px; align-items: flex-start; font-size: 13px; padding: 6px 8px; border-radius: 8px; background: var(--app-gold-soft); margin-bottom: 6px; }
    .alert mat-icon { font-size: 16px; width: 16px; height: 16px; color: #9b7a3f; }
    .load-title { margin-top: 14px; }
    .load { display: grid; grid-template-columns: 140px 1fr auto; gap: 8px; align-items: center; font-size: 13px; margin-bottom: 6px; }
    .load .bar { height: 6px; background: var(--app-border); border-radius: 4px; overflow: hidden; }
    .load .bar span { display: block; height: 100%; background: var(--c); }
    .late { color: var(--app-danger); }
    .list .clickable { cursor: pointer; }
    .archived { padding: 14px 16px; }
    .arch-row { display: flex; align-items: center; padding: 6px 0; border-bottom: 1px solid var(--app-border); }
  `,
})
export class BoardViewComponent {
  private readonly service = inject(BoardsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  readonly priorities = CARD_PRIORITY_LABELS;
  readonly priorityKeys = Object.keys(CARD_PRIORITY_LABELS) as CardPriority[];
  readonly staleDays = STALE_DAYS;
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });

  readonly view = signal<BoardView | null>(null);
  readonly insights = signal<BoardInsights | null>(null);
  readonly insightsOpen = signal(false);
  readonly archived = signal<BoardCard[] | null>(null);
  readonly mode = signal<'board' | 'list'>('board');
  readonly term = signal('');
  readonly assignee = signal('');
  readonly label = signal('');
  readonly priority = signal('');
  readonly onlyLate = signal(false);
  readonly onlyStale = signal(false);
  private boardId = '';

  readonly hasFilters = computed(() => !!(this.term() || this.assignee() || this.label() || this.priority() || this.onlyLate() || this.onlyStale()));
  readonly openCount = computed(() => (this.view()?.cards ?? []).filter((c) => !c.completed).length);

  readonly filtered = computed(() => {
    const v = this.view();
    if (!v) return [] as BoardCard[];
    const t = this.term().trim().toLowerCase();
    const me = this.auth.me()?.uid;
    const doneCols = new Set(v.board.columns.filter((c) => c.done).map((c) => c.id));
    return v.cards.filter((c) => {
      if (t && ![c.title, c.description, c.clientName, c.caseNumber].some((x) => (x ?? '').toLowerCase().includes(t))) return false;
      const a = this.assignee();
      if (a === '__me' && !c.assigneeUids.includes(me ?? '')) return false;
      if (a === '__none' && c.assigneeUids.length) return false;
      if (a && !a.startsWith('__') && !c.assigneeUids.includes(a)) return false;
      if (this.label() && !c.labelIds.includes(this.label())) return false;
      if (this.priority() && c.priority !== this.priority()) return false;
      if (this.onlyLate() && dueState(c.dueDate, c.completed) !== 'overdue') return false;
      if (this.onlyStale() && (doneCols.has(c.columnId) || daysSince(c.columnEnteredAt) < STALE_DAYS)) return false;
      return true;
    });
  });

  readonly byColumn = computed(() => {
    const map = new Map<string, BoardCard[]>();
    for (const c of this.filtered()) {
      map.set(c.columnId, [...(map.get(c.columnId) ?? []), c]);
    }
    map.forEach((list) => list.sort((a, b) => a.position - b.position));
    return map;
  });

  readonly listCards = computed(() => {
    const order = new Map((this.view()?.board.columns ?? []).map((c, i) => [c.id, i]));
    return [...this.filtered()].sort((a, b) => (order.get(a.columnId) ?? 0) - (order.get(b.columnId) ?? 0) || a.position - b.position);
  });

  readonly throughputChart = computed<ChartConfiguration>(() => {
    const weeks = this.insights()?.throughputWeeks ?? [];
    return {
      type: 'bar',
      data: {
        labels: weeks.map((_, i) => (i === weeks.length - 1 ? 'Esta semana' : `-${weeks.length - 1 - i} sem`)),
        datasets: [{ label: 'Concluídos', data: weeks, backgroundColor: this.view()?.board.color || '#12305a', borderRadius: 6 }],
      },
      options: { plugins: { legend: { display: false } }, scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } },
    };
  });

  constructor() {
    this.route.paramMap.subscribe((p) => {
      this.boardId = p.get('id') ?? '';
      this.load(true);
    });
  }

  load(openFromQuery = false): void {
    this.service.view(this.boardId).subscribe({
      next: (v) => {
        this.view.set(v);
        if (this.insightsOpen()) this.loadInsights();
        const cardId = this.route.snapshot.queryParamMap.get('card');
        if (openFromQuery && cardId) {
          const card = v.cards.find((c) => c.id === cardId);
          if (card) this.openCard(card);
        }
      },
      error: () => void this.router.navigate(['/quadros']),
    });
  }

  loadInsights(): void {
    this.service.insights(this.boardId).subscribe((i) => this.insights.set(i));
  }

  toggleInsights(): void {
    this.insightsOpen.set(!this.insightsOpen());
    if (this.insightsOpen()) this.loadInsights();
  }

  clearFilters(): void {
    this.term.set(''); this.assignee.set(''); this.label.set(''); this.priority.set(''); this.onlyLate.set(false); this.onlyStale.set(false);
  }

  cardsIn(columnId: string): BoardCard[] {
    return this.byColumn().get(columnId) ?? [];
  }

  countIn(columnId: string): number {
    return (this.view()?.cards ?? []).filter((c) => c.columnId === columnId).length;
  }

  atLimit(col: BoardColumn): boolean {
    return col.wipLimit > 0 && this.countIn(col.id) >= col.wipLimit;
  }

  overLimit(col: BoardColumn): boolean {
    return col.wipLimit > 0 && this.countIn(col.id) > col.wipLimit;
  }

  hasAutomation(col: BoardColumn): boolean {
    const a = col.automation;
    return col.done || !!(a && (a.notifyAssignees || a.addLabelId || a.assignUid || a.setDueInDays));
  }

  automationText(col: BoardColumn): string {
    const a = col.automation;
    const parts: string[] = [];
    if (col.done) parts.push('conclui o cartão');
    if (a?.notifyAssignees) parts.push('notifica responsáveis');
    if (a?.addLabelId) parts.push('adiciona etiqueta ' + (this.view()?.board.labels.find((l) => l.id === a.addLabelId)?.name ?? ''));
    if (a?.assignUid) parts.push('atribui a ' + this.nameOf(a.assignUid));
    if (a?.setDueInDays) parts.push(`vence em ${a.setDueInDays} dia(s)`);
    return 'Ao entrar: ' + parts.join(', ');
  }

  labelsOf(card: BoardCard) {
    const labels = this.view()?.board.labels ?? [];
    return card.labelIds.map((id) => labels.find((l) => l.id === id)).filter((l) => !!l);
  }

  checkDone(card: BoardCard): number {
    return card.checklist.filter((i) => i.done).length;
  }

  due(card: BoardCard): string {
    return dueState(card.dueDate, card.completed);
  }

  age(card: BoardCard): number {
    return daysSince(card.columnEnteredAt);
  }

  nameOf(uid: string): string {
    return this.staff().find((s) => s.uid === uid)?.displayName ?? 'Integrante';
  }

  initialsOf(uid: string): string {
    return this.nameOf(uid).split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  }

  assigneeNames(card: BoardCard): string {
    return card.assigneeUids.map((u) => this.nameOf(u)).join(', ');
  }

  columnName(id: string): string {
    return this.view()?.board.columns.find((c) => c.id === id)?.name ?? '';
  }

  loadPct(open: number, i: BoardInsights): number {
    const max = Math.max(1, ...i.members.map((m) => m.open));
    return (open / max) * 100;
  }

  drop(event: CdkDragDrop<string>): void {
    const v = this.view();
    if (!v) return;
    const card = event.item.data as BoardCard;
    const targetColumn = event.container.data;
    if (event.previousContainer === event.container && event.previousIndex === event.currentIndex) return;
    // Vizinhos na lista visível (filtrada) da coluna de destino, sem o próprio cartão.
    const siblings = this.cardsIn(targetColumn).filter((c) => c.id !== card.id);
    const before = siblings[event.currentIndex - 1]?.position ?? null;
    const after = siblings[event.currentIndex]?.position ?? null;
    const position = positionBetween(before, after);
    const previous = { columnId: card.columnId, position: card.position };
    // Atualização otimista; desfeita se o servidor recusar (ex.: limite WIP).
    this.patchCard({ ...card, columnId: targetColumn, position });
    this.service.move(card.id, targetColumn, position).subscribe({
      next: (saved) => {
        this.patchCard(saved);
        if (saved.completed && !card.completed) this.toast.success('Cartão concluído.');
        if (this.insightsOpen()) this.loadInsights();
      },
      error: (err) => {
        this.patchCard({ ...card, ...previous });
        this.toast.error(err?.error?.detail ?? err?.error?.message ?? 'Não foi possível mover o cartão.');
      },
    });
  }

  private patchCard(card: BoardCard): void {
    const v = this.view();
    if (!v) return;
    const exists = v.cards.some((c) => c.id === card.id);
    const cards = card.archived ? v.cards.filter((c) => c.id !== card.id)
      : exists ? v.cards.map((c) => (c.id === card.id ? card : c)) : [...v.cards, card];
    this.view.set({ ...v, cards });
  }

  newCard(columnId?: string): void {
    const v = this.view();
    if (!v) return;
    this.dialog.open(CardDialogComponent, { data: { board: v.board, columnId }, width: '760px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((r: CardDialogResult) => {
        if (r) {
          this.patchCard(r.card);
          this.toast.success('Cartão criado.');
        }
      });
  }

  openCard(card: BoardCard): void {
    const v = this.view();
    if (!v) return;
    void this.router.navigate([], { queryParams: { card: card.id }, replaceUrl: true });
    this.dialog.open(CardDialogComponent, { data: { board: v.board, card }, width: '1080px', maxWidth: '96vw' })
      .afterClosed().subscribe((r: CardDialogResult) => {
        void this.router.navigate([], { queryParams: {}, replaceUrl: true });
        if (r) this.patchCard(r.card);
        this.load();
      });
  }

  editBoard(board: Board): void {
    this.dialog.open(BoardFormDialogComponent, { data: board, width: '720px', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: BoardView) => { if (r) { this.view.set(r); this.toast.success('Quadro atualizado.'); } });
  }

  editColumns(board: Board): void {
    this.dialog.open(BoardColumnsDialogComponent, { data: board, width: '860px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((r?: BoardView) => { if (r) { this.view.set(r); this.toast.success('Colunas atualizadas.'); } });
  }

  editLabels(board: Board): void {
    this.dialog.open(BoardLabelsDialogComponent, { data: board, width: '640px', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: BoardView) => { if (r) this.view.set(r); });
  }

  showArchived(): void {
    this.service.archivedCards(this.boardId).subscribe((list) => this.archived.set(list));
  }

  restore(card: BoardCard): void {
    this.service.archiveCard(card.id, false).subscribe((saved) => {
      this.archived.set((this.archived() ?? []).filter((c) => c.id !== card.id));
      this.patchCard(saved);
      this.toast.success('Cartão restaurado.');
    });
  }
}
