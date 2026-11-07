import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe, RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { Board, BoardSummary, BoardTemplate, BoardsService, CARD_PRIORITY_LABELS, MyCard, dueState } from './boards.service';

export const BOARD_COLORS = ['#12305a', '#2f5d9e', '#2c7a7b', '#2f855a', '#b7791f', '#c05621', '#9b2c2c', '#6b46c1', '#4a5568'];

// ======================================================================= criar / editar quadro
@Component({
  selector: 'app-board-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatButtonToggleModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>view_kanban</mat-icon>{{ data ? 'Configurar quadro' : 'Novo quadro' }}</h2>
    <mat-dialog-content>
      @if (!data) {
        <div class="form-section-title">Modelo</div>
        <div class="templates">
          @for (t of templates(); track t.key) {
            <button type="button" class="tpl" [class.sel]="form.controls.templateKey.value === t.key" (click)="pick(t)">
              <strong>{{ t.name }}</strong>
              <span class="muted">{{ t.description }}</span>
              <span class="cols">@for (c of t.columns; track c) { <i>{{ c }}</i> }</span>
            </button>
          }
        </div>
      }
      <form [formGroup]="form" class="form-grid" id="boardForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Nome do quadro</mat-label>
          <input matInput formControlName="name" placeholder="Ex.: Peças da semana — Trabalhista" />
          @if (form.controls.name.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <div class="c4 colors">
          @for (c of colors; track c) {
            <button type="button" class="swatch" [style.background]="c" [class.sel]="form.controls.color.value === c"
              (click)="form.controls.color.setValue(c)" [attr.aria-label]="'Cor ' + c"></button>
          }
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Descrição</mat-label>
          <input matInput formControlName="description" />
        </mat-form-field>
        <mat-button-toggle-group formControlName="visibility" class="vis" aria-label="Visibilidade">
          <mat-button-toggle value="OFFICE"><mat-icon>apartment</mat-icon> Todo o escritório</mat-button-toggle>
          <mat-button-toggle value="MEMBERS"><mat-icon>lock</mat-icon> Somente membros</mat-button-toggle>
        </mat-button-toggle-group>
        <mat-form-field appearance="outline">
          <mat-label>Membros</mat-label>
          <mat-select formControlName="memberUids" multiple>
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
          <mat-hint>{{ form.controls.visibility.value === 'MEMBERS' ? 'Só membros, o dono e o sócio administrador veem o quadro.' : 'Membros aparecem como sugestão de responsáveis.' }}</mat-hint>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="boardForm" [disabled]="saving()">{{ data ? 'Salvar' : 'Criar quadro' }}</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; }
    .templates { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin-bottom: 16px; }
    .tpl { text-align: left; display: flex; flex-direction: column; gap: 4px; padding: 12px; border-radius: 12px; cursor: pointer; font: inherit;
      border: 1px solid var(--app-border); background: var(--app-surface-2); color: var(--app-text); }
    .tpl.sel { border-color: var(--app-gold); box-shadow: 0 0 0 2px var(--app-gold-soft); }
    .tpl .muted { font-size: 12px; }
    .cols { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
    .cols i { font-style: normal; font-size: 11px; padding: 2px 6px; border-radius: 6px; background: var(--app-surface); border: 1px solid var(--app-border); }
    .colors { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; padding-bottom: 20px; }
    .swatch { width: 24px; height: 24px; border-radius: 50%; border: 2px solid transparent; cursor: pointer; }
    .swatch.sel { border-color: var(--app-text); }
    .vis { margin-bottom: 16px; flex-wrap: wrap; }
  `,
})
export class BoardFormDialogComponent {
  readonly data = inject<Board | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<BoardFormDialogComponent>);
  private readonly service = inject(BoardsService);
  readonly saving = signal(false);
  readonly colors = BOARD_COLORS;
  readonly templates = toSignal(this.service.templates(), { initialValue: [] as BoardTemplate[] });
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.data?.name ?? '', [Validators.required, Validators.maxLength(80)]],
    description: [this.data?.description ?? ''],
    color: [this.data?.color ?? BOARD_COLORS[0]],
    visibility: [(this.data?.visibility ?? 'OFFICE') as 'OFFICE' | 'MEMBERS'],
    memberUids: [this.data?.memberUids ?? ([] as string[])],
    templateKey: ['contencioso'],
  });

  pick(t: BoardTemplate): void {
    this.form.controls.templateKey.setValue(t.key);
    if (!this.form.controls.name.value) this.form.controls.name.setValue(t.name);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = { name: v.name.trim(), description: v.description || null, color: v.color, visibility: v.visibility,
      memberUids: v.memberUids, templateKey: this.data ? null : v.templateKey };
    this.saving.set(true);
    const request$ = this.data ? this.service.update(this.data.id, body) : this.service.create(body);
    request$.subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= lista de quadros
@Component({
  selector: 'app-boards-list',
  imports: [MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, RouterLink,
    PageHeaderComponent, EmptyStateComponent, BrDatePipe, RelativeTimePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Escritório" title="Quadros" subtitle="Fluxos de trabalho com limites, automações e indicadores — do protocolo à entrega.">
        <mat-button-toggle-group [value]="archived()" (change)="setArchived($event.value)" aria-label="Quadros ativos ou arquivados">
          <mat-button-toggle [value]="false">Ativos</mat-button-toggle>
          <mat-button-toggle [value]="true">Arquivados</mat-button-toggle>
        </mat-button-toggle-group>
        <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon>Novo quadro</button>
      </app-page-header>

      <div class="layout">
        <section>
          @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
          <div class="boards">
            @for (b of boards(); track b.id) {
              <a class="card board" [routerLink]="['/quadros', b.id]" [style.--c]="b.color || '#12305a'">
                <div class="band"></div>
                <div class="body">
                  <div class="row">
                    <strong class="title">{{ b.name }}</strong>
                    <span class="spacer"></span>
                    @if (b.visibility === 'MEMBERS') { <mat-icon class="lock" title="Somente membros">lock</mat-icon> }
                    @if (b.canManage) {
                      <button mat-icon-button (click)="$event.preventDefault(); $event.stopPropagation()" [matMenuTriggerFor]="menu" aria-label="Ações do quadro"><mat-icon>more_vert</mat-icon></button>
                      <mat-menu #menu="matMenu" xPosition="before">
                        @if (b.archived) {
                          <button mat-menu-item (click)="toggleArchive(b, false)"><mat-icon>unarchive</mat-icon>Restaurar</button>
                          <button mat-menu-item class="danger" (click)="remove(b)"><mat-icon>delete_forever</mat-icon>Excluir definitivamente</button>
                        } @else {
                          <button mat-menu-item (click)="toggleArchive(b, true)"><mat-icon>archive</mat-icon>Arquivar</button>
                        }
                      </mat-menu>
                    }
                  </div>
                  @if (b.description) { <p class="muted desc">{{ b.description }}</p> }
                  <div class="meta">
                    <span><mat-icon>style</mat-icon>{{ b.openCards }} abertos</span>
                    <span><mat-icon>view_column</mat-icon>{{ b.columns }} colunas</span>
                    <span class="muted">{{ b.updatedAt ? (b.updatedAt | relative) : '' }}</span>
                  </div>
                </div>
              </a>
            }
          </div>
          @if (!loading() && boards().length === 0) {
            <div class="card"><app-empty-state icon="view_kanban" [title]="archived() ? 'Nenhum quadro arquivado' : 'Nenhum quadro ainda'"
              text="Comece por um modelo jurídico (contencioso, entrada de cliente, consultivo, audiências) e ajuste as colunas." /></div>
          }
        </section>

        <aside class="card mine">
          <div class="card-title"><mat-icon>assignment_ind</mat-icon>Meus cartões</div>
          @for (m of myCards(); track m.card.id) {
            <a class="mc" [routerLink]="['/quadros', m.card.boardId]" [queryParams]="{ card: m.card.id }">
              <span class="pill" [style.background]="m.boardColor || '#12305a'"></span>
              <div class="mc-body">
                <div class="mc-title">{{ m.card.title }}</div>
                <div class="muted small">{{ m.boardName }} · {{ m.columnName }}</div>
              </div>
              @if (m.card.dueDate) { <span class="due" [class]="due(m)">{{ m.card.dueDate | brDate }}</span> }
              @else { <span class="badge">{{ priorities[m.card.priority] }}</span> }
            </a>
          } @empty {
            <p class="muted small empty">Nenhum cartão aberto atribuído a você.</p>
          }
        </aside>
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr 340px; gap: 20px; align-items: start; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
    .boards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
    .board { display: block; text-decoration: none; color: inherit; overflow: hidden; transition: transform .15s, box-shadow .15s; }
    .board:hover { transform: translateY(-2px); box-shadow: var(--app-shadow-lg); }
    .band { height: 8px; background: linear-gradient(90deg, var(--c), color-mix(in srgb, var(--c) 55%, var(--app-gold))); }
    .body { padding: 14px 16px 16px; }
    .title { font-size: 16px; }
    .lock { font-size: 18px; width: 18px; height: 18px; color: var(--app-text-muted); }
    .desc { font-size: 13px; margin: 6px 0 0; }
    .meta { display: flex; gap: 14px; margin-top: 14px; font-size: 13px; align-items: center; flex-wrap: wrap; }
    .meta span { display: inline-flex; align-items: center; gap: 4px; }
    .meta mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--app-gold); }
    .mine { padding: 16px; position: sticky; top: 80px; }
    .mc { display: flex; gap: 10px; align-items: center; padding: 10px 6px; border-bottom: 1px solid var(--app-border); text-decoration: none; color: inherit; border-radius: 8px; }
    .mc:hover { background: var(--app-surface-2); }
    .pill { width: 4px; align-self: stretch; border-radius: 4px; }
    .mc-body { flex: 1; min-width: 0; }
    .mc-title { font-weight: 600; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .small { font-size: 12px; }
    .empty { padding: 8px 4px; }
    .due { font-size: 12px; padding: 2px 8px; border-radius: 6px; background: var(--app-surface-2); white-space: nowrap; }
    .due.overdue { background: var(--app-danger-bg); color: var(--app-danger); }
    .due.today, .due.soon { background: var(--app-warning-bg); color: var(--app-warning); }
    .danger { color: var(--app-danger); }
  `,
})
export class BoardsListComponent {
  private readonly service = inject(BoardsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly priorities = CARD_PRIORITY_LABELS;
  readonly boards = signal<BoardSummary[]>([]);
  readonly myCards = signal<MyCard[]>([]);
  readonly archived = signal(false);
  readonly loading = signal(false);
  readonly count = computed(() => this.boards().length);

  constructor() {
    this.load();
    this.service.myCards().subscribe((m) => this.myCards.set(m));
  }

  load(): void {
    this.loading.set(true);
    this.service.list(this.archived()).subscribe({
      next: (b) => { this.boards.set(b); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  setArchived(value: boolean): void {
    this.archived.set(value);
    this.load();
  }

  due(m: MyCard): string {
    return dueState(m.card.dueDate, m.card.completed);
  }

  create(): void {
    this.dialog.open(BoardFormDialogComponent, { data: null, width: '820px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((r?: { board: Board }) => {
        if (r) void this.router.navigate(['/quadros', r.board.id]);
      });
  }

  toggleArchive(b: BoardSummary, archived: boolean): void {
    this.service.archive(b.id, archived).subscribe(() => {
      this.toast.success(archived ? 'Quadro arquivado.' : 'Quadro restaurado.');
      this.load();
    });
  }

  remove(b: BoardSummary): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir quadro', message: `O quadro ${b.name}, seus cartões e comentários serão excluídos definitivamente.`, confirmLabel: 'Excluir', danger: true },
      width: '460px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.delete(b.id).subscribe(() => { this.toast.success('Quadro excluído.'); this.load(); });
    });
  }
}
