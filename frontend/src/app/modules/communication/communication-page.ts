import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, filter, of, switchMap } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { ClientSummary } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { ClientsService } from '../clients/clients.service';
import { DocumentsService, StoredDocument } from '../documents/documents.service';

type Channel = 'EMAIL' | 'WHATSAPP' | 'TELEFONE' | 'PRESENCIAL' | 'CARTA' | 'INTERNO';

interface Communication {
  id: string;
  channel: Channel;
  direction: 'SAIDA' | 'ENTRADA';
  status: 'ENVIADO' | 'FALHOU' | 'RECEBIDO' | 'REGISTRADO';
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  address: string | null;
  subject: string | null;
  body: string;
  error: string | null;
  attachments: string[];
  templateName: string | null;
  followUpDate: string | null;
  followUpDone: boolean;
  createdByName: string;
  createdAt: string;
}

interface MessageTemplate {
  id: string;
  name: string;
  channel: Channel;
  subject: string | null;
  body: string;
  whatsappTemplate: string | null;
}

const CHANNEL: Record<Channel, { label: string; icon: string }> = {
  EMAIL: { label: 'E-mail', icon: 'mail' }, WHATSAPP: { label: 'WhatsApp', icon: 'chat' }, TELEFONE: { label: 'Telefone', icon: 'call' },
  PRESENCIAL: { label: 'Presencial', icon: 'person' }, CARTA: { label: 'Carta', icon: 'local_post_office' }, INTERNO: { label: 'Nota interna', icon: 'sticky_note_2' },
};
const FIELDS_HINT = '{{cliente.nome}}, {{processo.numero}}, {{escritorio.nome}}, {{advogado.nome}}, {{data.hoje}}';

