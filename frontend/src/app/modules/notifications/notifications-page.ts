import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { AppNotification } from '../../core/models/models';
import { NotificationService } from '../../core/services/notification.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { RelativeTimePipe } from '../../shared/pipes/format.pipes';

export type NotificationPriority = 'ALTA' | 'MEDIA' | 'BAIXA';

interface Kind { label: string; icon: string; priority: NotificationPriority }

/** Categoria, ícone e prioridade por tipo de notificação emitido pelo backend. */
export const NOTIFICATION_KINDS: Record<string, Kind> = {
  DEADLINE_ALERT: { label: 'Prazos', icon: 'alarm', priority: 'ALTA' },
  DEADLINE_ASSIGNED: { label: 'Prazos', icon: 'alarm_add', priority: 'MEDIA' },
  APPOINTMENT_REMINDER: { label: 'Agenda', icon: 'event', priority: 'ALTA' },
  APPOINTMENT: { label: 'Agenda', icon: 'event', priority: 'MEDIA' },
  CASE_ASSIGNED: { label: 'Processos', icon: 'gavel', priority: 'MEDIA' },
  CASE_MOVEMENT: { label: 'Processos', icon: 'star', priority: 'ALTA' },
  CONTRACT_EXPIRY: { label: 'Contratos', icon: 'handshake', priority: 'MEDIA' },
  BOARD_MENTION: { label: 'Tarefas', icon: 'alternate_email', priority: 'MEDIA' },
  BOARD_CARD: { label: 'Tarefas', icon: 'view_kanban', priority: 'MEDIA' },
  BOARD_COMMENT: { label: 'Tarefas', icon: 'chat_bubble', priority: 'BAIXA' },
  CRM_LEAD: { label: 'CRM', icon: 'filter_alt', priority: 'MEDIA' },
};
const OTHER: Kind = { label: 'Outros', icon: 'notifications', priority: 'BAIXA' };

export function notificationKind(type: string): Kind {
  return NOTIFICATION_KINDS[type] ?? OTHER;
}

const PRIORITY_ORDER: Record<NotificationPriority, number> = { ALTA: 0, MEDIA: 1, BAIXA: 2 };

