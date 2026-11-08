import {
  Component, ElementRef, NgZone, OnDestroy, ViewEncapsulation, computed, effect, inject, input, output, signal, untracked, viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { PDFDocumentLoadingTask, PDFDocumentProxy, PDFPageProxy } from 'pdfjs-dist';
import type { TextContent } from 'pdfjs-dist/types/src/display/api';

type PdfJs = typeof import('pdfjs-dist');
type Kind = 'pdf' | 'image' | 'text' | 'none';
type ZoomMode = 'width' | 'page' | number;

interface PageBox { n: number; w: number; h: number }

const ZOOMS = [0.5, 0.75, 1, 1.25, 1.5, 2, 3];
let pdfjsPromise: Promise<PdfJs> | null = null;

/** Carrega o pdf.js sob demanda (chunk separado) com o worker servido pela própria aplicação. */
function loadPdfJs(): Promise<PdfJs> {
  pdfjsPromise ??= import('pdfjs-dist').then((m) => {
    m.GlobalWorkerOptions.workerSrc = new URL('pdfjs/pdf.worker.min.mjs', document.baseURI).toString();
    return m;
  });
  return pdfjsPromise;
}

/** Renderiza a 1ª página de um PDF como PNG (ex.: papel timbrado), na largura indicada em pixels. */
export async function renderPdfFirstPage(blob: Blob, widthPx = 1240): Promise<Blob> {
  const pdfjs = await loadPdfJs();
  const pdf = await pdfjs.getDocument({ data: new Uint8Array(await blob.arrayBuffer()), isEvalSupported: false, enableXfa: false }).promise;
  try {
    const page = await pdf.getPage(1);
    const base = page.getViewport({ scale: 1 });
    const viewport = page.getViewport({ scale: widthPx / base.width });
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    const ctx = canvas.getContext('2d')!;
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    await page.render({ canvasContext: ctx, viewport }).promise;
    const png = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/png'));
    if (!png) throw new Error('Falha ao gerar a imagem');
    return png;
  } finally {
    void pdf.destroy();
  }
}

/** Imprime um PDF (renderizado em imagens, sem abrir o arquivo fora da aplicação). */
export async function printPdf(blob: Blob, title: string, onProgress?: (pct: number) => void): Promise<void> {
  const pdfjs = await loadPdfJs();
  const pdf = await pdfjs.getDocument({ data: new Uint8Array(await blob.arrayBuffer()), isEvalSupported: false, enableXfa: false }).promise;
  const urls: string[] = [];
  try {
    for (let n = 1; n <= pdf.numPages; n++) {
      const page = await pdf.getPage(n);
      const viewport = page.getViewport({ scale: 2 });
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width);
      canvas.height = Math.floor(viewport.height);
      await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;
      const img = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/jpeg', 0.92));
      if (img) urls.push(URL.createObjectURL(img));
      onProgress?.(Math.round((n / pdf.numPages) * 100));
    }
  } finally {
    void pdf.destroy();
  }
  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
  document.body.appendChild(frame);
  const doc = frame.contentDocument!;
  doc.open();
  doc.write('<!doctype html><html><head><meta charset="utf-8"><title></title></head><body></body></html>');
  doc.close();
  doc.title = title;
  const style = doc.createElement('style');
  style.textContent = '@page{size:A4;margin:0}body{margin:0}img{display:block;width:210mm;height:297mm;page-break-after:always}img:last-child{page-break-after:auto}';
  doc.head.appendChild(style);
  await Promise.all(urls.map((u) => new Promise<void>((resolve) => {
    const img = doc.createElement('img');
    img.onload = () => resolve();
    img.onerror = () => resolve();
    img.src = u;
    doc.body.appendChild(img);
  })));
  const win = frame.contentWindow!;
  const cleanup = () => setTimeout(() => { frame.remove(); urls.forEach((u) => URL.revokeObjectURL(u)); }, 1000);
  win.addEventListener('afterprint', cleanup, { once: true });
  win.focus();
  win.print();
  setTimeout(cleanup, 120_000);
}

/** Normaliza para busca: minúsculas, sem acentos e com espaços simples. */
export function normalizeForSearch(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\s+/g, ' ');
}

export function kindOf(contentType: string, fileName: string): Kind {
  const ct = (contentType || '').toLowerCase();
  if (ct === 'application/pdf' || fileName.toLowerCase().endsWith('.pdf')) return 'pdf';
  if (ct.startsWith('image/')) return 'image';
  if (ct.startsWith('text/plain')) return 'text';
  return 'none';
}

