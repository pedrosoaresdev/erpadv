import {
  AfterViewInit, Component, ElementRef, OnDestroy, ViewEncapsulation, computed, input, output, signal, viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { FieldInfo, escapeHtml, plainText, sanitizeEditorHtml } from './legal-editor.service';

/**
 * Editor de texto rico em formato de folha A4 (contenteditable): estilos de parágrafo, negrito/itálico/
 * sublinhado/tachado, alinhamentos, listas, recuo, citação, tabela, quebra de página e campos automáticos.
 * O HTML é saneado na colagem e novamente no servidor.
 */
@Component({
  selector: 'app-rich-text-editor',
  encapsulation: ViewEncapsulation.None,
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, MatDividerModule],
  template: `
    <div class="rte" [class.rte-readonly]="readonly()">
      @if (!readonly()) {
        <div class="rte-bar" role="toolbar" aria-label="Formatação">
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('undo')" matTooltip="Desfazer (Ctrl+Z)" aria-label="Desfazer"><mat-icon>undo</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('redo')" matTooltip="Refazer (Ctrl+Y)" aria-label="Refazer"><mat-icon>redo</mat-icon></button>
          <span class="rte-sep"></span>
          <button mat-button class="rte-style" [matMenuTriggerFor]="styles" (mousedown)="saveSelection()" aria-label="Estilo do parágrafo">{{ blockLabel() }}<mat-icon iconPositionEnd>arrow_drop_down</mat-icon></button>
          <mat-menu #styles="matMenu">
            <button mat-menu-item (click)="block('p')">Parágrafo</button>
            <button mat-menu-item (click)="block('h1')"><strong>Título</strong></button>
            <button mat-menu-item (click)="block('h2')"><strong>Subtítulo</strong></button>
            <button mat-menu-item (click)="block('h3')">Seção</button>
            <button mat-menu-item (click)="block('blockquote')"><em>Citação recuada</em></button>
          </mat-menu>
          <span class="rte-sep"></span>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('bold')" [class.rte-on]="state().bold" matTooltip="Negrito (Ctrl+B)" aria-label="Negrito"><mat-icon>format_bold</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('italic')" [class.rte-on]="state().italic" matTooltip="Itálico (Ctrl+I)" aria-label="Itálico"><mat-icon>format_italic</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('underline')" [class.rte-on]="state().underline" matTooltip="Sublinhado (Ctrl+U)" aria-label="Sublinhado"><mat-icon>format_underlined</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('strikeThrough')" [class.rte-on]="state().strike" matTooltip="Tachado" aria-label="Tachado"><mat-icon>strikethrough_s</mat-icon></button>
          <span class="rte-sep"></span>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('justifyLeft')" [class.rte-on]="state().align === 'left'" matTooltip="Alinhar à esquerda" aria-label="Alinhar à esquerda"><mat-icon>format_align_left</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('justifyCenter')" [class.rte-on]="state().align === 'center'" matTooltip="Centralizar" aria-label="Centralizar"><mat-icon>format_align_center</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('justifyRight')" [class.rte-on]="state().align === 'right'" matTooltip="Alinhar à direita" aria-label="Alinhar à direita"><mat-icon>format_align_right</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('justifyFull')" [class.rte-on]="state().align === 'justify'" matTooltip="Justificar" aria-label="Justificar"><mat-icon>format_align_justify</mat-icon></button>
          <span class="rte-sep"></span>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('insertUnorderedList')" matTooltip="Lista com marcadores" aria-label="Lista com marcadores"><mat-icon>format_list_bulleted</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('insertOrderedList')" matTooltip="Lista numerada" aria-label="Lista numerada"><mat-icon>format_list_numbered</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('outdent')" matTooltip="Diminuir recuo" aria-label="Diminuir recuo"><mat-icon>format_indent_decrease</mat-icon></button>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('indent')" matTooltip="Aumentar recuo" aria-label="Aumentar recuo"><mat-icon>format_indent_increase</mat-icon></button>
          <span class="rte-sep"></span>
          <button mat-icon-button [matMenuTriggerFor]="insert" (mousedown)="saveSelection()" matTooltip="Inserir" aria-label="Inserir elemento"><mat-icon>add_box</mat-icon></button>
          <mat-menu #insert="matMenu">
            <button mat-menu-item (click)="insertTable(2, 2)"><mat-icon>table</mat-icon>Tabela 2 × 2</button>
            <button mat-menu-item (click)="insertTable(4, 2)"><mat-icon>table_rows</mat-icon>Tabela 4 × 2 (dados)</button>
            <button mat-menu-item (click)="insertHtml('<hr class=&quot;page-break&quot;><p><br></p>')"><mat-icon>insert_page_break</mat-icon>Quebra de página</button>
            <button mat-menu-item (click)="insertHtml('<p style=&quot;text-align: center;&quot;>_______________________________________<br>Assinatura</p>')"><mat-icon>draw</mat-icon>Linha de assinatura</button>
          </mat-menu>
          <button mat-icon-button (mousedown)="$event.preventDefault()" (click)="exec('removeFormat')" matTooltip="Limpar formatação" aria-label="Limpar formatação"><mat-icon>format_clear</mat-icon></button>
          @if (fields().length) {
            <span class="rte-sep"></span>
            <button mat-stroked-button class="rte-fields" [matMenuTriggerFor]="fieldMenu" (mousedown)="saveSelection()"><mat-icon>data_object</mat-icon>Inserir campo</button>
            <mat-menu #fieldMenu="matMenu" class="rte-field-menu">
              @for (g of groups(); track g.name) {
                <button mat-menu-item [matMenuTriggerFor]="sub">{{ g.name }}</button>
                <mat-menu #sub="matMenu">
                  @for (f of g.items; track f.key) {
                    <button mat-menu-item (click)="insertField(f)">
                      <span>{{ f.label }}</span>
                      @if (placeholderMode()) { <span class="rte-hint">{{ '{{' + f.key + '}}' }}</span> }
                      @else if (fieldValues()[f.key]) { <span class="rte-hint">{{ fieldValues()[f.key] }}</span> }
                      @else { <span class="rte-hint warn">sem valor</span> }
                    </button>
                  }
                </mat-menu>
              }
            </mat-menu>
          }
        </div>
      }
      <div class="rte-scroll">
        <div class="rte-paper" [class.rte-compact]="compact()" [class.rte-lh]="!!background()"
          [style.background-image]="background() ? 'url(' + background() + ')' : null"
          [style.padding]="margins() ? margins()!.top + 'mm ' + margins()!.right + 'mm ' + margins()!.bottom + 'mm ' + margins()!.left + 'mm' : null">
          @if (header()) { <div class="rte-hf rte-head">{{ header() }}</div> }
          <div #area class="rte-area" [attr.contenteditable]="!readonly()" spellcheck="true" lang="pt-BR" role="textbox" aria-multiline="true"
            [attr.aria-label]="label()" (input)="onInput()" (paste)="onPaste($event)" (keyup)="refreshState()" (mouseup)="refreshState()"></div>
          @if (footer() || pageNumbers()) {
            <div class="rte-hf rte-foot"><span>{{ footer() }}</span>@if (pageNumbers()) { <span class="rte-pn">Página 1 de {{ pages() }}</span> }</div>
          }
        </div>
      </div>
      <div class="rte-status">{{ words() }} palavras · ~{{ pages() }} página(s)</div>
    </div>
  `,
  styles: `
    app-rich-text-editor { display: block; min-height: 0; }
    .rte { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 12px; overflow: hidden; }
    .rte-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 1px; padding: 4px 6px; background: var(--app-surface); border-bottom: 1px solid var(--app-border); position: sticky; top: 0; z-index: 2; }
    .rte-bar .rte-on { color: var(--app-gold); background: var(--app-gold-soft); }
    .rte-sep { width: 1px; height: 22px; background: var(--app-border); margin: 0 4px; }
    .rte-style { min-width: 112px; justify-content: space-between; }
    .rte-fields { margin-left: 2px; }
    .rte-hint { display: block; font-size: 11px; color: var(--app-text-muted); max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .rte-hint.warn { color: var(--app-warning); }
    .rte-scroll { flex: 1; overflow: auto; padding: 24px 12px; min-height: 0; }
    .rte-paper { width: 794px; max-width: 100%; margin: 0 auto; background: #fff; color: #111; box-shadow: 0 2px 12px rgba(0,0,0,.18);
      padding: 56px 76px 56px 113px; box-sizing: border-box; min-height: 1123px; display: flex; flex-direction: column; }
    .rte-paper.rte-compact { min-height: 520px; padding: 32px 40px; }
    .rte-paper.rte-lh { width: 210mm; max-width: none; min-height: 297mm; background-size: 210mm 297mm; background-repeat: repeat-y; background-position: top center; }
    .rte-paper.rte-lh .rte-head { border-bottom: 0; }
    .rte-hf { font: 9pt 'Times New Roman', Times, serif; color: #555; text-align: center; white-space: pre-line; }
    .rte-head { border-bottom: .5px solid #bbb; padding-bottom: 8px; margin-bottom: 20px; }
    .rte-foot { margin-top: auto; padding-top: 24px; display: flex; justify-content: center; gap: 16px; position: relative; }
    .rte-pn { position: absolute; right: 0; }
    .rte-area { flex: 1; outline: none; font: 12pt/1.5 'Times New Roman', Times, serif; color: #111; min-height: 400px; caret-color: #000; }
    .rte-area p { margin: 0 0 8pt; text-align: justify; }
    .rte-area h1 { font-size: 15pt; text-align: center; margin: 12pt 0 10pt; }
    .rte-area h2 { font-size: 13pt; margin: 12pt 0 6pt; }
    .rte-area h3 { font-size: 12pt; margin: 10pt 0 6pt; }
    .rte-area blockquote { margin: 0 18pt 8pt 36pt; }
    .rte-area ul, .rte-area ol { margin: 0 0 8pt; padding-left: 28px; }
    .rte-area table { width: 100%; border-collapse: collapse; margin: 4pt 0 10pt; }
    .rte-area td, .rte-area th { border: 1px solid #333; padding: 4pt 6pt; font-size: 11pt; text-align: left; vertical-align: top; min-width: 40px; }
    .rte-area hr { border: 0; border-top: 1px solid #999; }
    .rte-area hr.page-break { border-top: 2px dashed #c9a227; margin: 18px -40px; position: relative; overflow: visible; }
    .rte-area hr.page-break::after { content: 'quebra de página'; position: absolute; left: 50%; top: -9px; transform: translateX(-50%); background: #fff; color: #a88618; font: 11px sans-serif; padding: 0 8px; }
    .rte-area span.field { background: #fff3c4; color: #8a5a00; border-radius: 3px; padding: 0 2px; }
    .rte-readonly .rte-area { cursor: default; }
    .rte-status { font-size: 12px; color: var(--app-text-muted); padding: 4px 12px; border-top: 1px solid var(--app-border); background: var(--app-surface); }
    @media (max-width: 860px) {
      .rte-paper:not(.rte-lh) { padding: 24px 18px; min-height: 70vh; }
      .rte-scroll { padding: 8px 4px; }
      .rte-area hr.page-break { margin: 18px -8px; }
    }
  `,
})
export class RichTextEditorComponent implements AfterViewInit, OnDestroy {
  readonly readonly = input(false);
  readonly fields = input<FieldInfo[]>([]);
  readonly fieldValues = input<Record<string, string | null>>({});
  /** Em modelos, os campos entram como {{grupo.campo}}; em documentos, entram preenchidos. */
  readonly placeholderMode = input(false);
  readonly header = input<string | null>(null);
  readonly footer = input<string | null>(null);
  readonly pageNumbers = input(false);
  readonly compact = input(false);
  readonly label = input('Conteúdo do documento');
  /** Papel timbrado: URL local da imagem A4 e margens (mm) da área de texto. */
  readonly background = input<string | null>(null);
  readonly margins = input<{ top: number; bottom: number; left: number; right: number } | null>(null);
  readonly changed = output<string>();

