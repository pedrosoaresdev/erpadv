import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { Board, BoardView, BoardsService } from './boards.service';

interface ColumnDraft {
  id: string | null;
  name: string;
  color: string;
  wipLimit: number;
  done: boolean;
  notifyAssignees: boolean;
  addLabelId: string;
  assignUid: string;
  setDueInDays: number | null;
}

const COLUMN_COLORS = ['', '#2f5d9e', '#2c7a7b', '#2e7d32', '#b8860b', '#c05621', '#9b2c2c', '#6b46c1'];

// ======================================================================= colunas e automações
@Component({
  selector: 'app-board-columns-dialog',
  imports: [FormsModule, CdkDropList, CdkDrag, CdkDragHandle, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatButtonModule, MatIconModule, MatCheckboxModule, MatExpansionModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>view_column</mat-icon>Colunas e automações</h2>
    <mat-dialog-content>
      <p class="muted small">Arraste para reordenar. O limite WIP impede que a coluna receba mais cartões do que a equipe consegue tratar;
        automações rodam sempre que um cartão entra na coluna.</p>
      <div cdkDropList (cdkDropListDropped)="drop($event)" class="list">
        @for (c of columns(); track $index; let i = $index) {
          <mat-expansion-panel cdkDrag class="col">
            <mat-expansion-panel-header>
              <mat-panel-title>
                <mat-icon cdkDragHandle class="handle" (click)="$event.stopPropagation()">drag_indicator</mat-icon>
                <span class="swatch" [style.background]="c.color || 'var(--app-border)'"></span>
                {{ c.name || 'Nova coluna' }}
              </mat-panel-title>
              <mat-panel-description>
                @if (c.wipLimit) { <span class="badge">WIP {{ c.wipLimit }}</span> }
                @if (c.done) { <span class="badge success">conclusão</span> }
                @if (hasAutomation(c)) { <span class="badge gold">automação</span> }
              </mat-panel-description>
            </mat-expansion-panel-header>
            <div class="form-grid">
              <mat-form-field appearance="outline" class="c6">
                <mat-label>Nome</mat-label>
                <input matInput [(ngModel)]="c.name" maxlength="40" required />
              </mat-form-field>
              <mat-form-field appearance="outline" class="c3">
                <mat-label>Limite WIP</mat-label>
                <input matInput type="number" min="0" max="500" [(ngModel)]="c.wipLimit" />
                <mat-hint>0 = sem limite</mat-hint>
              </mat-form-field>
              <div class="c3 colors">
                @for (color of colors; track color) {
                  <button type="button" class="swatch pick" [style.background]="color || 'var(--app-surface-2)'" [class.sel]="c.color === color"
                    (click)="c.color = color" [attr.aria-label]="color ? 'Cor ' + color : 'Sem cor'"></button>
                }
              </div>
              <mat-checkbox class="c6" [(ngModel)]="c.done">Coluna de conclusão (marca o cartão como concluído)</mat-checkbox>
              <mat-checkbox class="c6" [(ngModel)]="c.notifyAssignees">Notificar responsáveis ao entrar</mat-checkbox>
              <mat-form-field appearance="outline" class="c4">
                <mat-label>Adicionar etiqueta</mat-label>
                <mat-select [(ngModel)]="c.addLabelId">
                  <mat-option value="">—</mat-option>
                  @for (l of data.labels; track l.id) { <mat-option [value]="l.id">{{ l.name }}</mat-option> }
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline" class="c4">
                <mat-label>Atribuir a</mat-label>
                <mat-select [(ngModel)]="c.assignUid">
                  <mat-option value="">—</mat-option>
                  @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline" class="c4">
                <mat-label>Vencimento em (dias)</mat-label>
                <input matInput type="number" min="0" max="365" [(ngModel)]="c.setDueInDays" />
                <mat-hint>Só se o cartão não tiver data</mat-hint>
              </mat-form-field>
            </div>
            <div class="row">
              <span class="spacer"></span>
              <button mat-button class="danger" type="button" (click)="remove(i)" [disabled]="columns().length <= 1"><mat-icon>delete</mat-icon>Remover coluna</button>
            </div>
          </mat-expansion-panel>
        }
      </div>
      <button mat-stroked-button type="button" (click)="add()" [disabled]="columns().length >= 20"><mat-icon>add</mat-icon>Adicionar coluna</button>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">Salvar colunas</button>
    </mat-dialog-actions>
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; }
    .small { font-size: 13px; }
    .list { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
    .handle { cursor: grab; color: var(--app-text-muted); margin-right: 6px; }
    .swatch { display: inline-block; width: 14px; height: 14px; border-radius: 50%; margin-right: 8px; border: 1px solid var(--app-border); }
    .colors { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; padding-bottom: 20px; }
    .pick { cursor: pointer; width: 20px; height: 20px; margin: 0; }
    .pick.sel { outline: 2px solid var(--app-text); }
    mat-panel-description { gap: 6px; justify-content: flex-end; }
    .danger { color: var(--app-danger); }
    .cdk-drag-preview { box-shadow: var(--app-shadow-lg); }
  `,
})
export class BoardColumnsDialogComponent {
  readonly data = inject<Board>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<BoardColumnsDialogComponent>);
  private readonly service = inject(BoardsService);
  readonly staff = toSignal(this.service.staff(), { initialValue: [] });
  readonly colors = COLUMN_COLORS;
  readonly saving = signal(false);
  readonly columns = signal<ColumnDraft[]>(this.data.columns.map((c) => ({
    id: c.id, name: c.name, color: c.color ?? '', wipLimit: c.wipLimit, done: c.done,
    notifyAssignees: !!c.automation?.notifyAssignees, addLabelId: c.automation?.addLabelId ?? '',
    assignUid: c.automation?.assignUid ?? '', setDueInDays: c.automation?.setDueInDays ?? null,
  })));

  hasAutomation(c: ColumnDraft): boolean {
    return c.notifyAssignees || !!c.addLabelId || !!c.assignUid || !!c.setDueInDays;
  }

  drop(event: CdkDragDrop<ColumnDraft[]>): void {
    const next = [...this.columns()];
    moveItemInArray(next, event.previousIndex, event.currentIndex);
    this.columns.set(next);
  }

  add(): void {
    this.columns.set([...this.columns(), { id: null, name: '', color: '', wipLimit: 0, done: false, notifyAssignees: false,
      addLabelId: '', assignUid: '', setDueInDays: null }]);
  }

  remove(i: number): void {
    this.columns.set(this.columns().filter((_, idx) => idx !== i));
  }

  save(): void {
    const columns = this.columns();
    if (columns.some((c) => !c.name.trim())) {
      return;
    }
    this.saving.set(true);
    this.service.columns(this.data.id, columns.map((c) => ({
      id: c.id, name: c.name.trim(), color: c.color || null, wipLimit: Number(c.wipLimit) || 0, done: c.done,
      notifyAssignees: c.notifyAssignees, addLabelId: c.addLabelId || null, assignUid: c.assignUid || null,
      setDueInDays: c.setDueInDays ? Number(c.setDueInDays) : null,
    }))).subscribe({ next: (v: BoardView) => this.ref.close(v), error: () => this.saving.set(false) });
  }
}

// ======================================================================= etiquetas
const LABEL_COLORS = ['#c62828', '#ef6c00', '#f9a825', '#2e7d32', '#00838f', '#1565c0', '#6a1b9a', '#4e342e', '#546e7a'];

@Component({
  selector: 'app-board-labels-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>sell</mat-icon>Etiquetas</h2>
    <mat-dialog-content>
      @for (l of labels(); track $index; let i = $index) {
        <div class="row lab">
          <span class="chip" [style.background]="l.color">{{ l.name || 'etiqueta' }}</span>
          <input class="name" [(ngModel)]="l.name" maxlength="30" aria-label="Nome da etiqueta" />
          <div class="colors">
            @for (c of colors; track c) {
              <button type="button" class="sw" [style.background]="c" [class.sel]="l.color === c" (click)="l.color = c" [attr.aria-label]="'Cor ' + c"></button>
            }
          </div>
          <button mat-icon-button type="button" (click)="remove(i)" aria-label="Remover etiqueta"><mat-icon>delete</mat-icon></button>
        </div>
      }
      <button mat-stroked-button type="button" (click)="add()" [disabled]="labels().length >= 30"><mat-icon>add</mat-icon>Nova etiqueta</button>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .lab { padding: 6px 0; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .chip { color: #fff; font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 999px; min-width: 80px; text-align: center; }
    .name { flex: 1; min-width: 140px; border: 1px solid var(--app-border); border-radius: 8px; padding: 6px 8px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .colors { display: flex; gap: 4px; }
    .sw { width: 18px; height: 18px; border-radius: 50%; border: 0; cursor: pointer; }
    .sw.sel { outline: 2px solid var(--app-text); }
    button[mat-stroked-button] { margin-top: 12px; }
  `,
})
export class BoardLabelsDialogComponent {
  readonly data = inject<Board>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<BoardLabelsDialogComponent>);
  private readonly service = inject(BoardsService);
  readonly colors = LABEL_COLORS;
  readonly saving = signal(false);
  readonly labels = signal(this.data.labels.map((l) => ({ ...l })) as { id: string | null; name: string; color: string }[]);

  add(): void {
    this.labels.set([...this.labels(), { id: null, name: '', color: LABEL_COLORS[this.labels().length % LABEL_COLORS.length] }]);
  }

  remove(i: number): void {
    this.labels.set(this.labels().filter((_, idx) => idx !== i));
  }

  save(): void {
    const labels = this.labels().filter((l) => l.name.trim()).map((l) => ({ ...l, name: l.name.trim() }));
    this.saving.set(true);
    this.service.labels(this.data.id, labels).subscribe({ next: (v) => this.ref.close(v), error: () => this.saving.set(false) });
  }
}