/**
 * Visualizador integrado: PDF (pdf.js) com páginas, miniaturas, zoom, rotação, busca no texto, tela cheia e
 * impressão; imagens com zoom/rotação; texto puro. O arquivo chega como Blob autenticado (sem URL pública).
 */
@Component({
  selector: 'app-file-viewer',
  encapsulation: ViewEncapsulation.None,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTooltipModule],
  template: `
    <div class="fv" #root [class.fv-full]="fullscreen()">
      @if (kind() !== 'none') {
        <div class="fv-bar" role="toolbar" aria-label="Ferramentas do visualizador">
          @if (kind() === 'pdf') {
            <button mat-icon-button (click)="showThumbs.set(!showThumbs())" [class.fv-on]="showThumbs()" matTooltip="Miniaturas" aria-label="Miniaturas"><mat-icon>view_sidebar</mat-icon></button>
            <span class="fv-sep"></span>
            <button mat-icon-button (click)="goTo(page() - 1)" [disabled]="page() <= 1" matTooltip="Página anterior" aria-label="Página anterior"><mat-icon>keyboard_arrow_up</mat-icon></button>
            <span class="fv-pageno">
              <input type="number" min="1" [max]="pages().length" [ngModel]="page()" (change)="goToInput($event)" aria-label="Página atual" />
              <span>/ {{ pages().length }}</span>
            </span>
            <button mat-icon-button (click)="goTo(page() + 1)" [disabled]="page() >= pages().length" matTooltip="Próxima página" aria-label="Próxima página"><mat-icon>keyboard_arrow_down</mat-icon></button>
            <span class="fv-sep"></span>
          }
          @if (kind() !== 'text') {
            <button mat-icon-button (click)="zoomStep(-1)" matTooltip="Diminuir zoom" aria-label="Diminuir zoom"><mat-icon>zoom_out</mat-icon></button>
            <button mat-button class="fv-zoom" [matMenuTriggerFor]="zoomMenu" aria-label="Nível de zoom">{{ zoomLabel() }}</button>
            <mat-menu #zoomMenu="matMenu">
              @if (kind() === 'pdf') {
                <button mat-menu-item (click)="setZoom('width')">Ajustar à largura</button>
                <button mat-menu-item (click)="setZoom('page')">Página inteira</button>
              } @else {
                <button mat-menu-item (click)="setZoom('page')">Ajustar à tela</button>
              }
              @for (z of zooms; track z) { <button mat-menu-item (click)="setZoom(z)">{{ z * 100 }}%</button> }
            </mat-menu>
            <button mat-icon-button (click)="zoomStep(1)" matTooltip="Aumentar zoom" aria-label="Aumentar zoom"><mat-icon>zoom_in</mat-icon></button>
            <button mat-icon-button (click)="rotate()" matTooltip="Girar 90°" aria-label="Girar 90 graus"><mat-icon>rotate_right</mat-icon></button>
          }
          @if (kind() === 'pdf') {
            <span class="fv-sep"></span>
            <div class="fv-search">
              <mat-icon>search</mat-icon>
              <input type="search" placeholder="Buscar no documento" [ngModel]="query()" (ngModelChange)="query.set($event)"
                (keydown.enter)="onSearchKey($event)" aria-label="Buscar texto no documento" />
              @if (searching()) { <span class="fv-count">…</span> }
              @else if (searched()) { <span class="fv-count">{{ hits().length ? (hitIndex() + 1) + '/' + hits().length : '0' }}</span> }
              <button mat-icon-button (click)="runSearch(-1)" [disabled]="!query().trim()" aria-label="Resultado anterior"><mat-icon>expand_less</mat-icon></button>
              <button mat-icon-button (click)="runSearch(1)" [disabled]="!query().trim()" aria-label="Próximo resultado"><mat-icon>expand_more</mat-icon></button>
            </div>
          }
          <span class="fv-grow"></span>
          <button mat-icon-button (click)="print()" [disabled]="printing() !== null" matTooltip="Imprimir" aria-label="Imprimir"><mat-icon>print</mat-icon></button>
          <button mat-icon-button (click)="download.emit()" matTooltip="Baixar" aria-label="Baixar"><mat-icon>download</mat-icon></button>
          <button mat-icon-button (click)="toggleFullscreen()" [matTooltip]="fullscreen() ? 'Sair da tela cheia' : 'Tela cheia'" aria-label="Tela cheia">
            <mat-icon>{{ fullscreen() ? 'fullscreen_exit' : 'fullscreen' }}</mat-icon></button>
        </div>
      }
      @if (loading() || printing() !== null) {
        <mat-progress-bar [mode]="printing() !== null ? 'determinate' : 'indeterminate'" [value]="printing() ?? 0" />
      }
      @if (error(); as e) {
        <div class="fv-empty"><mat-icon>error_outline</mat-icon><p>{{ e }}</p></div>
      }
      <div class="fv-body">
        @switch (kind()) {
          @case ('pdf') {
            @if (showThumbs()) {
              <div class="fv-thumbs" #thumbs>
                @for (p of pages(); track p.n) {
                  <button type="button" class="fv-thumb" [class.fv-on]="page() === p.n" (click)="goTo(p.n)" [attr.data-n]="p.n"
                    [attr.aria-label]="'Ir para a página ' + p.n">
                    <div class="fv-thumb-box" [style.aspect-ratio]="ratio(p)"></div>
                    <span>{{ p.n }}</span>
                  </button>
                }
              </div>
            }
            <div class="fv-pages" #scroller (scroll)="onScroll()">
              @for (p of pages(); track p.n) {
                <div class="fv-page" [attr.data-n]="p.n" [style.width.px]="boxW(p)" [style.height.px]="boxH(p)"></div>
              }
            </div>
          }
          @case ('image') {
            <div class="fv-pages fv-center" #scroller>
              @if (objectUrl(); as url) {
                <img class="fv-img" [src]="url" [alt]="fileName()" [style.transform]="'rotate(' + rotation() + 'deg)'"
                  [style.width]="imageWidth()" (load)="onImageLoad($event)" />
              }
            </div>
          }
          @case ('text') {
            <div class="fv-pages"><pre class="fv-text">{{ text() }}</pre></div>
          }
          @default {
            <div class="fv-empty">
              <mat-icon>visibility_off</mat-icon>
              <p>Pré-visualização indisponível para {{ fileName() }}.<br />Baixe o arquivo para abrir no aplicativo correspondente.</p>
              <button mat-flat-button (click)="download.emit()"><mat-icon>download</mat-icon>Baixar</button>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    app-file-viewer { display: block; height: 100%; }
    .fv { display: flex; flex-direction: column; height: 100%; min-height: 360px; background: var(--app-surface-2); border-radius: 10px; overflow: hidden; }
    .fv.fv-full { border-radius: 0; background: #2b2f36; }
    .fv-bar { display: flex; align-items: center; gap: 2px; flex-wrap: wrap; padding: 4px 8px; background: var(--app-surface); border-bottom: 1px solid var(--app-border); }
    .fv-bar .fv-on { color: var(--app-gold); }
    .fv-sep { width: 1px; height: 22px; background: var(--app-border); margin: 0 4px; }
    .fv-grow { flex: 1; }
    .fv-pageno { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; }
    .fv-pageno input { width: 52px; text-align: center; border: 1px solid var(--app-border); border-radius: 6px; padding: 4px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .fv-zoom { min-width: 64px; }
    .fv-search { display: inline-flex; align-items: center; gap: 2px; border: 1px solid var(--app-border); border-radius: 8px; padding-left: 8px; height: 36px; }
    .fv-search > mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--app-text-muted); }
    .fv-search input { border: 0; outline: 0; background: transparent; color: var(--app-text); font: inherit; width: 170px; }
    .fv-count { font-size: 12px; color: var(--app-text-muted); min-width: 34px; text-align: center; }
    .fv-body { flex: 1; display: flex; min-height: 0; }
    .fv-thumbs { width: 132px; flex: none; overflow-y: auto; padding: 10px; display: flex; flex-direction: column; gap: 10px; border-right: 1px solid var(--app-border); background: var(--app-surface); }
    .fv-thumb { border: 2px solid transparent; border-radius: 6px; background: none; padding: 2px; cursor: pointer; display: flex; flex-direction: column; align-items: center; gap: 2px; color: var(--app-text-muted); font-size: 11px; }
    .fv-thumb.fv-on { border-color: var(--app-gold); color: var(--app-text); }
    .fv-thumb-box { width: 100%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.2); display: flex; }
    .fv-thumb-box canvas { width: 100%; height: 100%; }
    .fv-pages { flex: 1; overflow: auto; padding: 16px; display: flex; flex-direction: column; align-items: center; gap: 14px; position: relative; }
    .fv-center { justify-content: center; }
    .fv-page { position: relative; background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.25); flex: none; }
    .fv-page canvas { display: block; }
    .fv-img { max-width: none; transition: transform .2s; background: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.25); }
    .fv-text { white-space: pre-wrap; word-break: break-word; background: var(--app-surface); color: var(--app-text); padding: 20px; border-radius: 8px; width: 100%; max-width: 900px; box-sizing: border-box; margin: 0; font-size: 13px; }
    .fv-empty { margin: auto; text-align: center; color: var(--app-text-muted); padding: 32px; display: flex; flex-direction: column; align-items: center; gap: 10px; }
    .fv-empty mat-icon { font-size: 40px; width: 40px; height: 40px; }
    .fv-page .textLayer { position: absolute; text-align: initial; inset: 0; overflow: clip; opacity: 1; line-height: 1; text-size-adjust: none; forced-color-adjust: none; transform-origin: 0 0; z-index: 0; }
    .fv-page .textLayer :is(span, br) { color: transparent; position: absolute; white-space: pre; cursor: text; transform-origin: 0% 0%; }
    .fv-page .textLayer span.markedContent { top: 0; height: 0; }
    .fv-page .textLayer ::selection { background: rgba(0, 0, 255, .25); }
    .fv-page .textLayer span.fv-hit { background: rgba(255, 196, 0, .45); border-radius: 2px; }
    .fv-page .textLayer span.fv-hit.fv-current { background: rgba(255, 120, 0, .6); }
    .fv-page [data-main-rotation="90"] { transform: rotate(90deg) translateY(-100%); }
    .fv-page [data-main-rotation="180"] { transform: rotate(180deg) translate(-100%, -100%); }
    .fv-page [data-main-rotation="270"] { transform: rotate(270deg) translateX(-100%); }
    @media (max-width: 720px) {
      .fv-search input { width: 110px; }
      .fv-thumbs { width: 92px; }
      .fv-pages { padding: 8px; }
    }
  `,
})
export class FileViewerComponent implements OnDestroy {
  readonly blob = input<Blob | null>(null);
  readonly fileName = input('');
  readonly contentType = input('');
  readonly download = output<void>();