  private readonly area = viewChild.required<ElementRef<HTMLDivElement>>('area');
  private savedRange: Range | null = null;
  private pendingHtml: string | null = null;
  readonly words = signal(0);
  readonly pages = computed(() => Math.max(1, Math.ceil(this.words() / 450)));
  readonly state = signal<{ bold: boolean; italic: boolean; underline: boolean; strike: boolean; align: string; block: string }>(
    { bold: false, italic: false, underline: false, strike: false, align: 'left', block: 'p' });
  readonly blockLabel = computed(() => ({ h1: 'Título', h2: 'Subtítulo', h3: 'Seção', blockquote: 'Citação' } as Record<string, string>)[this.state().block] ?? 'Parágrafo');
  readonly groups = computed(() => {
    const map = new Map<string, FieldInfo[]>();
    for (const f of this.fields()) map.set(f.group, [...(map.get(f.group) ?? []), f]);
    return [...map.entries()].map(([name, items]) => ({ name, items }));
  });

  private readonly onSelection = () => {
    const sel = document.getSelection();
    if (sel && sel.rangeCount && this.area().nativeElement.contains(sel.anchorNode)) {
      this.savedRange = sel.getRangeAt(0).cloneRange();
    }
  };

  ngAfterViewInit(): void {
    document.addEventListener('selectionchange', this.onSelection);
    try {
      document.execCommand('defaultParagraphSeparator', false, 'p');
      document.execCommand('styleWithCSS', false, 'false');
    } catch {
      // navegadores sem suporte mantêm o padrão
    }
    if (this.pendingHtml !== null) {
      this.setHtml(this.pendingHtml);
      this.pendingHtml = null;
    }
  }

