import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { renderPdfFirstPage } from '../documents/file-viewer';
import { LegalEditorService, Letterhead, LetterheadMeta } from './legal-editor.service';

const MAX_BYTES = 10 * 1024 * 1024;

interface Draft extends LetterheadMeta {
  id: string | null;
}

/**
 * Ficheiro de papéis timbrados do escritório: adicionar (PNG, JPG ou PDF), ajustar a área de texto,
 * definir o padrão dos novos documentos e excluir.
 */
@Component({
  selector: 'app-letterhead-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressBarModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>description</mat-icon>Papel timbrado</h2>
    <mat-dialog-content>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="lh">
        <aside class="list">
          @for (l of items(); track l.id) {
            <button type="button" class="item" [class.on]="edit()?.id === l.id" (click)="select(l)">
              <div class="thumb">@if (thumbs()[l.id]; as url) { <img [src]="url" alt="" /> }</div>
              <div class="meta">
                <strong>{{ l.name }}</strong>
                @if (l.isDefault) { <span class="badge ok">padrão</span> }
                <span class="muted small">{{ l.hasPdf ? 'PDF' : 'Imagem' }}{{ l.createdByName ? ' · ' + l.createdByName : '' }}</span>
              </div>
            </button>
          } @empty {
            <p class="muted small pad">Nenhum papel timbrado. Adicione o do escritório para gerar os documentos já nele.</p>
          }
          @if (canWrite) {
            <button mat-stroked-button class="add" (click)="picker.click()"><mat-icon>add_photo_alternate</mat-icon>Adicionar papel timbrado</button>
            <input #picker type="file" accept=".png,.jpg,.jpeg,.pdf" hidden (change)="onPick($event)" />
          }
        </aside>

        <section class="detail">
          @if (edit(); as e) {
            <div class="preview">
              <div class="sheet">
                @if (previewUrl(); as url) { <img [src]="url" alt="Pré-visualização do papel timbrado" /> }
                <div class="area" [style.top.%]="e.marginTop / 2.97" [style.bottom.%]="e.marginBottom / 2.97"
                  [style.left.%]="e.marginLeft / 2.1" [style.right.%]="e.marginRight / 2.1"><span>Área do texto</span></div>
              </div>
            </div>
            <div class="form">
              <mat-form-field subscriptSizing="dynamic"><mat-label>Nome</mat-label>
                <input matInput [(ngModel)]="e.name" maxlength="80" [readonly]="!canWrite" placeholder="Ex.: Timbrado oficial 2026" /></mat-form-field>
              <p class="muted small">Margens (mm) — a área tracejada deve ficar na parte em branco do papel.</p>
              <div class="margins">
                <mat-form-field subscriptSizing="dynamic"><mat-label>Superior</mat-label><input matInput type="number" min="0" max="120" [(ngModel)]="e.marginTop" [readonly]="!canWrite" /></mat-form-field>
                <mat-form-field subscriptSizing="dynamic"><mat-label>Inferior</mat-label><input matInput type="number" min="0" max="120" [(ngModel)]="e.marginBottom" [readonly]="!canWrite" /></mat-form-field>
                <mat-form-field subscriptSizing="dynamic"><mat-label>Esquerda</mat-label><input matInput type="number" min="5" max="80" [(ngModel)]="e.marginLeft" [readonly]="!canWrite" /></mat-form-field>
                <mat-form-field subscriptSizing="dynamic"><mat-label>Direita</mat-label><input matInput type="number" min="5" max="80" [(ngModel)]="e.marginRight" [readonly]="!canWrite" /></mat-form-field>
              </div>
              <mat-checkbox [(ngModel)]="e.isDefault" [disabled]="!canWrite">Usar como padrão nos novos documentos</mat-checkbox>
              @if (pendingFile(); as f) { <p class="muted small">Arquivo: {{ f.name }}{{ f.type === 'application/pdf' ? ' (PDF — fundo vetorial na exportação em PDF)' : '' }}</p> }
              @if (canWrite) {
                <div class="actions">
                  @if (e.id) { <button mat-button class="danger" (click)="remove(e)"><mat-icon>delete</mat-icon>Excluir</button> }
                  <span class="spacer"></span>
                  @if (!e.id) { <button mat-button (click)="cancelNew()">Cancelar</button> }
                  <button mat-flat-button (click)="save(e)" [disabled]="busy() || !e.name.trim()"><mat-icon>save</mat-icon>{{ e.id ? 'Salvar' : 'Adicionar ao ficheiro' }}</button>
                </div>
              }
            </div>
          } @else {
            <div class="empty muted">
              <mat-icon>description</mat-icon>
              <p>Envie o papel timbrado do escritório (imagem A4 em PNG/JPG ou PDF de uma página).<br>
                Os documentos do Editor Jurídico são gerados sobre ele — em PDF, DOCX e na impressão.</p>
            </div>
          }
        </section>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button [mat-dialog-close]="changed()">Fechar</button></mat-dialog-actions>
  `,
  styles: `
    .lh { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 16px; min-height: 460px; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .item { display: flex; gap: 10px; align-items: center; text-align: left; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-surface);
      color: var(--app-text); padding: 6px; cursor: pointer; font: inherit; }
    .item.on { border-color: var(--app-gold); box-shadow: 0 0 0 2px var(--app-gold-soft); }
    .thumb { width: 46px; height: 65px; flex: none; background: #fff; border: 1px solid var(--app-border); overflow: hidden; }
    .thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .meta { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .meta strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .badge.ok { align-self: flex-start; background: var(--app-gold-soft); }
    .small { font-size: 12px; }
    .pad { padding: 4px; }
    .add { margin-top: 4px; }
    .detail { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: 16px; align-items: start; }
    .sheet { position: relative; width: 240px; aspect-ratio: 210 / 297; background: #fff; box-shadow: 0 2px 10px rgba(0,0,0,.2); overflow: hidden; }
    .sheet img { position: absolute; inset: 0; width: 100%; height: 100%; }
    .area { position: absolute; border: 1.5px dashed #c9a227; background: rgba(201, 162, 39, .08); display: grid; place-items: center; }
    .area span { font-size: 10px; color: #a88618; background: rgba(255,255,255,.8); padding: 0 4px; border-radius: 3px; }
    .form { display: flex; flex-direction: column; gap: 10px; }
    .margins { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
    .actions { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
    .danger { color: var(--app-danger); }
    .empty { grid-column: 1 / -1; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; gap: 8px; padding: 40px 16px; }
    .empty mat-icon { font-size: 48px; width: 48px; height: 48px; color: var(--app-gold); }
    @media (max-width: 860px) {
      .lh, .detail { grid-template-columns: minmax(0, 1fr); }
      .sheet { margin: 0 auto; }
    }
  `,
})
export class LetterheadDialogComponent implements OnDestroy {
  private readonly service = inject(LegalEditorService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  readonly canWrite = inject(AuthService).has('DOCUMENTS_WRITE');
  readonly items = signal<Letterhead[]>([]);
  readonly thumbs = signal<Record<string, string>>({});
  readonly edit = signal<Draft | null>(null);
  readonly pendingFile = signal<File | null>(null);
  private readonly pendingImage = signal<Blob | null>(null);
  private readonly pendingUrl = signal<string | null>(null);
  readonly busy = signal(false);
  readonly changed = signal(false);
  readonly previewUrl = computed(() => {
    const e = this.edit();
    if (!e) return null;
    return e.id ? this.thumbs()[e.id] ?? null : this.pendingUrl();
  });

  constructor() {
    this.load();
  }

  private load(selectId?: string): void {
    this.service.letterheads().subscribe((list) => {
      this.items.set(list);
      for (const l of list) {
        this.service.letterheadImage(l.id).subscribe((url) => this.thumbs.update((t) => ({ ...t, [l.id]: url })));
      }
      const target = list.find((l) => l.id === selectId) ?? (this.edit()?.id ? list.find((l) => l.id === this.edit()!.id) : null) ?? list[0];
      if (target && !(this.edit() && !this.edit()!.id)) this.select(target);
    });
  }

  select(l: Letterhead): void {
    this.clearPending();
    this.edit.set({ id: l.id, name: l.name, marginTop: l.marginTop, marginBottom: l.marginBottom, marginLeft: l.marginLeft, marginRight: l.marginRight, isDefault: l.isDefault });
  }

  async onPick(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > MAX_BYTES) {
      this.toast.error('Arquivo maior que 10 MB.');
      return;
    }
    const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    const isImage = /^image\/(png|jpeg)$/.test(file.type) || /\.(png|jpe?g)$/i.test(file.name);
    if (!isPdf && !isImage) {
      this.toast.error('Use uma imagem PNG/JPG ou um PDF.');
      return;
    }
    this.busy.set(true);
    try {
      const image = isPdf ? await renderPdfFirstPage(file) : file;
      this.clearPending();
      this.pendingFile.set(file);
      this.pendingImage.set(image);
      this.pendingUrl.set(URL.createObjectURL(image));
      this.edit.set({ id: null, name: file.name.replace(/\.[^.]+$/, '').slice(0, 80), marginTop: 40, marginBottom: 30, marginLeft: 30, marginRight: 20,
        isDefault: this.items().length === 0 });
    } catch {
      this.toast.error('Não foi possível ler o PDF (protegido por senha ou corrompido).');
    } finally {
      this.busy.set(false);
    }
  }

  cancelNew(): void {
    this.clearPending();
    this.edit.set(null);
    if (this.items().length) this.select(this.items()[0]);
  }

  async save(e: Draft): Promise<void> {
    const meta: LetterheadMeta = {
      name: e.name.trim(), marginTop: clamp(e.marginTop, 0, 120), marginBottom: clamp(e.marginBottom, 0, 120),
      marginLeft: clamp(e.marginLeft, 5, 80), marginRight: clamp(e.marginRight, 5, 80), isDefault: e.isDefault,
    };
    this.busy.set(true);
    try {
      let saved: Letterhead;
      if (e.id) {
        saved = await firstValueFrom(this.service.updateLetterhead(e.id, meta));
      } else {
        const file = this.pendingFile()!;
        const isPdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
        const image = this.pendingImage()!;
        saved = await firstValueFrom(this.service.createLetterhead(meta, image, isPdf ? 'papel-timbrado.png' : file.name, isPdf ? file : null));
        this.clearPending();
      }
      this.changed.set(true);
      this.toast.success(e.id ? 'Papel timbrado atualizado.' : 'Papel timbrado adicionado ao ficheiro.');
      this.edit.set(null);
      this.load(saved.id);
    } catch {
      // erro exibido pelo interceptor
    } finally {
      this.busy.set(false);
    }
  }

  remove(e: Draft): void {
    if (!e.id) return;
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir papel timbrado', message: `Excluir “${e.name}”? Os documentos que o usam passam a sair em folha branca (o texto não é alterado).`, confirmLabel: 'Excluir', danger: true },
      width: '440px',
    }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.service.deleteLetterhead(e.id!).subscribe(() => {
        this.changed.set(true);
        this.toast.success('Papel timbrado excluído.');
        this.edit.set(null);
        this.load();
      });
    });
  }

  private clearPending(): void {
    const url = this.pendingUrl();
    if (url) URL.revokeObjectURL(url);
    this.pendingUrl.set(null);
    this.pendingFile.set(null);
    this.pendingImage.set(null);
  }

  ngOnDestroy(): void {
    this.clearPending();
  }
}

function clamp(v: number, min: number, max: number): number {
  const n = Math.round(Number(v) || 0);
  return Math.min(max, Math.max(min, n));
}