  private readonly zone = inject(NgZone);
  private readonly root = viewChild<ElementRef<HTMLElement>>('root');
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly thumbsEl = viewChild<ElementRef<HTMLElement>>('thumbs');

  readonly zooms = ZOOMS;
  readonly kind = computed<Kind>(() => kindOf(this.contentType(), this.fileName()));
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly pages = signal<PageBox[]>([]);
  readonly page = signal(1);
  readonly zoom = signal<ZoomMode>('width');
  readonly rotation = signal(0);
  readonly showThumbs = signal(typeof window !== 'undefined' && window.innerWidth > 720);
  readonly fullscreen = signal(false);
  readonly printing = signal<number | null>(null);
  readonly query = signal('');
  readonly searching = signal(false);
  readonly searched = signal(false);
  readonly hits = signal<number[]>([]);
  readonly hitIndex = signal(0);
  readonly objectUrl = signal<string | null>(null);
  readonly text = signal('');
  private readonly naturalSize = signal<{ w: number; h: number } | null>(null);
  /** Largura útil da área de páginas (para "ajustar à largura"). */
  private readonly viewportWidth = signal(800);
  private readonly viewportHeight = signal(600);

  private pdfjs: PdfJs | null = null;
  private loadingTask: PDFDocumentLoadingTask | null = null;
  private pdf: PDFDocumentProxy | null = null;
  private readonly pageCache = new Map<number, Promise<PDFPageProxy>>();
  private readonly textCache = new Map<number, Promise<TextContent>>();
  private pageObserver: IntersectionObserver | null = null;
  private thumbObserver: IntersectionObserver | null = null;
  private resizeObserver: ResizeObserver | null = null;
  private lastQuery = '';
  private generation = 0;
  private readonly onFsChange = () => this.fullscreen.set(document.fullscreenElement === this.root()?.nativeElement);