  ngOnDestroy(): void {
    document.removeEventListener('selectionchange', this.onSelection);
  }

  /** Substitui o conteúdo (saneado). */
  setHtml(html: string): void {
    const el = this.area()?.nativeElement;
    if (!el) {
      this.pendingHtml = html;
      return;
    }
    el.innerHTML = sanitizeEditorHtml(html || '<p><br></p>');
    this.countWords();
  }

  getHtml(): string {
    return sanitizeEditorHtml(this.area().nativeElement.innerHTML);
  }

  /** Elemento editável (para operações da página, ex.: preencher campos pendentes). */
  element(): HTMLDivElement {
    return this.area().nativeElement;
  }

  focus(): void {
    this.area().nativeElement.focus();
  }

  onInput(): void {
    this.countWords();
    this.changed.emit(this.getHtml());
  }

  notifyChange(): void {
    this.onInput();
  }

  private countWords(): void {
    const text = plainText(this.area().nativeElement.innerHTML).trim();
    this.words.set(text ? text.split(/\s+/).length : 0);
  }

  saveSelection(): void {
    this.onSelection();
  }

  private restoreSelection(): void {
    const el = this.area().nativeElement;
    el.focus();
    if (this.savedRange) {
      const sel = document.getSelection();
      sel?.removeAllRanges();
      sel?.addRange(this.savedRange);
    }
  }

