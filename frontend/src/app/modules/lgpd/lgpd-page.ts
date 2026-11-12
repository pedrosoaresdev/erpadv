import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import {
  ActivityDialogComponent, ConsentDialogComponent, IncidentDialogComponent, PolicyDialogComponent, RequestDetailComponent, RequestDialogComponent,
  ShareDialogComponent,
} from './lgpd-dialogs';
import {
  Consent, DataRequest, DataShare, Incident, LgpdOptions, LgpdOverview, PrivacyPolicy, ProcessingActivity, REQUEST_STATUS, RequestStatus,
  RetentionItem, RetentionRule,
} from './lgpd.models';

const TABS = ['painel', 'solicitacoes', 'consentimentos', 'tratamento', 'incidentes', 'compartilhamentos', 'retencao', 'politica'];

/** Central LGPD do escritório. */
@Component({
  selector: 'app-lgpd-page',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule,
    MatTabsModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, StatCardComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Segurança e LGPD" title="LGPD"
        subtitle="Solicitações de titulares, consentimentos, registro das operações de tratamento, incidentes, compartilhamentos, retenção e política de privacidade." />

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="onTab($event)">
        <!-- ============================================================ painel -->
        <mat-tab label="Painel">
          @if (overview(); as o) {
            <div class="stats">
              <app-stat-card label="Solicitações em aberto" [value]="o.openRequests" icon="assignment_ind"
                [hint]="o.nextRequestDue ? 'próximo prazo ' + (o.nextRequestDue | brDate) : 'nenhuma pendente'" />
              <app-stat-card label="Solicitações em atraso" [value]="o.overdueRequests" icon="schedule" [tone]="o.overdueRequests ? 'danger' : ''" />
              <app-stat-card label="Incidentes abertos" [value]="o.openIncidents" icon="report" [tone]="o.anpdPending ? 'danger' : ''"
                [hint]="o.anpdPending ? o.anpdPending + ' com comunicação à ANPD pendente' : ''" />
              <app-stat-card label="Consentimentos vigentes" [value]="o.activeConsents" icon="fact_check" />
              <app-stat-card label="Atividades de tratamento" [value]="o.activities" icon="account_tree"
                [hint]="o.pendingSuggestions ? o.pendingSuggestions + ' sugestão(ões) a revisar' : ''" />
              <app-stat-card label="Política de privacidade" [value]="o.publishedPolicyVersion ? 'v' + o.publishedPolicyVersion : '—'" icon="policy"
                [hint]="o.publishedPolicyVersion ? 'publicada' : 'ainda não publicada'" />
            </div>
            <section class="card pad checklist">
              <h3><mat-icon>checklist</mat-icon>Próximos passos</h3>
              <ul>
                <li [class.done]="o.activities > 0 && !o.pendingSuggestions">Registrar e revisar as atividades de tratamento com finalidade e base legal (art. 37)</li>
                <li [class.done]="!!o.publishedPolicyVersion">Publicar a política de privacidade (link público para clientes)</li>
                <li [class.done]="!o.overdueRequests">Responder às solicitações de titulares em até 15 dias</li>
                <li [class.done]="!o.anpdPending">Comunicar incidentes relevantes à ANPD em até 3 dias úteis</li>
                <li>Definir prazos de guarda por categoria de documento e revisar os vencidos</li>
              </ul>
            </section>
          }
        </mat-tab>

        <!-- ============================================================ solicitações -->
        <mat-tab label="Solicitações">
          <section class="card">
            <div class="bar">
              <mat-button-toggle-group [(ngModel)]="requestFilter" (change)="loadRequests()" aria-label="Situação">
                <mat-button-toggle value="">Todas</mat-button-toggle>
                <mat-button-toggle value="RECEBIDA">Recebidas</mat-button-toggle>
                <mat-button-toggle value="EM_ANALISE">Em análise</mat-button-toggle>
                <mat-button-toggle value="ATENDIDA">Atendidas</mat-button-toggle>
              </mat-button-toggle-group>
              <span class="spacer"></span>
              <button mat-flat-button (click)="newRequest()"><mat-icon>add</mat-icon>Nova solicitação</button>
            </div>
            @if (requests().length === 0) { <app-empty-state icon="assignment_ind" title="Nenhuma solicitação" text="Registre aqui os pedidos dos titulares (acesso, correção, eliminação…)." /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Protocolo</th><th>Titular</th><th>Pedido</th><th>Recebida</th><th>Prazo</th><th>Situação</th></tr></thead>
                <tbody>
                  @for (r of requests(); track r.id) {
                    <tr class="click" (click)="openRequest(r)">
                      <td data-label="Protocolo" class="mono">{{ r.protocol }}</td>
                      <td data-label="Titular">{{ r.requesterName }}@if (r.clientName && r.clientName !== r.requesterName) { <div class="muted small">{{ r.clientName }}</div> }</td>
                      <td data-label="Pedido">{{ label(options()?.requestTypes, r.type) }}</td>
                      <td data-label="Recebida">{{ r.receivedDate | brDate }}</td>
                      <td data-label="Prazo"><span [class.danger-text]="isOpen(r.status) && r.dueDate < today">{{ r.dueDate | brDate }}</span></td>
                      <td data-label="Situação"><span class="badge" [class.success]="r.status === 'ATENDIDA'">{{ requestStatus[r.status] }}</span></td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ consentimentos -->
        <mat-tab label="Consentimentos">
          <section class="card">
            <div class="bar"><span class="muted small">Registre o consentimento quando ele for a base legal (ex.: comunicações de marketing, uso de imagem).</span>
              <span class="spacer"></span><button mat-flat-button (click)="newConsent()"><mat-icon>add</mat-icon>Registrar consentimento</button></div>
            @if (consents().length === 0) { <app-empty-state icon="fact_check" title="Nenhum consentimento registrado" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Titular</th><th>Finalidade</th><th>Obtido</th><th>Situação</th><th></th></tr></thead>
                <tbody>
                  @for (c of consents(); track c.id) {
                    <tr>
                      <td data-label="Titular">{{ c.clientName }}</td>
                      <td data-label="Finalidade">{{ c.purpose }}<div class="muted small">{{ c.channel }}{{ c.evidence ? ' · ' + c.evidence : '' }}</div></td>
                      <td data-label="Obtido">{{ c.grantedDate | brDate }}{{ c.expiresDate ? ' até ' + (c.expiresDate | brDate) : '' }}</td>
                      <td data-label="Situação">@if (c.revoked) { <span class="badge">revogado em {{ c.revokedDate | brDate }}</span> } @else { <span class="badge success">vigente</span> }</td>
                      <td>@if (!c.revoked) { <button mat-button (click)="revokeConsent(c)">Revogar</button> }</td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ registro de tratamento -->
        <mat-tab label="Registro de tratamento">
          <section class="card">
            <div class="bar"><span class="muted small">Registro das operações de tratamento (art. 37): finalidade, base legal, dados, titulares, compartilhamento e retenção.</span>
              <span class="spacer"></span>
              @if (activities().length === 0) { <button mat-stroked-button (click)="seed()"><mat-icon>auto_fix_high</mat-icon>Carregar sugestões para advocacia</button> }
              <button mat-flat-button (click)="editActivity(null)"><mat-icon>add</mat-icon>Nova atividade</button></div>
            @if (activities().length === 0) { <app-empty-state icon="account_tree" title="Registro vazio" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Atividade</th><th>Base legal</th><th>Dados</th><th>Retenção</th><th></th></tr></thead>
                <tbody>
                  @for (a of activities(); track a.id) {
                    <tr class="click" (click)="editActivity(a)">
                      <td data-label="Atividade"><strong>{{ a.name }}</strong>@if (a.suggestion) { <span class="badge warn">sugestão — revisar</span> }
                        @if (!a.active) { <span class="badge">inativa</span> }<div class="muted small">{{ a.purpose }}</div></td>
                      <td data-label="Base legal">{{ label(options()?.legalBases, a.legalBasis) }}@if (a.sensitiveData) { <div class="small warn">dados sensíveis</div> }</td>
                      <td data-label="Dados" class="small">{{ a.dataCategories.join(', ') }}</td>
                      <td data-label="Retenção" class="small">{{ a.retention || '—' }}</td>
                      <td><mat-icon class="muted">chevron_right</mat-icon></td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ incidentes -->
        <mat-tab label="Incidentes">
          <section class="card">
            <div class="bar"><span class="muted small">Incidentes de segurança com dados pessoais (art. 48). Comunicação à ANPD e aos titulares em até 3 dias úteis quando relevante.</span>
              <span class="spacer"></span><button mat-flat-button (click)="openIncident(null)"><mat-icon>add</mat-icon>Registrar incidente</button></div>
            @if (incidents().length === 0) { <app-empty-state icon="verified_user" title="Nenhum incidente registrado" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Incidente</th><th>Descoberto</th><th>Risco</th><th>ANPD</th><th>Situação</th></tr></thead>
                <tbody>
                  @for (i of incidents(); track i.id) {
                    <tr class="click" (click)="openIncident(i)">
                      <td data-label="Incidente"><strong>{{ i.title }}</strong><div class="muted small">{{ i.affectedSubjects }} titular(es)</div></td>
                      <td data-label="Descoberto">{{ i.discoveredAt | brDate: true }}</td>
                      <td data-label="Risco"><span class="badge" [class.danger]="i.risk === 'ALTO'" [class.warn]="i.risk === 'MEDIO'">{{ i.risk.toLowerCase() }}</span></td>
                      <td data-label="ANPD">@if (!i.anpdNotificationRequired) { — } @else if (i.anpdNotifiedDate) { comunicada {{ i.anpdNotifiedDate | brDate }} }
                        @else { <span class="danger-text">até {{ i.anpdDeadline | brDate }}</span> }</td>
                      <td data-label="Situação">{{ i.status === 'EM_TRATAMENTO' ? 'em tratamento' : i.status.toLowerCase() }}</td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ compartilhamentos -->
        <mat-tab label="Compartilhamentos">
          <section class="card">
            <div class="bar"><span class="muted small">Controle de dados pessoais entregues a terceiros (peritos, contabilidade, correspondentes, autoridades).</span>
              <span class="spacer"></span><button mat-flat-button (click)="newShare()"><mat-icon>add</mat-icon>Registrar compartilhamento</button></div>
            @if (shares().length === 0) { <app-empty-state icon="share" title="Nenhum compartilhamento registrado" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Data</th><th>Destinatário</th><th>Titular</th><th>Dados</th><th>Base legal</th></tr></thead>
                <tbody>
                  @for (s of shares(); track s.id) {
                    <tr>
                      <td data-label="Data">{{ s.sharedDate | brDate }}</td>
                      <td data-label="Destinatário">{{ s.recipient }}<div class="muted small">{{ s.recipientType }}</div></td>
                      <td data-label="Titular">{{ s.clientName || '—' }}</td>
                      <td data-label="Dados">{{ s.dataDescription }}<div class="muted small">{{ s.purpose }}</div></td>
                      <td data-label="Base legal" class="small">{{ label(options()?.legalBases, s.legalBasis) }}</td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ retenção -->
        <mat-tab label="Retenção">
          <section class="card pad">
            <h3><mat-icon>auto_delete</mat-icon>Prazos de guarda por categoria</h3>
            <div class="rule-form">
              <mat-form-field><mat-label>Categoria</mat-label>
                <mat-select [(ngModel)]="rule.category">@for (c of options()?.documentCategories ?? []; track c.value) { <mat-option [value]="c.value">{{ c.label }}</mat-option> }</mat-select></mat-form-field>
              <mat-form-field class="years"><mat-label>Anos (0 = permanente)</mat-label><input matInput type="number" min="0" max="100" [(ngModel)]="rule.years" /></mat-form-field>
              <mat-form-field class="grow"><mat-label>Fundamento</mat-label><input matInput [(ngModel)]="rule.legalReference" maxlength="300" /></mat-form-field>
              <button mat-flat-button (click)="saveRule()" [disabled]="!rule.category">Salvar regra</button>
            </div>
            <ul class="rules">
              @for (r of rules(); track r.id) {
                <li><strong>{{ label(options()?.documentCategories, r.category) }}</strong>: {{ r.years ? r.years + ' ano(s)' : 'guarda permanente' }}
                  <span class="muted small">{{ r.legalReference }}</span>
                  <button mat-icon-button (click)="deleteRule(r)" aria-label="Remover regra"><mat-icon>close</mat-icon></button></li>
              } @empty { <li class="muted">Nenhuma regra definida.</li> }
            </ul>
          </section>
          <section class="card">
            <div class="bar"><h3><mat-icon>event_busy</mat-icon>Documentos com prazo de guarda vencido</h3><span class="spacer"></span>
              <button mat-button (click)="loadRetention()"><mat-icon>refresh</mat-icon>Atualizar</button>
              <button mat-flat-button (click)="trashExpired()" [disabled]="!selectedCount()"><mat-icon>delete</mat-icon>Enviar {{ selectedCount() }} à lixeira</button></div>
            @if (expired().length === 0) { <app-empty-state icon="task_alt" title="Nenhum documento com prazo vencido" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th></th><th>Documento</th><th>Categoria</th><th>Cliente/processo</th><th>Vencido em</th></tr></thead>
                <tbody>
                  @for (d of expired(); track d.documentId) {
                    <tr>
                      <td><mat-checkbox [(ngModel)]="selected[d.documentId]" [attr.aria-label]="d.title" /></td>
                      <td data-label="Documento">{{ d.title }}</td>
                      <td data-label="Categoria">{{ label(options()?.documentCategories, d.category) }}</td>
                      <td data-label="Cliente/processo">{{ d.clientName || '—' }}{{ d.caseNumber ? ' · ' + d.caseNumber : '' }}</td>
                      <td data-label="Vencido em">{{ d.expiredOn | brDate }}</td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
        </mat-tab>

        <!-- ============================================================ política -->
        <mat-tab label="Política de privacidade">
          <section class="card">
            <div class="bar">
              @if (publicUrl()) { <span class="small">Link público: <a [href]="publicUrl()" target="_blank" rel="noopener">{{ publicUrl() }}</a></span> }
              <span class="spacer"></span>
              @if (policies().length === 0) { <button mat-stroked-button (click)="newPolicy(true)"><mat-icon>auto_fix_high</mat-icon>Começar pelo modelo</button> }
              <button mat-flat-button (click)="newPolicy(false)"><mat-icon>add</mat-icon>Nova versão</button>
            </div>
            @if (policies().length === 0) { <app-empty-state icon="policy" title="Nenhuma política cadastrada" /> } @else {
              <div class="table-wrap"><table class="data stack">
                <thead><tr><th>Versão</th><th>Título</th><th>Situação</th><th></th></tr></thead>
                <tbody>
                  @for (p of policies(); track p.id) {
                    <tr>
                      <td data-label="Versão">v{{ p.version }}</td>
                      <td data-label="Título">{{ p.title }}</td>
                      <td data-label="Situação">@if (p.published) { <span class="badge success">vigente desde {{ p.publishedAt | brDate }}</span> }
                        @else if (p.publishedAt) { <span class="badge">substituída</span> } @else { <span class="badge warn">rascunho</span> }</td>
                      <td class="actions">
                        <button mat-button (click)="preview.set(p)"><mat-icon>visibility</mat-icon>Ver</button>
                        @if (!p.publishedAt) {
                          <button mat-button (click)="editPolicy(p)"><mat-icon>edit</mat-icon>Editar</button>
                          <button mat-flat-button (click)="publish(p)"><mat-icon>publish</mat-icon>Publicar</button>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table></div>
            }
          </section>
          @if (preview(); as p) {
            <section class="card pad policy-view">
              <div class="bar0"><h3>{{ p.title }} · v{{ p.version }}</h3><span class="spacer"></span>
                <button mat-icon-button (click)="preview.set(null)" aria-label="Fechar"><mat-icon>close</mat-icon></button></div>
              <div class="doc" [innerHTML]="safe(p.content)"></div>
            </section>
          }
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; padding-top: 16px; }
    .card { margin-top: 16px; }
    .pad { padding: 16px 20px; }
    .bar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .bar h3, .pad h3 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 16px; }
    .bar0 { display: flex; align-items: center; }
    .checklist ul { margin: 8px 0 0; padding-left: 20px; line-height: 1.9; }
    .checklist li.done { color: var(--app-success); text-decoration: line-through; text-decoration-color: rgba(46, 125, 50, .4); }
    .click { cursor: pointer; }
    .small { font-size: 12px; }
    .warn { color: var(--app-warning, #b26a00); }
    .badge.warn { background: var(--app-warning-bg, #fff4e5); color: var(--app-warning, #b26a00); }
    .badge.success { background: var(--app-success-bg); color: var(--app-success); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
    .danger-text { color: var(--app-danger); font-weight: 600; }
    .rule-form { display: flex; gap: 12px; flex-wrap: wrap; align-items: baseline; margin-top: 12px; }
    .rule-form .years { width: 160px; }
    .rule-form .grow { flex: 1 1 220px; }
    .rules { list-style: none; padding: 0; margin: 0; }
    .rules li { display: flex; gap: 8px; align-items: center; padding: 4px 0; flex-wrap: wrap; }
    .actions { white-space: nowrap; text-align: right; }
    .doc { line-height: 1.6; max-width: 820px; }
    @media (max-width: 900px) { .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 560px) { .stats { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class LgpdPageComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  readonly today = new Date().toISOString().slice(0, 10);
  readonly requestStatus = REQUEST_STATUS;
  readonly tab = signal(Math.max(0, TABS.indexOf(inject(ActivatedRoute).snapshot.queryParamMap.get('aba') ?? 'painel')));
  readonly options = signal<LgpdOptions | null>(null);
  readonly overview = signal<LgpdOverview | null>(null);
  readonly requests = signal<DataRequest[]>([]);
  readonly consents = signal<Consent[]>([]);
  readonly activities = signal<ProcessingActivity[]>([]);
  readonly incidents = signal<Incident[]>([]);
  readonly shares = signal<DataShare[]>([]);
  readonly rules = signal<RetentionRule[]>([]);
  readonly expired = signal<RetentionItem[]>([]);
  readonly policies = signal<PrivacyPolicy[]>([]);
  readonly preview = signal<PrivacyPolicy | null>(null);
  requestFilter: RequestStatus | '' = '';
  rule = { category: '', years: 5, legalReference: '' };
  selected: Record<string, boolean> = {};

  constructor() {
    this.api.get<LgpdOptions>('/lgpd/options').subscribe((o) => this.options.set(o));
    this.reloadAll();
  }

  reloadAll(): void {
    this.api.get<LgpdOverview>('/lgpd/overview').subscribe((o) => this.overview.set(o));
    this.loadRequests();
    this.api.get<Consent[]>('/lgpd/consents').subscribe((l) => this.consents.set(l));
    this.api.get<ProcessingActivity[]>('/lgpd/activities').subscribe((l) => this.activities.set(l));
    this.api.get<Incident[]>('/lgpd/incidents').subscribe((l) => this.incidents.set(l));
    this.api.get<DataShare[]>('/lgpd/shares').subscribe((l) => this.shares.set(l));
    this.api.get<RetentionRule[]>('/lgpd/retention/rules').subscribe((l) => this.rules.set(l));
    this.api.get<PrivacyPolicy[]>('/lgpd/policies').subscribe((l) => this.policies.set(l));
    this.loadRetention();
  }

  onTab(i: number): void {
    this.tab.set(i);
    void this.router.navigate([], { queryParams: { aba: TABS[i] }, replaceUrl: true });
  }

  label(list: { value: string; label: string }[] | undefined, v: string): string {
    return list?.find((o) => o.value === v)?.label ?? v;
  }

  isOpen(s: RequestStatus): boolean {
    return s === 'RECEBIDA' || s === 'EM_ANALISE';
  }

  safe(html: string): SafeHtml {
    // conteúdo já saneado no servidor (jsoup, lista de permissões)
    return this.sanitizer.bypassSecurityTrustHtml(html);
  }

  publicUrl(): string | null {
    const officeId = this.auth.me()?.officeId;
    return officeId && this.policies().some((p) => p.published) ? `${location.origin}/privacidade/${officeId}` : null;
  }

  selectedCount(): number {
    return Object.values(this.selected).filter(Boolean).length;
  }

  loadRequests(): void {
    this.api.get<DataRequest[]>('/lgpd/requests', { status: this.requestFilter || null }).subscribe((l) => this.requests.set(l));
  }

  loadRetention(): void {
    this.selected = {};
    this.api.get<RetentionItem[]>('/lgpd/retention/report').subscribe((l) => this.expired.set(l));
  }

  newRequest(): void {
    this.dialog.open(RequestDialogComponent, { data: { options: this.options() }, width: '720px', maxWidth: '96vw' }).afterClosed().subscribe((r) => {
      if (r) { this.toast.success('Solicitação registrada.'); this.reloadAll(); }
    });
  }

  openRequest(r: DataRequest): void {
    this.dialog.open(RequestDetailComponent, { data: { request: r, options: this.options() }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe(() => this.reloadAll());
  }

  newConsent(): void {
    this.dialog.open(ConsentDialogComponent, { width: '640px', maxWidth: '96vw' }).afterClosed().subscribe((c) => {
      if (c) { this.toast.success('Consentimento registrado.'); this.reloadAll(); }
    });
  }

  revokeConsent(c: Consent): void {
    const data: ConfirmData = { title: 'Revogar consentimento', message: `Registrar a revogação do consentimento de ${c.clientName} para "${c.purpose}"?`,
      reasonLabel: 'Observação (como o titular revogou)', confirmLabel: 'Revogar', danger: true };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((note) => {
      if (note === undefined || note === false) return;
      this.api.post<Consent>(`/lgpd/consents/${c.id}/revoke`, { note: typeof note === 'string' ? note : null }).subscribe(() => {
        this.toast.success('Revogação registrada.');
        this.reloadAll();
      });
    });
  }

  seed(): void {
    this.api.post<ProcessingActivity[]>('/lgpd/activities/suggestions', {}).subscribe((l) => {
      this.toast.success(`${l.length} sugestões carregadas — revise cada uma.`);
      this.reloadAll();
    });
  }

  editActivity(a: ProcessingActivity | null): void {
    this.dialog.open(ActivityDialogComponent, { data: { activity: a, options: this.options() }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe((x) => { if (x) this.reloadAll(); });
  }

  openIncident(i: Incident | null): void {
    this.dialog.open(IncidentDialogComponent, { data: { incident: i }, width: '780px', maxWidth: '96vw' }).afterClosed().subscribe((x) => {
      if (x) { this.toast.success(i ? 'Incidente atualizado.' : 'Incidente registrado.'); this.reloadAll(); }
    });
  }

  newShare(): void {
    this.dialog.open(ShareDialogComponent, { data: { options: this.options() }, width: '700px', maxWidth: '96vw' }).afterClosed().subscribe((x) => {
      if (x) { this.toast.success('Compartilhamento registrado.'); this.reloadAll(); }
    });
  }

  saveRule(): void {
    this.api.put<RetentionRule>('/lgpd/retention/rules', { category: this.rule.category, years: Number(this.rule.years) || 0,
      legalReference: this.rule.legalReference || null }).subscribe(() => {
      this.toast.success('Regra de retenção salva.');
      this.rule = { category: '', years: 5, legalReference: '' };
      this.reloadAll();
    });
  }

  deleteRule(r: RetentionRule): void {
    this.api.delete<void>(`/lgpd/retention/rules/${r.category}`).subscribe(() => this.reloadAll());
  }

  trashExpired(): void {
    const ids = Object.entries(this.selected).filter(([, v]) => v).map(([k]) => k);
    const data: ConfirmData = { title: 'Aplicar prazo de guarda', danger: true, confirmLabel: 'Enviar à lixeira',
      message: `Enviar ${ids.length} documento(s) com prazo vencido à lixeira? A eliminação definitiva continua dependendo da lixeira da central documental.` };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.api.post<{ trashed: number }>('/lgpd/retention/trash', { ids }).subscribe((r) => {
        this.toast.success(`${r.trashed} documento(s) enviado(s) à lixeira.`);
        this.loadRetention();
      });
    });
  }

  newPolicy(fromTemplate: boolean): void {
    const open = (template: { title: string; content: string } | null) =>
      this.dialog.open(PolicyDialogComponent, { data: { policy: null, template }, width: '900px', maxWidth: '96vw' })
        .afterClosed().subscribe((p) => { if (p) { this.toast.success('Rascunho salvo.'); this.reloadAll(); } });
    if (fromTemplate) {
      this.api.get<{ title: string; content: string }>('/lgpd/policies/template').subscribe((t) => open(t));
    } else {
      const last = this.policies()[0];
      open(last ? { title: last.title, content: last.content } : null);
    }
  }

  editPolicy(p: PrivacyPolicy): void {
    this.dialog.open(PolicyDialogComponent, { data: { policy: p, template: null }, width: '900px', maxWidth: '96vw' })
      .afterClosed().subscribe((x) => { if (x) this.reloadAll(); });
  }

  publish(p: PrivacyPolicy): void {
    const data: ConfirmData = { title: 'Publicar política', confirmLabel: 'Publicar',
      message: `Publicar a versão ${p.version}? Ela passa a ser exibida no link público e não poderá mais ser editada.` };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (ok) this.api.post<PrivacyPolicy>(`/lgpd/policies/${p.id}/publish`, {}).subscribe(() => { this.toast.success('Política publicada.'); this.reloadAll(); });
    });
  }
}