  /** Escala efetiva aplicada ao PDF (1 = 72 dpi). */
  readonly scale = computed(() => {
    const z = this.zoom();
    if (typeof z === 'number') return z;
    const first = this.pages()[0];
    if (!first) return 1;
    const rotated = this.rotation() % 180 !== 0;
    const w = rotated ? first.h : first.w;
    const h = rotated ? first.w : first.h;
    const byWidth = Math.max(0.25, (this.viewportWidth() - 40) / w);
    return z === 'width' ? Math.min(byWidth, 4) : Math.max(0.25, Math.min(byWidth, (this.viewportHeight() - 40) / h));
  });

  readonly zoomLabel = computed(() => {
    if (this.kind() === 'image') {
      const z = this.zoom();
      return typeof z === 'number' ? `${Math.round(z * 100)}%` : 'Ajustar';
    }
    return `${Math.round(this.scale() * 100)}%`;
  });

  readonly imageWidth = computed(() => {
    const z = this.zoom();
    const n = this.naturalSize();
    if (typeof z !== 'number' || !n) return 'auto';
    return `${Math.round(n.w * z)}px`;
  });

  constructor() {
    effect(() => {
      const blob = this.blob();
      const kind = this.kind();
      untracked(() => this.open(blob, kind));
    });
    // Reaplica o layout das páginas quando a escala ou a rotação mudam.
    effect(() => {
      this.scale();
      this.rotation();
      untracked(() => this.relayout());
    });
    effect(() => {
      if (this.showThumbs() && this.pages().length) untracked(() => setTimeout(() => this.observeThumbs()));
    });
    document.addEventListener('fullscreenchange', this.onFsChange);
  }

