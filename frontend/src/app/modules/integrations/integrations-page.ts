import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { EmployeeOption } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { DeadlineFormDialogComponent } from '../deadlines/deadline-form-dialog';
import { saveBlob } from '../finance/finance.service';
import { Connector, GoogleStatus, ImportResult, IntegrationRun, IntegrationsService, Provider, Publication, PublicationStatus } from './integrations.service';

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const STATUS_LABELS: Record<PublicationStatus, string> = { NOVA: 'Nova', LIDA: 'Lida', PRAZO_CRIADO: 'Prazo criado', ARQUIVADA: 'Arquivada' };
const RUN_LABELS: Record<IntegrationRun['status'], string> = { EXECUTANDO: 'Executando', SUCESSO: 'Sucesso', PARCIAL: 'Parcial', ERRO: 'Erro' };

/** Central Judicial e Central de Integrações (conectores, sincronizações, publicações e importação assistida). */
@Component({
  selector: 'app-integrations-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatSelectModule,
    MatTabsModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Integrações" [title]="tab() === 3 ? 'Central de Integrações' : 'Central Judicial'"
        subtitle="Consulta processual oficial (DataJud/CNJ), publicações do DJEN, importação assistida e conectores de comunicação, agenda e assinatura." />

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="onTab($event)">
        <mat-tab label="Central Judicial">
          <div class="pad">
            <div class="grid">
              @for (c of judicial(); track c.provider) {
                <div class="card conn" [class]="c.state">
                  <div class="row"><strong>{{ c.label }}</strong><span class="badge" [class]="c.state">{{ c.stateLabel }}</span></div>
                  <p class="muted small">{{ c.description }}</p>
                  @if (c.availability !== 'SEM_API') {
                    <div class="small">Última sincronização: {{ c.lastRunAt ? (c.lastRunAt | brDate: true) : 'nunca' }}</div>
                    @if (c.lastError) { <div class="small danger"><mat-icon class="mi">warning</mat-icon>{{ c.lastError }}</div> }
                    <div class="actions">
                      @if (c.provider === 'DATAJUD' && auth.has('CASES_WRITE')) {
                        <button mat-flat-button (click)="syncDataJud()" [disabled]="busy() === 'DATAJUD' || c.state === 'NAO_CONFIGURADO' || c.state === 'DESATIVADO'">
                          <mat-icon>sync</mat-icon>{{ busy() === 'DATAJUD' ? 'Sincronizando…' : 'Sincronizar processos' }}</button>
                      }
                      @if (c.provider === 'DJEN' && auth.has('DEADLINES_WRITE')) {
                        <button mat-flat-button (click)="syncDjen()" [disabled]="busy() === 'DJEN' || c.state === 'DESATIVADO'">
                          <mat-icon>sync</mat-icon>{{ busy() === 'DJEN' ? 'Consultando…' : 'Buscar publicações (7 dias)' }}</button>
                      }
                    </div>
                  } @else {
                    <button mat-stroked-button class="actions" (click)="tab.set(2)"><mat-icon>upload_file</mat-icon>Importação assistida</button>
                  }
                </div>
              }
            </div>
            <h3>Histórico de sincronizações</h3>
            @if (!runs().length) { <p class="muted small">Nenhuma sincronização ainda.</p> }
            @else {
              <div class="table-wrap">
                <table class="data stack">
                  <thead><tr><th>Quando</th><th>Conector</th><th>Origem</th><th>Situação</th><th>Resultado</th></tr></thead>
                  <tbody>
                    @for (r of runs(); track r.id) {
                      <tr>
                        <td data-label="Quando">{{ r.startedAt | brDate: true }}</td>
                        <td data-label="Conector">{{ r.provider }}</td>
                        <td data-label="Origem">{{ r.trigger === 'MANUAL' ? 'Manual · ' + (r.triggeredByName || '') : 'Automática' }}</td>
                        <td data-label="Situação"><span class="badge" [class]="r.status">{{ runLabels[r.status] }}</span></td>
                        <td data-label="Resultado">{{ r.message || '—' }}
                          @if (r.details.length) { <details><summary class="small">{{ r.details.length }} ocorrência(s)</summary>
                            <ul class="small">@for (d of r.details; track $index) { <li>{{ d }}</li> }</ul></details> }</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </mat-tab>

        <mat-tab label="Publicações (DJEN)">
          <div class="pad">
            <div class="toolbar">
              <mat-button-toggle-group [value]="pubStatus()" (change)="pubStatus.set($event.value); loadPubs()" aria-label="Situação">
                <mat-button-toggle value="NOVA">Novas</mat-button-toggle>
                <mat-button-toggle value="LIDA">Lidas</mat-button-toggle>
                <mat-button-toggle value="PRAZO_CRIADO">Com prazo</mat-button-toggle>
                <mat-button-toggle value="ARQUIVADA">Arquivadas</mat-button-toggle>
                <mat-button-toggle value="">Todas</mat-button-toggle>
              </mat-button-toggle-group>
              <mat-select class="sel" [ngModel]="pubLawyer()" (ngModelChange)="pubLawyer.set($event); loadPubs()" aria-label="Advogado">
                <mat-option value="">Todos os advogados</mat-option>
                @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }
              </mat-select>
              <span class="muted small">Últimos 30 dias · consulta pela OAB cadastrada em Equipe</span>
            </div>
            @if (pubLoading()) { <mat-progress-bar mode="indeterminate" /> }
            @if (!pubLoading() && !pubs().length) {
              <app-empty-state icon="newspaper" title="Nenhuma publicação" text="As intimações do DJEN chegam automaticamente duas vezes por dia para as OABs da equipe." />
            }
            @for (p of pubs(); track p.id) {
              <div class="card pub" [class.new]="p.status === 'NOVA'">
                <div class="row">
                  <div class="main">
                    <strong>{{ p.kind || 'Comunicação' }} · {{ p.court }}</strong> <span class="badge" [class]="p.status">{{ statusLabels[p.status] }}</span>
                    <div class="small muted">Disponibilizada em {{ p.availableDate | brDate }} · {{ p.organ }} · OAB {{ p.oab }} ({{ p.lawyerName }})</div>
                    <div class="small">
                      @if (p.caseId) { <a [routerLink]="['/processos', p.caseId]" class="mono">{{ p.caseNumber }}</a> · {{ p.clientName }} }
                      @else { <span class="mono">{{ p.caseNumber || 'sem número' }}</span> · <span class="warn">processo não cadastrado no escritório</span> }
                      @if (p.className) { · {{ p.className }} }
                    </div>
                  </div>
                  <div class="acts">
                    @if (!p.deadlineId && p.status !== 'ARQUIVADA' && auth.has('DEADLINES_WRITE')) {
                      <button mat-flat-button (click)="createDeadline(p)"><mat-icon>alarm_add</mat-icon>Criar prazo</button>
                    }
                    <button mat-icon-button [matMenuTriggerFor]="pm" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                    <mat-menu #pm="matMenu" xPosition="before">
                      @if (p.status === 'NOVA') { <button mat-menu-item (click)="setStatus(p, 'LIDA')"><mat-icon>done</mat-icon>Marcar como lida</button> }
                      @if (p.status !== 'ARQUIVADA') { <button mat-menu-item (click)="setStatus(p, 'ARQUIVADA')"><mat-icon>archive</mat-icon>Arquivar</button> }
                      @else { <button mat-menu-item (click)="setStatus(p, 'LIDA')"><mat-icon>unarchive</mat-icon>Desarquivar</button> }
                      @if (p.link) { <a mat-menu-item [href]="p.link" target="_blank" rel="noopener noreferrer"><mat-icon>open_in_new</mat-icon>Abrir no tribunal</a> }
                    </mat-menu>
                  </div>
                </div>
                <details (toggle)="p.status === 'NOVA' && auth.has('DEADLINES_READ') ? setStatus(p, 'LIDA', true) : null">
                  <summary class="small">Ler o inteiro teor</summary>
                  <p class="text">{{ p.text }}</p>
                </details>
              </div>
            }
          </div>
        </mat-tab>

        <mat-tab label="Importação assistida">
          <div class="pad">
            <p>PJe, eproc, Projudi e e-SAJ não oferecem API pública para escritórios. Exporte a lista de processos do sistema do tribunal
              (ou monte a planilha) e importe aqui: o número CNJ evita duplicidade e o cliente é vinculado pelo CPF/CNPJ ou nome.
              Depois, use "Sincronizar processos" para trazer as movimentações pelo DataJud.</p>
            @if (auth.has('CASES_WRITE')) {
              <div class="actions">
                <button mat-stroked-button (click)="template()"><mat-icon>download</mat-icon>Baixar modelo (CSV)</button>
                <button mat-flat-button (click)="picker.click()" [disabled]="busy() === 'IMPORT'"><mat-icon>upload_file</mat-icon>{{ busy() === 'IMPORT' ? 'Importando…' : 'Importar planilha' }}</button>
                <input #picker type="file" accept=".csv,text/csv" hidden (change)="importFile($event)" />
              </div>
            } @else { <p class="muted small">Sem permissão para cadastrar processos.</p> }
            @if (importResult(); as r) {
              <p><strong>{{ r.created }}</strong> criado(s) · <strong>{{ r.duplicates }}</strong> já existente(s) · <strong [class.danger]="r.errors">{{ r.errors }}</strong> com erro — de {{ r.total }} linha(s).</p>
              <div class="table-wrap">
                <table class="data stack">
                  <thead><tr><th>Linha</th><th>Nº CNJ</th><th>Resultado</th><th>Detalhe</th></tr></thead>
                  <tbody>
                    @for (row of r.rows; track row.line) {
                      <tr>
                        <td data-label="Linha">{{ row.line }}</td>
                        <td data-label="Nº CNJ" class="mono">@if (row.caseId) { <a [routerLink]="['/processos', row.caseId]">{{ row.cnj }}</a> } @else { {{ row.cnj || '—' }} }</td>
                        <td data-label="Resultado"><span class="badge" [class]="row.status">{{ row.status === 'CRIADO' ? 'Criado' : row.status === 'DUPLICADO' ? 'Já existia' : 'Erro' }}</span></td>
                        <td data-label="Detalhe">{{ row.message }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </mat-tab>

        <mat-tab label="Conectores">
          <div class="pad">
            <div class="card conn me">
              <div class="row"><strong><mat-icon class="mi">event</mat-icon>Minha conta Google (Agenda e Meet)</strong>
                <span class="badge" [class.ATIVO]="google()?.connected">{{ google()?.connected ? 'Conectada' : 'Não conectada' }}</span></div>
              @if (google(); as g) {
                @if (!g.available) {
                  <p class="muted small">O aplicativo Google ainda não foi configurado no servidor (GOOGLE_CLIENT_ID e GOOGLE_CLIENT_SECRET). Fale com o administrador da plataforma.</p>
                } @else if (g.connected) {
                  <p class="small">Conectada como <strong>{{ g.email }}</strong> desde {{ g.connectedAt | brDate }}. Seus compromissos vão para a sua agenda Google e reuniões do Meet ganham link automático.</p>
                  <button mat-stroked-button (click)="googleDisconnect()"><mat-icon>link_off</mat-icon>Desconectar</button>
                } @else {
                  <p class="small">Conecte para enviar seus compromissos ao Google Agenda e criar salas do Google Meet automaticamente.</p>
                  <button mat-flat-button (click)="googleConnect()"><mat-icon>link</mat-icon>Conectar conta Google</button>
                }
              }
            </div>
            <div class="grid">
              @for (c of others(); track c.provider) {
                <div class="card conn">
                  <div class="row"><strong>{{ c.label }}</strong><span class="badge" [class]="c.state">{{ c.stateLabel }}</span></div>
                  <p class="muted small">{{ c.description }}</p>
                  @if (c.lastError) { <div class="small danger"><mat-icon class="mi">warning</mat-icon>{{ c.lastError }} ({{ c.lastErrorAt | brDate: true }})</div> }
                  @if (c.webhookPath && c.provider !== 'GOOGLE_CALENDAR') { <div class="small muted">Webhook: <span class="mono">{{ origin + c.webhookPath }}</span></div> }
                  @if (canManage && c.availability !== 'SEM_API' && c.provider !== 'GOOGLE_CALENDAR') {
                    <button mat-stroked-button class="actions" (click)="configure(c)"><mat-icon>settings</mat-icon>Configurar</button>
                  }
                </div>
              }
            </div>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .pad { padding: 16px 0; display: flex; flex-direction: column; gap: 12px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .conn { padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; }
    .conn p { margin: 0; }
    .row { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
    .small { font-size: 12px; }
    .mi { font-size: 16px; width: 16px; height: 16px; vertical-align: -3px; margin-right: 4px; }
    .danger { color: var(--app-danger); }
    .warn { color: var(--app-warning); }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 6px; align-self: flex-start; }
    .badge.ATIVO, .badge.SUCESSO, .badge.CRIADO, .badge.PRAZO_CRIADO { background: var(--app-success-bg, #e3f4e5); color: var(--app-success, #2e7d32); }
    .badge.ERRO { background: var(--app-danger-bg); color: var(--app-danger); }
    .badge.PARCIAL, .badge.NAO_CONFIGURADO, .badge.NOVA { background: var(--app-warning-bg); color: var(--app-warning); }
    h3 { margin: 12px 0 0; font-size: 16px; }
    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .sel { width: 220px; border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; background: var(--app-surface); }
    .pub { padding: 12px 16px; }
    .pub.new { border-left: 3px solid var(--app-gold); }
    .pub .main { flex: 1; min-width: 0; }
    .acts { display: flex; align-items: center; gap: 4px; }
    .text { white-space: pre-wrap; font-size: 13px; background: var(--app-surface-2); padding: 10px; border-radius: 8px; max-height: 360px; overflow: auto; }
    details summary { cursor: pointer; color: var(--app-text-muted); margin-top: 6px; }
    .me { margin-bottom: 4px; }
    @media (max-width: 760px) { .sel { width: 100%; } .row { flex-wrap: wrap; } }
  `,
})
export class IntegrationsPageComponent {
  private readonly service = inject(IntegrationsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  readonly auth = inject(AuthService);
  readonly canManage = this.auth.has('SETTINGS_MANAGE');
  readonly origin = typeof location !== 'undefined' ? location.origin : '';
  readonly statusLabels = STATUS_LABELS;
  readonly runLabels = RUN_LABELS;
  readonly tab = signal(Number(this.route.snapshot.data['tab'] ?? 0));
  readonly connectors = signal<Connector[]>([]);
  readonly runs = signal<IntegrationRun[]>([]);
  readonly busy = signal<string | null>(null);
  readonly pubs = signal<Publication[]>([]);
  readonly pubLoading = signal(false);
  readonly pubStatus = signal<PublicationStatus | ''>('NOVA');
  readonly pubLawyer = signal('');
  readonly importResult = signal<ImportResult | null>(null);
  readonly google = signal<GoogleStatus | null>(null);
  readonly lawyers = toSignal(inject(ApiService).get<EmployeeOption[]>('/employees/lawyers', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly judicial = computed(() => this.connectors().filter((c) => c.category === 'JUDICIAL'));
  readonly others = computed(() => this.connectors().filter((c) => c.category !== 'JUDICIAL'));

  constructor() {
    const q = this.route.snapshot.queryParamMap;
    if (q.get('aba') === 'publicacoes') this.tab.set(1);
    const g = q.get('google');
    if (g) {
      this.tab.set(3);
      if (g === 'conectado') this.toast.success('Conta Google conectada.');
      else if (g === 'sem-permissao') this.toast.error('O Google não concedeu acesso contínuo — conecte de novo e permita o acesso à agenda.');
      else if (g !== 'cancelado') this.toast.error('Não foi possível conectar a conta Google.');
    }
    this.load();
    this.service.googleStatus().pipe(catchError(() => of(null))).subscribe((s) => this.google.set(s));
    if (this.tab() === 1) this.loadPubs();
  }

  load(): void {
    this.service.catalog().subscribe((c) => this.connectors.set(c));
    this.service.runs().subscribe((r) => this.runs.set(r));
  }

  onTab(i: number): void {
    this.tab.set(i);
    if (i === 1 && !this.pubs().length) this.loadPubs();
  }

  syncDataJud(): void {
    this.busy.set('DATAJUD');
    this.service.syncDataJud().subscribe({
      next: (r) => { this.busy.set(null); this.notifyRun(r); this.load(); },
      error: () => this.busy.set(null),
    });
  }

  syncDjen(): void {
    const today = new Date();
    this.busy.set('DJEN');
    this.service.syncDjen(iso(new Date(today.getTime() - 7 * 86_400_000)), iso(today)).subscribe({
      next: (r) => { this.busy.set(null); this.notifyRun(r); this.load(); this.loadPubs(); },
      error: () => this.busy.set(null),
    });
  }

  private notifyRun(r: IntegrationRun): void {
    if (r.status === 'ERRO') this.toast.error(r.details[0] ?? r.message ?? 'Falha na sincronização.');
    else this.toast.success(r.message ?? 'Sincronização concluída.');
  }

  loadPubs(): void {
    const today = new Date();
    this.pubLoading.set(true);
    this.service.publications({ status: this.pubStatus(), lawyerUid: this.pubLawyer(), from: iso(new Date(today.getTime() - 30 * 86_400_000)), to: iso(today) })
      .subscribe({ next: (p) => { this.pubs.set(p); this.pubLoading.set(false); }, error: () => this.pubLoading.set(false) });
  }

  setStatus(p: Publication, status: PublicationStatus, silent = false): void {
    this.service.setPublicationStatus(p.id, status).subscribe((x) => {
      this.pubs.update((list) => list.map((i) => (i.id === x.id ? x : i)));
      if (!silent) this.toast.success(status === 'ARQUIVADA' ? 'Publicação arquivada.' : 'Publicação atualizada.');
    });
  }

  createDeadline(p: Publication): void {
    this.dialog.open(DeadlineFormDialogComponent, {
      data: {
        legalCase: p.caseId ? { id: p.caseId, cnjNumber: p.caseNumber, clientName: p.clientName ?? '', court: p.court } : null,
        prefill: {
          description: `${p.kind ?? 'Intimação'} — ${p.documentType ?? p.organ ?? ''}`.slice(0, 300),
          origin: 'PUBLICACAO_DJE', availabilityDate: p.availableDate, responsibleUid: p.lawyerUid ?? undefined,
          notes: `Publicação DJEN nº ${p.externalId} (${p.court}) — ${p.organ ?? ''}`,
        },
      },
      width: '860px', maxWidth: '96vw',
    }).afterClosed().subscribe((d?: { id: string }) => {
      if (d?.id) this.service.linkDeadline(p.id, d.id).subscribe((x) => {
        this.pubs.update((list) => list.map((i) => (i.id === x.id ? x : i)));
        this.toast.success('Prazo criado e vinculado à publicação.');
      });
    });
  }

  template(): void {
    this.service.importTemplate().subscribe((r) => saveBlob(r, 'modelo-importacao-processos.csv'));
  }

  importFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const f = input.files?.[0];
    input.value = '';
    if (!f) return;
    this.busy.set('IMPORT');
    this.service.importCases(f).subscribe({
      next: (r) => { this.busy.set(null); this.importResult.set(r); this.toast.success(`${r.created} processo(s) importado(s).`); },
      error: () => this.busy.set(null),
    });
  }

  googleConnect(): void {
    this.service.googleConnect().subscribe((r) => { location.href = r.url; });
  }

  googleDisconnect(): void {
    this.service.googleDisconnect().subscribe(() => {
      this.toast.success('Conta Google desconectada.');
      this.service.googleStatus().subscribe((s) => this.google.set(s));
    });
  }

  configure(c: Connector): void {
    this.dialog.open(ConnectorDialogComponent, { data: c, width: '620px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => { if (r) { this.toast.success('Integração salva.'); this.load(); } });
  }
}

// ======================================================================= configuração de conector
interface FieldDef { key: string; label: string; secret?: boolean; hint?: string; type?: string }

const FIELDS: Partial<Record<Provider, FieldDef[]>> = {
  DATAJUD: [{ key: 'apiKey', label: 'Chave da API Pública (opcional)', secret: true, hint: 'Em branco usa a chave pública configurada no servidor' }],
  SMTP: [
    { key: 'host', label: 'Servidor SMTP', hint: 'Ex.: smtp.gmail.com, smtp.office365.com' },
    { key: 'port', label: 'Porta', type: 'number', hint: '587 (STARTTLS) ou 465 (SSL)' },
    { key: 'username', label: 'Usuário' },
    { key: 'password', label: 'Senha ou senha de app', secret: true },
    { key: 'fromAddress', label: 'E-mail do remetente' },
    { key: 'fromName', label: 'Nome do remetente' },
  ],
  WHATSAPP: [
    { key: 'phoneNumberId', label: 'Phone number ID (Meta)' },
    { key: 'businessAccountId', label: 'WhatsApp Business Account ID' },
    { key: 'displayNumber', label: 'Número exibido', hint: 'Ex.: +55 81 99999-0000' },
    { key: 'accessToken', label: 'Token de acesso permanente (System User)', secret: true },
    { key: 'appSecret', label: 'App Secret (validação do webhook)', secret: true },
    { key: 'verifyToken', label: 'Token de verificação do webhook', secret: true, hint: 'Invente um valor e use o mesmo no painel da Meta' },
  ],
  ZAPSIGN: [
    { key: 'apiToken', label: 'Token da API ZapSign', secret: true },
    { key: 'webhookSecret', label: 'Segredo do webhook', secret: true, hint: 'Use na URL do webhook: ...?secret=VALOR' },
  ],
};

@Component({
  selector: 'app-connector-dialog',
  imports: [FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatSlideToggleModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>hub</mat-icon>{{ c.label }}</h2>
    <mat-dialog-content>
      <p class="muted small">{{ c.description }}</p>
      <div class="toggles">
        <mat-slide-toggle [(ngModel)]="enabled">Ativado</mat-slide-toggle>
        @if (c.provider === 'DATAJUD') { <mat-slide-toggle [(ngModel)]="autoSync">Sincronizar automaticamente todos os dias</mat-slide-toggle> }
        @if (c.provider === 'ZAPSIGN') { <mat-checkbox [(ngModel)]="sandbox">Ambiente de testes (sandbox)</mat-checkbox> }
      </div>
      @for (f of fields; track f.key) {
        <mat-form-field class="full">
          <mat-label>{{ f.label }}</mat-label>
          @if (f.secret) {
            <input matInput type="password" autocomplete="new-password" [(ngModel)]="secrets[f.key]"
              [placeholder]="c.configuredSecrets.includes(f.key) ? '•••••• (configurado — em branco mantém)' : ''" />
          } @else {
            <input matInput [type]="f.type || 'text'" [(ngModel)]="config[f.key]" />
          }
          @if (f.hint) { <mat-hint>{{ f.hint }}</mat-hint> }
        </mat-form-field>
      }
      @if (c.webhookPath) { <p class="small">URL do webhook: <span class="mono">{{ origin + c.webhookPath }}</span></p> }
      <p class="muted small">Segredos são cifrados no servidor (AES-256-GCM) e nunca voltam para o navegador.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .full { width: 100%; }
    .small { font-size: 12px; }
    .toggles { display: flex; flex-direction: column; gap: 8px; margin: 8px 0 14px; }
  `,
})
export class ConnectorDialogComponent {
  readonly c = inject<Connector>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConnectorDialogComponent>);
  private readonly service = inject(IntegrationsService);
  readonly fields = FIELDS[this.c.provider] ?? [];
  readonly origin = location.origin;
  readonly saving = signal(false);
  enabled = this.c.enabled || this.c.state === 'NAO_CONFIGURADO';
  autoSync = this.c.autoSync;
  sandbox = this.c.config['sandbox'] === 'true';
  config: Record<string, string> = { ...this.c.config };
  secrets: Record<string, string> = {};

  save(): void {
    const config = { ...this.config };
    if (this.c.provider === 'ZAPSIGN') config['sandbox'] = String(this.sandbox);
    this.saving.set(true);
    this.service.save(this.c.provider, { enabled: this.enabled, autoSync: this.autoSync, config, secrets: this.secrets })
      .subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}
