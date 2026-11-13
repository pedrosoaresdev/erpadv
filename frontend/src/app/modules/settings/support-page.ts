import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute } from '@angular/router';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { OfficeService, SupportTicket, TICKET_CATEGORIES, TICKET_LABELS, TICKET_PRIORITIES, TicketStatus } from './office.service';

/** Conversa de um chamado (usada pelo escritório e pelo painel master). */
@Component({
  selector: 'app-ticket-thread',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, BrDatePipe],
  template: `
    @let t = ticket();
    <div class="thread-head">
      <div>
        <span class="mono muted small">{{ t.number }}</span>
        <h3>{{ t.subject }}</h3>
        <span class="muted small">{{ platform() ? t.officeName + ' · ' : '' }}{{ categoryLabel(t.category) }} · prioridade {{ t.priority.toLowerCase() }}
          · aberto por {{ t.openedByName || t.openedByEmail || '—' }}</span>
      </div>
      <span class="badge" [class.success]="t.status === 'RESOLVIDO'" [class.gold]="t.status === 'AGUARDANDO_ESCRITORIO'">{{ labels[t.status] }}</span>
    </div>
    <div class="messages">
      @for (m of t.messages; track $index) {
        <div class="msg" [class.mine]="m.fromPlatform === platform()">
          <div class="meta"><strong>{{ m.authorName }}</strong><span class="muted small">{{ m.at | brDate: true }}</span></div>
          <p>{{ m.text }}</p>
        </div>
      }
    </div>
    <div class="reply">
      <mat-form-field class="grow" subscriptSizing="dynamic"><mat-label>{{ t.status === 'RESOLVIDO' ? 'Reabrir com uma nova mensagem' : 'Responder' }}</mat-label>
        <textarea matInput [(ngModel)]="text" rows="3" maxlength="8000"></textarea></mat-form-field>
      <div class="bar">
        @if (platform()) {
          <mat-form-field subscriptSizing="dynamic" class="status"><mat-label>Situação após responder</mat-label>
            <mat-select [(ngModel)]="nextStatus">
              <mat-option [value]="null">Aguardando o escritório</mat-option>
              <mat-option value="EM_ATENDIMENTO">Em atendimento</mat-option>
              <mat-option value="RESOLVIDO">Resolvido</mat-option>
            </mat-select>
          </mat-form-field>
        } @else if (t.status !== 'RESOLVIDO') {
          <button mat-button (click)="send('RESOLVIDO')" [disabled]="busy()"><mat-icon>task_alt</mat-icon>Marcar como resolvido</button>
        }
        <span class="spacer"></span>
        <button mat-flat-button (click)="send(platform() ? nextStatus : null)" [disabled]="busy() || !text.trim()"><mat-icon>send</mat-icon>Enviar</button>
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .thread-head { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    h3 { margin: 2px 0; font-size: 16px; }
    .small { font-size: 12px; }
    .messages { display: flex; flex-direction: column; gap: 10px; padding: 16px; max-height: 52vh; overflow: auto; }
    .msg { max-width: 82%; border: 1px solid var(--app-border); border-radius: 12px; padding: 10px 12px; background: var(--app-surface-2); align-self: flex-start; }
    .msg.mine { align-self: flex-end; background: var(--app-surface); border-color: var(--app-gold); }
    .msg p { margin: 4px 0 0; white-space: pre-line; overflow-wrap: anywhere; }
    .meta { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .reply { padding: 12px 16px; border-top: 1px solid var(--app-border); }
    .grow { width: 100%; }
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 8px; }
    .status { width: 240px; }
  `,
})
export class TicketThreadComponent {
  readonly ticket = input.required<SupportTicket>();
  readonly platform = input(false);
  readonly busy = input(false);
  readonly reply = output<{ message: string | null; status: TicketStatus | null }>();
  readonly labels = TICKET_LABELS;
  text = '';
  nextStatus: TicketStatus | null = null;

  categoryLabel(c: string): string {
    return TICKET_CATEGORIES.find((x) => x.value === c)?.label ?? c;
  }

  send(status: TicketStatus | null): void {
    this.reply.emit({ message: this.text.trim() || null, status });
    this.text = '';
  }
}