  ratio(p: PageBox): string {
    return this.rotation() % 180 === 0 ? `${p.w} / ${p.h}` : `${p.h} / ${p.w}`;
  }

  boxW(p: PageBox): number {
    return Math.floor((this.rotation() % 180 === 0 ? p.w : p.h) * this.scale());
  }

  boxH(p: PageBox): number {
    return Math.floor((this.rotation() % 180 === 0 ? p.h : p.w) * this.scale());
  }

  // ------------------------------------------------------------------ abertura

  private async open(blob: Blob | null, kind: Kind): Promise<void> {
    const gen = ++this.generation;
    this.cleanup();
    this.error.set(null);
    this.pages.set([]);
    this.page.set(1);
    this.rotation.set(0);
    this.zoom.set(kind === 'image' ? 'page' : 'width');
    this.hits.set([]);
    this.searched.set(false);
    if (!blob) return;
    if (kind === 'image') {
      this.objectUrl.set(URL.createObjectURL(blob));
      return;
    }
    if (kind === 'text') {
      this.text.set(await blob.text());
      return;
    }
    if (kind !== 'pdf') return;
    this.loading.set(true);
    try {
      this.pdfjs = await loadPdfJs();
      const data = new Uint8Array(await blob.arrayBuffer());
      this.loadingTask = this.pdfjs.getDocument({ data, isEvalSupported: false, enableXfa: false });
      const pdf = await this.loadingTask.promise;
      if (gen !== this.generation) {
        void pdf.destroy();
        return;
      }
      this.pdf = pdf;
      const boxes: PageBox[] = [];
      for (let n = 1; n <= pdf.numPages; n++) {
        const page = await this.getPage(n);
        const vp = page.getViewport({ scale: 1 });
        boxes.push({ n, w: vp.width, h: vp.height });
      }
      if (gen !== this.generation) return;
      this.measure();
      this.pages.set(boxes);
      setTimeout(() => this.setupObservers());
    } catch {
      if (gen === this.generation) this.error.set('Não foi possível abrir o PDF. O arquivo pode estar corrompido ou protegido por senha — use “Baixar”.');
    } finally {
      if (gen === this.generation) this.loading.set(false);
    }
  }

  private getPage(n: number): Promise<PDFPageProxy> {
    let p = this.pageCache.get(n);
    if (!p) {
      p = this.pdf!.getPage(n);
      this.pageCache.set(n, p);
    }
    return p;
  }

  private getText(n: number): Promise<TextContent> {
    let t = this.textCache.get(n);
    if (!t) {
      t = this.getPage(n).then((p) => p.getTextContent());
      this.textCache.set(n, t);
    }
    return t;
  }

  private measure(): void {
    const el = this.scroller()?.nativeElement ?? this.root()?.nativeElement;
    if (el) {
      this.viewportWidth.set(el.clientWidth || 800);
      this.viewportHeight.set(el.clientHeight || 600);
    }
  }

