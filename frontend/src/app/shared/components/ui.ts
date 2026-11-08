import { Component, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

/** Cabeçalho padrão de página: título, subtítulo e ações (projeção de conteúdo). */
@Component({
  selector: 'app-page-header',
  imports: [MatIconModule],
  template: `
    <header class="ph">
      <div class="ph-text">
        @if (eyebrow()) { <span class="eyebrow">{{ eyebrow() }}</span> }
        <h1>{{ title() }}</h1>
        @if (subtitle()) { <p class="muted">{{ subtitle() }}</p> }
      </div>
      <div class="ph-actions"><ng-content /></div>
    </header>
  `,
  styles: `
    .ph { display: flex; align-items: flex-end; gap: 16px; flex-wrap: wrap; margin-bottom: 24px; }
    .ph-text { flex: 1 1 320px; min-width: 0; }
    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 1.2px; text-transform: uppercase; color: var(--app-gold); }
    h1 { font-size: 26px; font-weight: 700; letter-spacing: -.3px; margin-top: 2px; }
    p { margin: 6px 0 0; }
    .ph-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  `,
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly subtitle = input<string>();
  readonly eyebrow = input<string>();
}

/** Card de indicador (KPI). */
@Component({
  selector: 'app-stat-card',
  imports: [MatIconModule],
  template: `
    <div class="stat card" [class.accent]="accent()">
      <div class="top">
        <span class="label">{{ label() }}</span>
        <span class="icon"><mat-icon>{{ icon() }}</mat-icon></span>
      </div>
      <div class="value mono">{{ value() }}</div>
      @if (hint()) { <div class="hint" [class]="tone()">{{ hint() }}</div> }
    </div>
  `,
  styles: `
    .stat { padding: 18px 20px; position: relative; overflow: hidden; transition: transform .15s, box-shadow .15s; }
    .stat:hover { transform: translateY(-2px); box-shadow: var(--app-shadow-lg); }
    .stat.accent { background: linear-gradient(135deg, var(--app-navy), var(--app-navy-2)); border-color: transparent; color: #fff; }
    .stat.accent .label, .stat.accent .hint { color: rgb(255 255 255 / 75%); }
    .stat.accent .icon { background: rgb(197 164 109 / 18%); color: var(--app-gold); }
    .top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .label { font-size: 13px; font-weight: 500; color: var(--app-text-muted); }
    .icon { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; background: var(--app-gold-soft); color: #9b7a3f; }
    .icon mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .value { font-size: 28px; font-weight: 700; margin-top: 10px; letter-spacing: -.5px; }
    .hint { font-size: 12px; margin-top: 4px; color: var(--app-text-muted); }
    .hint.success { color: var(--app-success); }
    .hint.danger { color: var(--app-danger); }
    .hint.warning { color: var(--app-warning); }
    :host { display: block; height: 100%; }
    .stat { height: 100%; box-sizing: border-box; }
    @media (max-width: 760px) {
      .stat { padding: 12px 14px; }
      .label { font-size: 12px; line-height: 1.25; }
      .icon { width: 28px; height: 28px; border-radius: 8px; flex: none; }
      .icon mat-icon { font-size: 16px; width: 16px; height: 16px; }
      .value { font-size: 21px; margin-top: 6px; overflow-wrap: anywhere; }
      .hint { font-size: 11px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    }
  `,
})
export class StatCardComponent {
  readonly label = input.required<string>();
  readonly value = input.required<string | number>();
  readonly icon = input('insights');
  readonly hint = input<string>();
  readonly tone = input<'success' | 'danger' | 'warning' | ''>('');
  readonly accent = input(false);
}

/** Estado vazio com ícone, título e texto opcional. */
@Component({
  selector: 'app-empty-state',
  imports: [MatIconModule],
  template: `
    <div class="empty">
      <div class="ic"><mat-icon>{{ icon() }}</mat-icon></div>
      <h3>{{ title() }}</h3>
      @if (text()) { <p class="muted">{{ text() }}</p> }
      <ng-content />
    </div>
  `,
  styles: `
    .empty { text-align: center; padding: 48px 24px; display: flex; flex-direction: column; align-items: center; gap: 8px; }
    .ic { width: 56px; height: 56px; border-radius: 16px; display: grid; place-items: center; background: var(--app-surface-2); border: 1px solid var(--app-border); color: var(--app-text-muted); margin-bottom: 4px; }
    h3 { font-size: 16px; font-weight: 600; }
    p { margin: 0; max-width: 420px; }
  `,
})
export class EmptyStateComponent {
  readonly icon = input('inbox');
  readonly title = input.required<string>();
  readonly text = input<string>();
}

/** Indicador de carregamento centralizado. */
@Component({
  selector: 'app-loading',
  imports: [MatProgressSpinnerModule],
  template: `<div class="loading"><mat-spinner diameter="36" /></div>`,
  styles: `.loading { display: grid; place-items: center; padding: 48px; }`,
})
export class LoadingComponent {}