/** Suporte: chamados do escritório para a equipe da plataforma. */
@Component({
  selector: 'app-support-page',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, PageHeaderComponent,
    EmptyStateComponent, BrDatePipe, TicketThreadComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Ajuda" title="Suporte" subtitle="Abra chamados para a equipe do ERPAdv e acompanhe as respostas.">
        <button mat-flat-button (click)="creating.set(true); selected.set(null)"><mat-icon>add_comment</mat-icon>Novo chamado</button>
      </app-page-header>

      <div class="layout">
        <section class="card list">
          @if (tickets().length === 0) {
            <app-empty-state icon="support_agent" title="Nenhum chamado" text="Dúvidas, problemas ou pedidos sobre o plano: abra um chamado." />
          }
          @for (t of tickets(); track t.id) {
            <button class="item" [class.active]="selected()?.id === t.id" (click)="select(t)">
              <div class="row"><span class="mono small muted">{{ t.number }}</span><span class="badge" [class.success]="t.status === 'RESOLVIDO'"
                [class.gold]="t.status === 'AGUARDANDO_ESCRITORIO'">{{ labels[t.status] }}</span></div>
              <strong>{{ t.subject }}</strong>
              <span class="muted small">{{ t.lastMessageAt | brDate: true }}</span>
            </button>
          }
        </section>

        <section class="card detail">
          @if (creating()) {
            <div class="pad">
              <h3>Novo chamado</h3>
              <div class="grid">
                <mat-form-field class="c2"><mat-label>Assunto</mat-label><input matInput [(ngModel)]="draft.subject" maxlength="160" /></mat-form-field>
                <mat-form-field><mat-label>Categoria</mat-label>
                  <mat-select [(ngModel)]="draft.category">@for (c of categories; track c.value) { <mat-option [value]="c.value">{{ c.label }}</mat-option> }</mat-select>
                </mat-form-field>
                <mat-form-field><mat-label>Prioridade</mat-label>
                  <mat-select [(ngModel)]="draft.priority">@for (p of priorities; track p.value) { <mat-option [value]="p.value">{{ p.label }}</mat-option> }</mat-select>
                </mat-form-field>
                <mat-form-field class="full"><mat-label>Descreva o que precisa</mat-label>
                  <textarea matInput [(ngModel)]="draft.message" rows="6" maxlength="8000"></textarea></mat-form-field>
              </div>
              <p class="muted small">Não envie senhas, certificados ou dados sigilosos de clientes no chamado.</p>
              <div class="bar">
                <span class="spacer"></span>
                <button mat-button (click)="creating.set(false)">Cancelar</button>
                <button mat-flat-button (click)="open()" [disabled]="busy() || !draft.subject.trim() || !draft.message.trim()"><mat-icon>send</mat-icon>Abrir chamado</button>
              </div>
            </div>
          } @else if (selected(); as t) {
            <app-ticket-thread [ticket]="t" [busy]="busy()" (reply)="send(t, $event)" />
          } @else {
            <app-empty-state icon="forum" title="Selecione um chamado" text="Ou abra um novo chamado para falar com a equipe do ERPAdv." />
          }
        </section>
      </div>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); gap: 16px; align-items: start; }
    .list { display: flex; flex-direction: column; max-height: 72vh; overflow: auto; }
    .item { text-align: left; border: 0; border-bottom: 1px solid var(--app-border); background: transparent; color: inherit; font: inherit;
      padding: 12px 14px; display: flex; flex-direction: column; gap: 3px; cursor: pointer; }
    .item:hover, .item.active { background: var(--app-surface-2); }
    .row { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .small { font-size: 12px; }
    .pad { padding: 16px 20px; }
    h3 { margin: 0 0 12px; font-size: 16px; }
    .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0 12px; }
    .c2 { grid-column: span 2; }
    .full { grid-column: 1 / -1; }
    .bar { display: flex; gap: 8px; align-items: center; }
    @media (max-width: 900px) { .layout, .grid { grid-template-columns: minmax(0, 1fr); } .c2 { grid-column: auto; } }
  `,
})
export class SupportPageComponent {
  private readonly office = inject(OfficeService);
  private readonly toast = inject(ToastService);
  readonly labels = TICKET_LABELS;
  readonly categories = TICKET_CATEGORIES;
  readonly priorities = TICKET_PRIORITIES;
  readonly tickets = signal<SupportTicket[]>([]);
  readonly selected = signal<SupportTicket | null>(null);
  readonly creating = signal(false);
  readonly busy = signal(false);
  readonly awaiting = computed(() => this.tickets().filter((t) => t.status === 'AGUARDANDO_ESCRITORIO').length);
  draft = { subject: '', category: 'DUVIDA', priority: 'NORMAL', message: '' };
  private readonly wanted = inject(ActivatedRoute).snapshot.queryParamMap.get('chamado');

  constructor() {
    this.load();
  }

  load(): void {
    this.office.tickets().subscribe((list) => {
      this.tickets.set(list);
      const keep = this.selected()?.id ?? this.wanted;
      if (keep) this.selected.set(list.find((t) => t.id === keep) ?? null);
    });
  }

  select(t: SupportTicket): void {
    this.creating.set(false);
    this.selected.set(t);
  }

  open(): void {
    this.busy.set(true);
    this.office.openTicket({ ...this.draft, subject: this.draft.subject.trim(), message: this.draft.message.trim() }).subscribe({
      next: (t) => {
        this.busy.set(false);
        this.creating.set(false);
        this.draft = { subject: '', category: 'DUVIDA', priority: 'NORMAL', message: '' };
        this.toast.success(`Chamado ${t.number} aberto.`);
        this.selected.set(t);
        this.load();
      },
      error: () => this.busy.set(false),
    });
  }

  send(t: SupportTicket, r: { message: string | null; status: TicketStatus | null }): void {
    this.busy.set(true);
    this.office.reply(t.id, r.message, r.status).subscribe({
      next: (u) => { this.busy.set(false); this.selected.set(u); this.load(); },
      error: () => this.busy.set(false),
    });
  }
}