  private setupObservers(): void {
    const scroller = this.scroller()?.nativeElement;
    if (!scroller) return;
    this.measure();
    this.resizeObserver?.disconnect();
    this.resizeObserver = new ResizeObserver(() => this.measure());
    this.resizeObserver.observe(scroller);
    this.pageObserver?.disconnect();
    this.zone.runOutsideAngular(() => {
      this.pageObserver = new IntersectionObserver((entries) => {
        for (const e of entries) {
          if (e.isIntersecting) void this.renderPage(e.target as HTMLElement);
        }
      }, { root: scroller, rootMargin: '150% 0px' });
    });
    scroller.querySelectorAll<HTMLElement>('.fv-page').forEach((el) => this.pageObserver!.observe(el));
  }

  private observeThumbs(): void {
    const box = this.thumbsEl()?.nativeElement;
    if (!box || !this.pdf) return;
    this.thumbObserver?.disconnect();
    this.zone.runOutsideAngular(() => {
      this.thumbObserver = new IntersectionObserver((entries) => {
        for (const e of entries) {
          if (e.isIntersecting) void this.renderThumb(e.target as HTMLElement);
        }
      }, { root: box, rootMargin: '200% 0px' });
    });
    box.querySelectorAll<HTMLElement>('.fv-thumb').forEach((el) => {
      delete el.dataset['key'];
      this.thumbObserver!.observe(el);
    });
  }

  private relayout(): void {
    if (!this.pdf) return;
    const current = this.page();
    setTimeout(() => {
      const scroller = this.scroller()?.nativeElement;
      if (!scroller || !this.pageObserver) return;
      scroller.querySelectorAll<HTMLElement>('.fv-page').forEach((el) => {
        this.pageObserver!.unobserve(el);
        this.pageObserver!.observe(el);
      });
      this.scrollToPage(current, 'auto');
      if (this.showThumbs()) this.observeThumbs();
    });
  }

  // ------------------------------------------------------------------ renderização

  private async renderPage(el: HTMLElement): Promise<void> {
    if (!this.pdf || !this.pdfjs) return;
    const n = Number(el.dataset['n']);
    const scale = this.scale();
    const rotation = this.rotation();
    const key = `${scale}|${rotation}`;
    if (el.dataset['key'] === key) return;
    el.dataset['key'] = key;
    const page = await this.getPage(n);
    const viewport = page.getViewport({ scale, rotation: (page.rotate + rotation) % 360 });
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(viewport.width * ratio);
    canvas.height = Math.floor(viewport.height * ratio);
    canvas.style.width = `${Math.floor(viewport.width)}px`;
    canvas.style.height = `${Math.floor(viewport.height)}px`;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    try {
      await page.render({ canvasContext: ctx, viewport, transform: ratio !== 1 ? [ratio, 0, 0, ratio, 0, 0] : undefined }).promise;
    } catch {
      return;
    }
    if (el.dataset['key'] !== key) return;
    const layer = document.createElement('div');
    layer.className = 'textLayer';
    el.style.setProperty('--scale-factor', String(scale));
    el.replaceChildren(canvas, layer);
    try {
      const textLayer = new this.pdfjs.TextLayer({ textContentSource: await this.getText(n), container: layer, viewport });
      await textLayer.render();
      this.highlight(el);
    } catch {
      // camada de texto é opcional (PDF digitalizado sem OCR)
    }
  }

  private async renderThumb(el: HTMLElement): Promise<void> {
    if (!this.pdf) return;
    const n = Number(el.dataset['n']);
    const rotation = this.rotation();
    const key = String(rotation);
    if (el.dataset['key'] === key) return;
    el.dataset['key'] = key;
    const page = await this.getPage(n);
    const base = page.getViewport({ scale: 1, rotation: (page.rotate + rotation) % 360 });
    const viewport = page.getViewport({ scale: 220 / base.width, rotation: (page.rotate + rotation) % 360 });
    const canvas = document.createElement('canvas');
    canvas.width = Math.floor(viewport.width);
    canvas.height = Math.floor(viewport.height);
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    try {
      await page.render({ canvasContext: ctx, viewport }).promise;
    } catch {
      return;
    }
    if (el.dataset['key'] === key) el.querySelector('.fv-thumb-box')?.replaceChildren(canvas);
  }