  exec(command: string): void {
    if (this.readonly()) return;
    this.area().nativeElement.focus();
    document.execCommand(command, false);
    this.refreshState();
    this.onInput();
  }

  block(tag: 'p' | 'h1' | 'h2' | 'h3' | 'blockquote'): void {
    if (this.readonly()) return;
    this.restoreSelection();
    document.execCommand('formatBlock', false, `<${tag}>`);
    this.refreshState();
    this.onInput();
  }

  insertHtml(html: string): void {
    if (this.readonly()) return;
    this.restoreSelection();
    document.execCommand('insertHTML', false, sanitizeEditorHtml(html));
    this.onInput();
  }

  insertTable(rows: number, cols: number): void {
    const cells = Array.from({ length: cols }, () => '<td><br></td>').join('');
    this.insertHtml(`<table><tbody>${Array.from({ length: rows }, () => `<tr>${cells}</tr>`).join('')}</tbody></table><p><br></p>`);
  }

  insertField(f: FieldInfo): void {
    if (this.placeholderMode()) {
      this.insertHtml(escapeHtml(`{{${f.key}}}`));
      return;
    }
    const value = this.fieldValues()[f.key];
    this.insertHtml(value ? escapeHtml(value) : `<span class="field">[${escapeHtml(f.key)}]</span>&nbsp;`);
  }

  onPaste(event: ClipboardEvent): void {
    if (this.readonly()) return;
    const data = event.clipboardData;
    if (!data) return;
    event.preventDefault();
    const html = data.getData('text/html');
    if (html) {
      document.execCommand('insertHTML', false, sanitizeEditorHtml(html));
    } else {
      document.execCommand('insertText', false, data.getData('text/plain'));
    }
    this.onInput();
  }

  refreshState(): void {
    const q = (c: string) => {
      try { return document.queryCommandState(c); } catch { return false; }
    };
    let block = 'p';
    let align = 'left';
    const sel = document.getSelection();
    let node: Node | null = sel?.anchorNode ?? null;
    const root = this.area().nativeElement;
    while (node && node !== root) {
      if (node instanceof HTMLElement) {
        const tag = node.tagName.toLowerCase();
        if (['h1', 'h2', 'h3', 'blockquote', 'p', 'li', 'div'].includes(tag)) {
          if (block === 'p' && tag !== 'li' && tag !== 'div') block = tag;
          const a = node.style.textAlign;
          if (a && align === 'left') align = a;
          if (tag !== 'li') break;
        }
      }
      node = node.parentNode;
    }
    if (q('justifyCenter')) align = 'center';
    else if (q('justifyRight')) align = 'right';
    else if (q('justifyFull')) align = 'justify';
    this.state.set({ bold: q('bold'), italic: q('italic'), underline: q('underline'), strike: q('strikeThrough'), align, block });
  }
}
