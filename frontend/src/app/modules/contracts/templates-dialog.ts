import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { CONTRACT_TYPE_LABELS, ContractTemplate, ContractType, ContractsService } from './contracts.service';

/** Modelos de documentos com campos dinâmicos ({{cliente.nome}}…). Modelos do sistema podem ser duplicados. */
@Component({
  selector: 'app-templates-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>article</mat-icon>Modelos de contratos e documentos</h2>
    <mat-dialog-content>
      <div class="layout">
        <aside>
          <ul>
            @for (t of templates(); track t.id) {
              <li [class.sel]="selected()?.id === t.id" (click)="select(t)" tabindex="0" (keydown.enter)="select(t)">
                <strong>{{ t.name }}</strong>
                <span class="muted small">{{ t.system ? 'Modelo do sistema' : (t.active ? 'Do escritório' : 'Inativo') }}</span>
              </li>
            }
          </ul>
          @if (canWrite) { <button mat-stroked-button (click)="newTemplate()"><mat-icon>add</mat-icon>Novo modelo</button> }
        </aside>
        <section>
          @if (selected() || creating()) {
            <form [formGroup]="form" class="form-grid" (ngSubmit)="save()">
              <mat-form-field class="c8"><mat-label>Nome</mat-label><input matInput formControlName="name" /></mat-form-field>
              <mat-form-field class="c4">
                <mat-label>Tipo</mat-label>
                <mat-select formControlName="type">@for (t of types; track t) { <mat-option [value]="t">{{ typeLabels[t] }}</mat-option> }</mat-select>
              </mat-form-field>
              <mat-form-field><mat-label>Descrição</mat-label><input matInput formControlName="description" /></mat-form-field>
              <mat-form-field>
                <mat-label>Texto do modelo</mat-label>
                <textarea matInput rows="16" formControlName="body" class="mono"></textarea>
                <mat-hint># título · ## cláusula · &gt; linha centralizada · {{ '{' }}{{ '{' }}campo{{ '}' }}{{ '}' }} para dados dinâmicos</mat-hint>
              </mat-form-field>
              <div class="row-actions">
                @if (!isSystem()) { <mat-slide-toggle formControlName="active">Ativo</mat-slide-toggle> }
                <span class="spacer"></span>
                @if (isSystem() && canWrite) { <button mat-stroked-button type="button" (click)="duplicate()"><mat-icon>content_copy</mat-icon>Duplicar para editar</button> }
                @if (!isSystem() && selected() && canWrite) { <button mat-button type="button" class="danger" (click)="remove()"><mat-icon>delete</mat-icon>Excluir</button> }
                @if (!isSystem() && canWrite) { <button mat-flat-button type="submit"><mat-icon>save</mat-icon>Salvar modelo</button> }
              </div>
            </form>
            <div class="fields">
              <div class="card-title small-title">Campos disponíveis</div>
              @for (f of fields(); track f.key) { <code (click)="insert(f.key)" [title]="f.description">{{ '{' }}{{ '{' }}{{ f.key }}{{ '}' }}{{ '}' }}</code> }
            </div>
          } @else {
            <p class="muted">Selecione um modelo à esquerda.</p>
          }
        </section>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Fechar</button></mat-dialog-actions>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 280px 1fr; gap: 20px; }
    @media (max-width: 800px) { .layout { grid-template-columns: 1fr; } }
    ul { list-style: none; margin: 0 0 12px; padding: 0; }
    li { padding: 10px 12px; border-radius: 10px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    li:hover { background: var(--app-surface-2); }
    li.sel { background: var(--app-gold-soft); }
    .small { font-size: 12px; }
    textarea.mono { font-family: ui-monospace, Consolas, monospace; font-size: 13px; }
    .row-actions { grid-column: span 12; display: flex; gap: 8px; align-items: center; margin-top: 8px; }
    .fields { margin-top: 16px; display: flex; flex-wrap: wrap; gap: 6px; }
    .fields .card-title { width: 100%; }
    code { font-size: 12px; padding: 2px 6px; border-radius: 6px; background: var(--app-surface-2); border: 1px solid var(--app-border); cursor: pointer; }
    .danger { color: var(--app-danger); }
  `,
})
export class TemplatesDialogComponent {
  private readonly service = inject(ContractsService);
  private readonly toast = inject(ToastService);
  readonly canWrite = inject(AuthService).has('CONTRACTS_WRITE');
  readonly types = Object.keys(CONTRACT_TYPE_LABELS) as ContractType[];
  readonly typeLabels = CONTRACT_TYPE_LABELS;
  readonly templates = signal<ContractTemplate[]>([]);
  readonly fields = toSignal(this.service.fields(), { initialValue: [] });
  readonly selected = signal<ContractTemplate | null>(null);
  readonly creating = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', Validators.required],
    type: ['HONORARIOS' as ContractType],
    description: [''],
    body: ['', Validators.required],
    active: [true],
  });

  constructor() {
    this.reload();
  }

  isSystem(): boolean {
    return !!this.selected()?.system;
  }

  reload(selectId?: string): void {
    this.service.templates().subscribe((list) => {
      this.templates.set(list);
      const target = list.find((t) => t.id === selectId) ?? list[0];
      if (target) this.select(target);
    });
  }

  select(t: ContractTemplate): void {
    this.creating.set(false);
    this.selected.set(t);
    this.form.reset({ name: t.name, type: t.type, description: t.description ?? '', body: t.body, active: t.active });
    if (t.system || !this.canWrite) this.form.disable(); else this.form.enable();
  }

  newTemplate(): void {
    this.selected.set(null);
    this.creating.set(true);
    this.form.enable();
    this.form.reset({ name: '', type: 'HONORARIOS', description: '', body: '# TÍTULO\n\n## CLÁUSULA 1ª\n', active: true });
  }

  duplicate(): void {
    const t = this.selected();
    if (!t) return;
    this.selected.set(null);
    this.creating.set(true);
    this.form.enable();
    this.form.reset({ name: `${t.name} (escritório)`, type: t.type, description: t.description ?? '', body: t.body, active: true });
  }

  insert(key: string): void {
    if (this.form.disabled) return;
    this.form.controls.body.setValue(`${this.form.controls.body.value}{{${key}}}`);
  }

  save(): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const v = this.form.getRawValue();
    this.service.saveTemplate(this.creating() ? null : this.selected()!.id, { ...v, description: v.description || null }).subscribe((t) => {
      this.toast.success('Modelo salvo.');
      this.reload(t.id);
    });
  }

  remove(): void {
    const t = this.selected();
    if (!t) return;
    this.service.deleteTemplate(t.id).subscribe(() => { this.toast.success('Modelo excluído.'); this.reload(); });
  }
}