  // ------------------------------------------------------------------ navegação

  onScroll(): void {
    const scroller = this.scroller()?.nativeElement;
    if (!scroller) return;
    const top = scroller.scrollTop + scroller.clientHeight * 0.3;
    let current = 1;
    for (const el of Array.from(scroller.querySelectorAll<HTMLElement>('.fv-page'))) {
      if (el.offsetTop <= top) current = Number(el.dataset['n']);
      else break;
    }
    if (current !== this.page()) {
      this.page.set(current);
      this.thumbsEl()?.nativeElement.querySelector<HTMLElement>(`.fv-thumb[data-n="${current}"]`)?.scrollIntoView({ block: 'nearest' });
    }
  }

  goToInput(event: Event): void {
    this.goTo(Number((event.target as HTMLInputElement).value));
  }

  goTo(n: number): void {
    const total = this.pages().length;
    if (!total) return;
    const target = Math.min(Math.max(1, Math.round(n) || 1), total);
    this.page.set(target);
    this.scrollToPage(target, 'smooth');
  }

  private scrollToPage(n: number, behavior: ScrollBehavior): void {
    const scroller = this.scroller()?.nativeElement;
    const el = scroller?.querySelector<HTMLElement>(`.fv-page[data-n="${n}"]`);
    if (scroller && el) scroller.scrollTo({ top: el.offsetTop - 12, behavior });
  }

  setZoom(z: ZoomMode): void {
    this.zoom.set(z);
  }

  zoomStep(dir: 1 | -1): void {
    const current = this.kind() === 'image' ? (typeof this.zoom() === 'number' ? this.zoom() as number : 1) : this.scale();
    const next = dir > 0 ? ZOOMS.find((z) => z > current + 0.01) ?? ZOOMS[ZOOMS.length - 1]
      : [...ZOOMS].reverse().find((z) => z < current - 0.01) ?? ZOOMS[0];
    this.zoom.set(next);
  }

  rotate(): void {
    this.rotation.set((this.rotation() + 90) % 360);
  }

  onImageLoad(event: Event): void {
    const img = event.target as HTMLImageElement;
    this.naturalSize.set({ w: img.naturalWidth, h: img.naturalHeight });
    if (this.zoom() === 'page') {
      const box = this.scroller()?.nativeElement;
      const fit = box ? Math.min(1, (box.clientWidth - 32) / img.naturalWidth, (box.clientHeight - 32) / img.naturalHeight) : 1;
      this.zoom.set(Math.max(0.1, Math.round(fit * 100) / 100));
    }
  }

  toggleFullscreen(): void {
    const el = this.root()?.nativeElement;
    if (!el) return;
    if (document.fullscreenElement) void document.exitFullscreen();
    else void el.requestFullscreen?.().catch(() => undefined);
  }

  // ------------------------------------------------------------------ busca

  onSearchKey(event: Event): void {
    event.preventDefault();
    void this.runSearch((event as KeyboardEvent).shiftKey ? -1 : 1);
  }

  async runSearch(dir: 1 | -1): Promise<void> {
    const q = normalizeForSearch(this.query().trim());
    if (!q || !this.pdf) return;
    if (q !== this.lastQuery) {
      this.lastQuery = q;
      this.searching.set(true);
      const found: number[] = [];
      for (let n = 1; n <= this.pdf.numPages; n++) {
        const content = await this.getText(n);
        const text = normalizeForSearch(content.items.map((i) => ('str' in i ? i.str : '')).join(' '));
        let idx = text.indexOf(q);
        while (idx >= 0) {
          found.push(n);
          idx = text.indexOf(q, idx + q.length);
        }
      }
      if (q !== this.lastQuery) return;
      this.hits.set(found);
      this.hitIndex.set(0);
      this.searching.set(false);
      this.searched.set(true);
    } else if (this.hits().length) {
      const len = this.hits().length;
      this.hitIndex.set((this.hitIndex() + dir + len) % len);
    }
    this.scroller()?.nativeElement.querySelectorAll<HTMLElement>('.fv-page').forEach((el) => this.highlight(el));
    const target = this.hits()[this.hitIndex()];
    if (target) this.goTo(target);
  }