/** Central de notificações: prioridade, filtros, pesquisa, marcar como lida e acesso ao registro. */
@Component({
  selector: 'app-notifications-page',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatSelectModule, PageHeaderComponent, EmptyStateComponent, RelativeTimePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Comunicação" title="Notificações" subtitle="Alertas de prazos, agenda, processos, contratos, tarefas e CRM, organizados por prioridade.">
        <button mat-stroked-button (click)="service.markAllRead()" [disabled]="!service.unread()"><mat-icon>done_all</mat-icon>Marcar todas como lidas</button>
      </app-page-header>

      <div class="filters">
        <div class="search">
          <mat-icon>search</mat-icon>
          <input type="search" placeholder="Pesquisar notificações" [ngModel]="term()" (ngModelChange)="term.set($event)" aria-label="Pesquisar notificações" />
        </div>
        <mat-button-toggle-group [value]="status()" (change)="status.set($event.value)" aria-label="Situação">
          <mat-button-toggle value="">Todas</mat-button-toggle>
          <mat-button-toggle value="unread">Não lidas ({{ service.unread() }})</mat-button-toggle>
        </mat-button-toggle-group>
        <mat-select class="fsel" [ngModel]="category()" (ngModelChange)="category.set($event)" aria-label="Categoria" placeholder="Categoria">
          <mat-option value="">Todas as categorias</mat-option>
          @for (c of categories(); track c) { <mat-option [value]="c">{{ c }}</mat-option> }
        </mat-select>
        <mat-select class="fsel" [ngModel]="priority()" (ngModelChange)="priority.set($event)" aria-label="Prioridade" placeholder="Prioridade">
          <mat-option value="">Todas as prioridades</mat-option>
          <mat-option value="ALTA">Alta</mat-option>
          <mat-option value="MEDIA">Média</mat-option>
          <mat-option value="BAIXA">Baixa</mat-option>
        </mat-select>
      </div>

      <div class="card list">
        @for (n of filtered(); track n.id) {
          <button type="button" class="item" [class.unread]="!n.read" (click)="open(n)">
            <span class="ic" [class]="kind(n).priority"><mat-icon>{{ kind(n).icon }}</mat-icon></span>
            <span class="body">
              <span class="row1"><strong>{{ n.title }}</strong><span class="spacer"></span>
                <span class="prio" [class]="kind(n).priority">{{ priorityLabel[kind(n).priority] }}</span></span>
              <span class="text">{{ n.body }}</span>
              <span class="meta muted">{{ kind(n).label }} · {{ n.createdAt | relative }}@if (n.link) { · abrir registro }</span>
            </span>
            @if (!n.read) {
              <span class="mark" role="button" tabindex="0" (click)="$event.stopPropagation(); service.markRead(n)"
                (keydown.enter)="$event.stopPropagation(); service.markRead(n)" aria-label="Marcar como lida"><mat-icon>check</mat-icon></span>
            }
          </button>
        } @empty {
          <app-empty-state icon="notifications_none" [title]="service.error() ? 'Não foi possível carregar as notificações' : 'Nenhuma notificação'"
            text="As últimas 100 notificações aparecem aqui assim que forem geradas." />
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 14px; }
    .search { flex: 1 1 260px; display: flex; align-items: center; gap: 8px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 0 12px; height: 40px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .fsel { width: 200px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 10px; padding: 9px 12px; box-sizing: border-box; }
    .list { overflow: hidden; }
    .item { display: flex; gap: 14px; width: 100%; text-align: left; padding: 14px 18px; border: 0; border-bottom: 1px solid var(--app-border);
      background: transparent; color: inherit; font: inherit; cursor: pointer; align-items: flex-start; }
    .item:hover, .item:focus-visible { background: var(--app-surface-2); outline: none; }
    .item.unread { background: color-mix(in srgb, var(--app-gold-soft) 70%, transparent); }
    .ic { width: 38px; height: 38px; flex: none; border-radius: 10px; display: grid; place-items: center; background: var(--app-surface-2); }
    .ic.ALTA { background: var(--app-danger-bg); color: var(--app-danger); }
    .ic.MEDIA { background: var(--app-warning-bg); color: var(--app-warning); }
    .body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .row1 { display: flex; gap: 8px; align-items: center; }
    .text { font-size: 14px; color: var(--app-text-muted); }
    .meta { font-size: 12px; }
    .prio { font-size: 11px; font-weight: 700; padding: 1px 8px; border-radius: 999px; background: var(--app-surface-2); }
    .prio.ALTA { background: var(--app-danger-bg); color: var(--app-danger); }
    .prio.MEDIA { background: var(--app-warning-bg); color: var(--app-warning); }
    .mark { width: 34px; height: 34px; border-radius: 50%; display: grid; place-items: center; color: var(--app-success); }
    .mark:hover, .mark:focus-visible { background: var(--app-success-bg); outline: none; }
  `,
})
export class NotificationsPageComponent {
  readonly service = inject(NotificationService);
  private readonly router = inject(Router);
  readonly priorityLabel: Record<NotificationPriority, string> = { ALTA: 'Alta', MEDIA: 'Média', BAIXA: 'Baixa' };
  readonly term = signal('');
  readonly status = signal('');
  readonly category = signal('');
  readonly priority = signal('');

  readonly categories = computed(() => [...new Set(this.service.items().map((n) => notificationKind(n.type).label))].sort());

  readonly filtered = computed(() => {
    const t = this.term().trim().toLowerCase();
    return this.service.items()
      .filter((n) => !this.status() || !n.read)
      .filter((n) => !this.category() || notificationKind(n.type).label === this.category())
      .filter((n) => !this.priority() || notificationKind(n.type).priority === this.priority())
      .filter((n) => !t || `${n.title} ${n.body}`.toLowerCase().includes(t))
      .sort((a, b) => Number(a.read) - Number(b.read)
        || PRIORITY_ORDER[notificationKind(a.type).priority] - PRIORITY_ORDER[notificationKind(b.type).priority]
        || b.createdAt - a.createdAt);
  });

  kind(n: AppNotification) {
    return notificationKind(n.type);
  }

  open(n: AppNotification): void {
    void this.service.markRead(n);
    if (n.link) void this.router.navigateByUrl(n.link);
  }
}