/** Central de comunicação: histórico de contatos, envio de e-mail e WhatsApp, modelos e lembretes de retorno. */
@Component({
  selector: 'app-communication-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTabsModule,
    PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Comunicação" title="Atendimento e mensagens" subtitle="Histórico de contatos com clientes, e-mails e WhatsApp enviados pelo sistema, modelos e retornos pendentes.">
        <button mat-stroked-button (click)="register()"><mat-icon>edit_note</mat-icon>Registrar contato</button>
        <button mat-flat-button [matMenuTriggerFor]="nm"><mat-icon>send</mat-icon>Nova mensagem</button>
        <mat-menu #nm="matMenu" xPosition="before">
          <button mat-menu-item (click)="compose('EMAIL')" [disabled]="!channels()['EMAIL']"><mat-icon>mail</mat-icon>E-mail{{ channels()['EMAIL'] ? '' : ' (configure o SMTP)' }}</button>
          <button mat-menu-item (click)="compose('WHATSAPP')" [disabled]="!channels()['WHATSAPP']"><mat-icon>chat</mat-icon>WhatsApp{{ channels()['WHATSAPP'] ? '' : ' (configure a API)' }}</button>
        </mat-menu>
      </app-page-header>
      @if (!channels()['EMAIL'] || !channels()['WHATSAPP']) {
        <p class="muted small">Envio por {{ !channels()['EMAIL'] ? 'e-mail' : '' }}{{ !channels()['EMAIL'] && !channels()['WHATSAPP'] ? ' e ' : '' }}{{ !channels()['WHATSAPP'] ? 'WhatsApp' : '' }}
          indisponível até a configuração na <a routerLink="/integracoes">Central de Integrações</a>. Os registros manuais funcionam sempre.</p>
      }

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" (selectedIndexChange)="onTab($event)">
        <mat-tab label="Histórico">
          <div class="pad">
            <div class="toolbar">
              <mat-button-toggle-group [value]="channel()" (change)="channel.set($event.value); load()" aria-label="Canal">
                <mat-button-toggle value="">Todos</mat-button-toggle>
                <mat-button-toggle value="EMAIL">E-mail</mat-button-toggle>
                <mat-button-toggle value="WHATSAPP">WhatsApp</mat-button-toggle>
                <mat-button-toggle value="TELEFONE">Telefone</mat-button-toggle>
                <mat-button-toggle value="PRESENCIAL">Presencial</mat-button-toggle>
              </mat-button-toggle-group>
              @if (clientId()) { <button mat-button (click)="clearClient()"><mat-icon>close</mat-icon>Remover filtro do cliente</button> }
            </div>
            @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (!loading() && !items().length) { <app-empty-state icon="forum" title="Nenhuma comunicação" text="Envie e-mails e mensagens ou registre ligações e atendimentos." /> }
            <ul class="list">
              @for (c of items(); track c.id) {
                <li [class.in]="c.direction === 'ENTRADA'" [class.fail]="c.status === 'FALHOU'">
                  <mat-icon class="ch">{{ ch[c.channel].icon }}</mat-icon>
                  <div class="main">
                    <div class="head">
                      <strong>{{ c.direction === 'ENTRADA' ? 'Recebido de' : ch[c.channel].label + ' para' }} {{ c.clientName || c.address || '—' }}</strong>
                      <span class="muted small">{{ c.createdAt | brDate: true }} · {{ c.createdByName }}</span>
                      @if (c.status === 'FALHOU') { <span class="badge danger">falhou</span> }
                    </div>
                    @if (c.subject) { <div class="subj">{{ c.subject }}</div> }
                    <div class="body">{{ c.body }}</div>
                    <div class="muted small">
                      @if (c.caseNumber) { <a [routerLink]="['/processos', c.caseId]" class="mono">{{ c.caseNumber }}</a> · }
                      @if (c.attachments.length) { <mat-icon class="mi">attach_file</mat-icon>{{ c.attachments.join(', ') }} · }
                      @if (c.followUpDate) { retorno {{ c.followUpDate | brDate }}{{ c.followUpDone ? ' (feito)' : '' }} }
                      @if (c.error) { <span class="danger">{{ c.error }}</span> }
                    </div>
                  </div>
                </li>
              }
            </ul>
          </div>
        </mat-tab>
        <mat-tab label="Retornos pendentes">
          <div class="pad">
            @if (!followUps().length) { <app-empty-state icon="event_available" title="Nenhum retorno pendente" text="Defina uma data de retorno ao registrar um contato para ser lembrado." /> }
            <ul class="list">
              @for (c of followUps(); track c.id) {
                <li [class.late]="isLate(c)">
                  <mat-icon class="ch">{{ ch[c.channel].icon }}</mat-icon>
                  <div class="main">
                    <div class="head"><strong>{{ c.clientName || c.address || 'Contato' }}</strong><span class="small" [class.danger]="isLate(c)">retornar em {{ c.followUpDate | brDate }}</span></div>
                    <div class="body">{{ c.subject || c.body }}</div>
                  </div>
                  <button mat-stroked-button (click)="done(c)"><mat-icon>done</mat-icon>Feito</button>
                </li>
              }
            </ul>
          </div>
        </mat-tab>
        <mat-tab label="Modelos de mensagem">
          <div class="pad">
            <div class="toolbar"><span class="muted small">Campos automáticos: {{ hint }}</span><span class="spacer"></span>
              <button mat-stroked-button (click)="editTemplate(null)"><mat-icon>add</mat-icon>Novo modelo</button></div>
            @if (!templates().length) { <p class="muted small">Nenhum modelo cadastrado.</p> }
            <div class="grid">
              @for (t of templates(); track t.id) {
                <div class="card tpl">
                  <div class="head"><strong>{{ t.name }}</strong><span class="badge">{{ ch[t.channel].label }}</span></div>
                  @if (t.subject) { <div class="small"><strong>Assunto:</strong> {{ t.subject }}</div> }
                  <div class="body small">{{ t.body }}</div>
                  <div class="acts"><button mat-button (click)="editTemplate(t)">Editar</button><button mat-button class="danger" (click)="removeTemplate(t)">Excluir</button></div>
                </div>
              }
            </div>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .pad { padding: 14px 0; display: flex; flex-direction: column; gap: 10px; }
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .small { font-size: 12px; }
    .mi { font-size: 14px; width: 14px; height: 14px; vertical-align: -2px; }
    .list { list-style: none; margin: 0; padding: 0; }
    .list li { display: flex; gap: 12px; align-items: flex-start; padding: 12px 4px; border-bottom: 1px solid var(--app-border); }
    .list li.in { background: var(--app-gold-soft); }
    .list li.fail .ch { color: var(--app-danger); }
    .list li.late { border-left: 3px solid var(--app-danger); padding-left: 8px; }
    .ch { color: var(--app-gold); flex: none; margin-top: 2px; }
    .main { flex: 1; min-width: 0; }
    .head { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .subj { font-weight: 600; margin-top: 2px; }
    .body { white-space: pre-wrap; margin: 4px 0; max-height: 9em; overflow: hidden; }
    .danger { color: var(--app-danger); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
    .tpl { padding: 12px 14px; display: flex; flex-direction: column; gap: 6px; }
    .acts { display: flex; gap: 4px; margin-top: auto; }
  `,
})
export class CommunicationPageComponent {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly ch = CHANNEL;
  readonly hint = FIELDS_HINT;
  readonly channels = signal<Record<string, boolean>>({});
  readonly channel = signal<Channel | ''>((this.route.snapshot.queryParamMap.get('canal') as Channel) ?? '');
  readonly clientId = signal<string | null>(this.route.snapshot.queryParamMap.get('cliente'));
  readonly items = signal<Communication[]>([]);
  readonly followUps = signal<Communication[]>([]);
  readonly templates = signal<MessageTemplate[]>([]);
  readonly loading = signal(false);

  constructor() {
    this.api.get<Record<string, boolean>>('/communications/channels').subscribe((c) => this.channels.set(c));
    this.load();
    this.loadTemplates();
  }

  load(): void {
    this.loading.set(true);
    this.api.get<Communication[]>('/communications', { channel: this.channel() || null, clientId: this.clientId() }).subscribe({
      next: (l) => { this.items.set(l); this.loading.set(false); }, error: () => this.loading.set(false),
    });
  }

  private loadFollowUps(): void {
    this.api.get<Communication[]>('/communications', { followUps: true }).subscribe((l) => this.followUps.set(l));
  }

  private loadTemplates(): void {
    this.api.get<MessageTemplate[]>('/communications/templates').subscribe((t) => this.templates.set(t));
  }

  onTab(i: number): void {
    if (i === 1) this.loadFollowUps();
  }

  clearClient(): void {
    this.clientId.set(null);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { cliente: null }, queryParamsHandling: 'merge' });
    this.load();
  }

  isLate(c: Communication): boolean {
    return !!c.followUpDate && c.followUpDate < new Date().toISOString().slice(0, 10);
  }

  compose(channel: 'EMAIL' | 'WHATSAPP'): void {
    this.dialog.open(ComposeDialogComponent, { data: { channel, clientId: this.clientId(), templates: this.templates() }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success(channel === 'EMAIL' ? 'E-mail enviado.' : 'Mensagem enviada.'); this.load(); } });
  }

  register(): void {
    this.dialog.open(ComposeDialogComponent, { data: { channel: 'TELEFONE', clientId: this.clientId(), templates: [], manual: true }, width: '700px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Contato registrado.'); this.load(); } });
  }

  done(c: Communication): void {
    this.api.post(`/communications/${encodeURIComponent(c.id)}/follow-up-done`).subscribe(() => this.loadFollowUps());
  }

  editTemplate(t: MessageTemplate | null): void {
    this.dialog.open(TemplateDialogComponent, { data: t, width: '640px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Modelo salvo.'); this.loadTemplates(); } });
  }

  removeTemplate(t: MessageTemplate): void {
    this.dialog.open(ConfirmDialogComponent, { data: { title: 'Excluir modelo', message: t.name, confirmLabel: 'Excluir', danger: true }, width: '420px' })
      .afterClosed().subscribe((ok) => { if (ok) this.api.delete(`/communications/templates/${encodeURIComponent(t.id)}`).subscribe(() => this.loadTemplates()); });
  }
}

// ======================================================================= escrever / registrar
interface ComposeData { channel: Channel; clientId: string | null; templates: MessageTemplate[]; manual?: boolean }

@Component({
  selector: 'app-compose-dialog',
  imports: [FormsModule, ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatAutocompleteModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>{{ data.manual ? 'edit_note' : channel === 'EMAIL' ? 'mail' : 'chat' }}</mat-icon>
      {{ data.manual ? 'Registrar contato' : channel === 'EMAIL' ? 'Novo e-mail' : 'Nova mensagem de WhatsApp' }}</h2>
    <mat-dialog-content>
      @if (saving()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="form-grid">
        @if (data.manual) {
          <mat-form-field class="c6"><mat-label>Canal</mat-label>
            <mat-select [(ngModel)]="channel">@for (c of manualChannels; track c) { <mat-option [value]="c">{{ ch[c].label }}</mat-option> }</mat-select></mat-form-field>
          <mat-form-field class="c6"><mat-label>Sentido</mat-label>
            <mat-select [(ngModel)]="direction"><mat-option value="SAIDA">O escritório entrou em contato</mat-option><mat-option value="ENTRADA">O cliente entrou em contato</mat-option></mat-select></mat-form-field>
        }
        <mat-form-field class="c6"><mat-label>Cliente</mat-label>
          <input matInput [formControl]="clientSearch" [matAutocomplete]="ca" placeholder="Nome ou CPF/CNPJ" />
          <mat-autocomplete #ca="matAutocomplete" [displayWith]="display" (optionSelected)="pickClient($event.option.value)">
            @for (c of clientOptions(); track c.id) { <mat-option [value]="c">{{ c.name }} · {{ c.documentMasked }}</mat-option> }
          </mat-autocomplete></mat-form-field>
        @if (!data.manual) {
          <mat-form-field class="c6"><mat-label>{{ channel === 'EMAIL' ? 'E-mail do destinatário' : 'WhatsApp (DDD + número)' }}</mat-label>
            <input matInput [(ngModel)]="to" /></mat-form-field>
          @if (templatesFor().length) {
            <mat-form-field><mat-label>Modelo</mat-label>
              <mat-select [(ngModel)]="templateId" (ngModelChange)="applyTemplate()">
                <mat-option value="">Sem modelo</mat-option>
                @for (t of templatesFor(); track t.id) { <mat-option [value]="t.id">{{ t.name }}</mat-option> }
              </mat-select></mat-form-field>
          }
        }
        @if (channel === 'EMAIL' || data.manual) {
          <mat-form-field><mat-label>{{ data.manual ? 'Assunto (opcional)' : 'Assunto' }}</mat-label><input matInput [(ngModel)]="subject" maxlength="200" /></mat-form-field>
        }
        <mat-form-field><mat-label>{{ data.manual ? 'Resumo do contato' : 'Mensagem' }}</mat-label>
          <textarea matInput rows="8" [(ngModel)]="body" maxlength="8000"></textarea>
          @if (missing().length) { <mat-hint class="warn">Sem dado para: {{ missing().join(', ') }}</mat-hint> }</mat-form-field>
        @if (!data.manual && channel === 'EMAIL' && docs().length) {
          <mat-form-field><mat-label>Anexos da central de documentos</mat-label>
            <mat-select multiple [(ngModel)]="documentIds">@for (d of docs(); track d.id) { <mat-option [value]="d.id">{{ d.title }} ({{ d.fileName }})</mat-option> }</mat-select></mat-form-field>
        }
        @if (!data.manual && channel === 'WHATSAPP') {
          <mat-form-field class="c6"><mat-label>Modelo aprovado na Meta (opcional)</mat-label><input matInput [(ngModel)]="waTemplate" />
            <mat-hint>Necessário fora da janela de 24 h</mat-hint></mat-form-field>
        }
        <mat-form-field class="c6"><mat-label>Lembrar de retornar em</mat-label><input matInput type="date" [(ngModel)]="followUpDate" /></mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving() || !body.trim()"><mat-icon>{{ data.manual ? 'save' : 'send' }}</mat-icon>{{ data.manual ? 'Registrar' : 'Enviar' }}</button>
    </mat-dialog-actions>
  `,
  styles: `.warn { color: var(--app-warning); }`,
})
export class ComposeDialogComponent {
  readonly data = inject<ComposeData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ComposeDialogComponent>);
  private readonly api = inject(ApiService);
  private readonly clients = inject(ClientsService);
  private readonly documents = inject(DocumentsService);
  readonly ch = CHANNEL;
  readonly manualChannels: Channel[] = ['TELEFONE', 'PRESENCIAL', 'EMAIL', 'WHATSAPP', 'CARTA', 'INTERNO'];
  readonly saving = signal(false);
  readonly clientOptions = signal<ClientSummary[]>([]);
  readonly docs = signal<StoredDocument[]>([]);
  readonly missing = signal<string[]>([]);
  readonly clientSearch = new FormControl<ClientSummary | string>('');
  channel: Channel = this.data.channel;
  direction: 'SAIDA' | 'ENTRADA' = 'SAIDA';
  clientId: string | null = this.data.clientId;
  to = '';
  subject = '';
  body = '';
  templateId = '';
  waTemplate = '';
  followUpDate = '';
  documentIds: string[] = [];
  readonly templatesFor = computed(() => this.data.templates.filter((t) => t.channel === this.data.channel));

  constructor() {
    this.clientSearch.valueChanges.pipe(
      debounceTime(250), filter((v): v is string => typeof v === 'string' && v.trim().length >= 2),
      switchMap((t) => this.clients.list({ search: t.trim() }, null, 8).pipe(catchError(() => of({ items: [] as ClientSummary[], nextCursor: null })))),
      takeUntilDestroyed(),
    ).subscribe((p) => this.clientOptions.set(p.items));
    if (this.clientId) {
      this.api.get<{ id: string; name: string; email: string | null; whatsapp: string | null; phone: string | null; documentMasked: string }>(`/clients/${encodeURIComponent(this.clientId)}`)
        .subscribe((c) => { this.clientSearch.setValue({ id: c.id, name: c.name, documentMasked: c.documentMasked } as ClientSummary, { emitEvent: false }); this.fillContact(c); });
      this.loadDocs();
    }
  }

  display = (v: ClientSummary | string | null): string => (typeof v === 'string' ? v : v?.name ?? '');

  pickClient(c: ClientSummary): void {
    this.clientId = c.id;
    this.api.get<{ email: string | null; whatsapp: string | null; phone: string | null }>(`/clients/${encodeURIComponent(c.id)}`).subscribe((x) => this.fillContact(x));
    this.loadDocs();
    if (this.templateId) this.applyTemplate();
  }

  private fillContact(c: { email: string | null; whatsapp: string | null; phone: string | null }): void {
    this.to = (this.channel === 'EMAIL' ? c.email : c.whatsapp ?? c.phone) ?? this.to;
  }

  private loadDocs(): void {
    if (this.channel !== 'EMAIL' || !this.clientId) return;
    this.documents.list({ clientId: this.clientId }, null, 50).subscribe((p) => this.docs.set(p.items.filter((d) => !d.confidential)));
  }

  applyTemplate(): void {
    const t = this.data.templates.find((x) => x.id === this.templateId);
    if (!t) return;
    this.waTemplate = t.whatsappTemplate ?? '';
    this.api.get<{ subject: string | null; body: string; missing: string[] }>(`/communications/templates/${encodeURIComponent(t.id)}/render`, { clientId: this.clientId })
      .subscribe((r) => { this.subject = r.subject ?? ''; this.body = r.body; this.missing.set(r.missing); });
  }

  save(): void {
    this.saving.set(true);
    const req = this.data.manual
      ? this.api.post('/communications/register', { channel: this.channel, direction: this.direction, clientId: this.clientId, caseId: null,
          subject: this.subject || null, body: this.body, followUpDate: this.followUpDate || null })
      : this.api.post('/communications/send', { channel: this.channel, clientId: this.clientId, caseId: null, to: this.to || null, subject: this.subject || null,
          body: this.body, templateId: this.templateId || null, documentIds: this.documentIds, followUpDate: this.followUpDate || null,
          whatsappTemplate: this.waTemplate || null, whatsappParams: null });
    req.subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= modelo
@Component({
  selector: 'app-message-template-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>article</mat-icon>{{ t ? 'Editar modelo' : 'Novo modelo' }}</h2>
    <mat-dialog-content>
      <div class="form-grid">
        <mat-form-field class="c8"><mat-label>Nome</mat-label><input matInput [(ngModel)]="name" maxlength="80" /></mat-form-field>
        <mat-form-field class="c4"><mat-label>Canal</mat-label>
          <mat-select [(ngModel)]="channel"><mat-option value="EMAIL">E-mail</mat-option><mat-option value="WHATSAPP">WhatsApp</mat-option></mat-select></mat-form-field>
        @if (channel === 'EMAIL') { <mat-form-field><mat-label>Assunto</mat-label><input matInput [(ngModel)]="subject" maxlength="200" /></mat-form-field> }
        <mat-form-field><mat-label>Texto</mat-label><textarea matInput rows="8" [(ngModel)]="body" maxlength="8000"></textarea>
          <mat-hint>Campos: {{ hint }}</mat-hint></mat-form-field>
        @if (channel === 'WHATSAPP') {
          <mat-form-field><mat-label>Nome do modelo aprovado na Meta (opcional)</mat-label><input matInput [(ngModel)]="whatsappTemplate" /></mat-form-field>
        }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="!name.trim() || !body.trim()">Salvar</button>
    </mat-dialog-actions>
  `,
})
export class TemplateDialogComponent {
  readonly t = inject<MessageTemplate | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<TemplateDialogComponent>);
  private readonly api = inject(ApiService);
  readonly hint = FIELDS_HINT;
  name = this.t?.name ?? '';
  channel: Channel = this.t?.channel ?? 'EMAIL';
  subject = this.t?.subject ?? '';
  body = this.t?.body ?? '';
  whatsappTemplate = this.t?.whatsappTemplate ?? '';

  save(): void {
    const body = { name: this.name, channel: this.channel, subject: this.subject || null, body: this.body, whatsappTemplate: this.whatsappTemplate || null };
    (this.t ? this.api.put(`/communications/templates/${encodeURIComponent(this.t.id)}`, body) : this.api.post('/communications/templates', body))
      .subscribe((r) => this.ref.close(r));
  }
}