  /** Destaca os trechos da camada de texto que contêm o termo pesquisado. */
  private highlight(pageEl: HTMLElement): void {
    const q = this.lastQuery;
    const n = Number(pageEl.dataset['n']);
    const currentPage = this.hits()[this.hitIndex()];
    pageEl.querySelectorAll<HTMLElement>('.textLayer span').forEach((span) => {
      const hit = !!q && normalizeForSearch(span.textContent ?? '').includes(q);
      span.classList.toggle('fv-hit', hit);
      span.classList.toggle('fv-current', hit && n === currentPage);
    });
  }

  // ------------------------------------------------------------------ impressão

  /** Imprime a partir de imagens renderizadas (funciona mesmo com o PDF servido em sandbox). */
  async print(): Promise<void> {
    const urls: string[] = [];
    try {
      this.printing.set(0);
      if (this.kind() === 'pdf' && this.pdf) {
        const total = this.pdf.numPages;
        for (let n = 1; n <= total; n++) {
          const page = await this.getPage(n);
          const viewport = page.getViewport({ scale: 2, rotation: (page.rotate + this.rotation()) % 360 });
          const canvas = document.createElement('canvas');
          canvas.width = Math.floor(viewport.width);
          canvas.height = Math.floor(viewport.height);
          await page.render({ canvasContext: canvas.getContext('2d')!, viewport }).promise;
          const blob = await new Promise<Blob | null>((r) => canvas.toBlob(r, 'image/jpeg', 0.92));
          if (blob) urls.push(URL.createObjectURL(blob));
          this.printing.set(Math.round((n / total) * 100));
        }
      } else if (this.kind() === 'image' && this.objectUrl()) {
        urls.push(this.objectUrl()!);
      } else if (this.kind() === 'text') {
        urls.push('');
      }
      await this.printFrame(urls);
    } finally {
      this.printing.set(null);
      setTimeout(() => urls.filter((u) => u && u !== this.objectUrl()).forEach((u) => URL.revokeObjectURL(u)), 60_000);
    }
  }

  private printFrame(urls: string[]): Promise<void> {
    return new Promise((resolve) => {
      const frame = document.createElement('iframe');
      frame.setAttribute('aria-hidden', 'true');
      frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;';
      document.body.appendChild(frame);
      const doc = frame.contentDocument!;
      doc.open();
      doc.write('<!doctype html><html><head><meta charset="utf-8"><title></title></head><body></body></html>');
      doc.close();
      doc.title = this.fileName();
      const style = doc.createElement('style');
      style.textContent = '@page{margin:8mm}body{margin:0}img{display:block;max-width:100%;max-height:100vh;margin:0 auto;page-break-after:always}'
        + 'img:last-child{page-break-after:auto}pre{white-space:pre-wrap;font:12px/1.5 monospace}';
      doc.head.appendChild(style);
      const loads: Promise<void>[] = [];
      if (this.kind() === 'text') {
        const pre = doc.createElement('pre');
        pre.textContent = this.text();
        doc.body.appendChild(pre);
      } else {
        for (const url of urls) {
          const img = doc.createElement('img');
          loads.push(new Promise((r) => { img.onload = () => r(); img.onerror = () => r(); }));
          img.src = url;
          if (this.kind() === 'image' && this.rotation()) img.style.transform = `rotate(${this.rotation()}deg)`;
          doc.body.appendChild(img);
        }
      }
      void Promise.all(loads).then(() => {
        const win = frame.contentWindow!;
        const done = () => { setTimeout(() => frame.remove(), 1000); resolve(); };
        win.addEventListener('afterprint', done, { once: true });
        win.focus();
        win.print();
        setTimeout(done, 120_000);
      });
    });
  }

  // ------------------------------------------------------------------ limpeza

  private cleanup(): void {
    this.pageObserver?.disconnect();
    this.thumbObserver?.disconnect();
    this.resizeObserver?.disconnect();
    this.pageObserver = this.thumbObserver = this.resizeObserver = null;
    this.pageCache.clear();
    this.textCache.clear();
    this.lastQuery = '';
    const pdf = this.pdf;
    this.pdf = null;
    if (pdf) void pdf.destroy();
    else if (this.loadingTask) void this.loadingTask.destroy();
    this.loadingTask = null;
    const url = this.objectUrl();
    if (url) URL.revokeObjectURL(url);
    this.objectUrl.set(null);
    this.naturalSize.set(null);
    this.text.set('');
  }

  ngOnDestroy(): void {
    this.generation++;
    document.removeEventListener('fullscreenchange', this.onFsChange);
    this.cleanup();
  }
}
