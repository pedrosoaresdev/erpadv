import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { AuditLog, AuditVerifyResult, CursorPage, EmployeeOption } from '../../core/models/models';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';

const ENTITY_LABELS: Record<string, string> = {
  client: 'Cliente', case: 'Processo', user: 'Usuário', office: 'Escritório', audit: 'Auditoria', deadline: 'Prazo', appointment: 'Compromisso',
  finance: 'Lançamento', contract: 'Contrato', document: 'Documento', legal_document: 'Documento do editor', lead: 'Oportunidade', role: 'Cargo',
  security: 'Política de segurança', session: 'Sessão', backup: 'Backup', integration: 'Integração', publication: 'Publicação',
  communication: 'Comunicação', report: 'Relatório',
};

const MODULES = ['Clientes', 'Processos', 'Prazos', 'Agenda', 'Financeiro', 'Contratos', 'Documentos', 'Editor Jurídico', 'CRM', 'Quadros',
  'Equipe e permissões', 'Segurança', 'Integrações', 'Comunicação', 'Relatórios', 'Auditoria', 'LGPD', 'IA Jurídica', 'Automação', 'Sistema'];

const ORIGIN_LABELS: Record<string, string> = { WEB: 'Navegador', CELULAR: 'Celular', WEBHOOK: 'Webhook do provedor', SISTEMA: 'Rotina automática', API: 'API' };

/** Nomes amigáveis dos campos mais comuns nas alterações (os demais aparecem com o nome técnico). */
const FIELD_LABELS: Record<string, string> = {
  name: 'Nome', status: 'Situação', email: 'E-mail', phone: 'Telefone', whatsapp: 'WhatsApp', documentMasked: 'CPF/CNPJ', documentEnc: 'CPF/CNPJ',
  address: 'Endereço', notes: 'Observações', responsibleUid: 'Responsável (ID)', responsibleName: 'Responsável', cnjNumber: 'Número CNJ',
  court: 'Tribunal', courtDivision: 'Vara', district: 'Comarca', area: 'Área', subject: 'Assunto', proceduralClass: 'Classe', opposingParty: 'Parte contrária',
  claimValueCents: 'Valor da causa (centavos)', description: 'Descrição', dueDate: 'Vencimento', startDate: 'Início', days: 'Dias', priority: 'Prioridade',
  amountCents: 'Valor (centavos)', category: 'Categoria', paidDate: 'Data do pagamento', title: 'Título', type: 'Tipo', start: 'Início', end: 'Fim',
  location: 'Local', role: 'Papel', customRoleName: 'Cargo', permissionsGranted: 'Permissões concedidas', permissionsRevoked: 'Permissões retiradas',
  permissions: 'Permissões', displayName: 'Nome', confidential: 'Sigiloso', restricted: 'Restrito', mfaRequirement: 'Exigência de 2FA',
  sessionMaxHours: 'Duração máxima da sessão (h)', idleMinutes: 'Inatividade (min)', requireJustificationOnDelete: 'Justificativa em exclusões',
};

/** Detalhes de um registro: antes × depois, justificativa, origem e integridade. */
@Component({
  selector: 'app-audit-detail',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>fact_check</mat-icon>Registro nº {{ a.seq }}</h2>
    <mat-dialog-content>
      <dl class="kv">
        <dt>Data e hora</dt><dd>{{ a.at | brDate: true }}</dd>
        <dt>Usuário</dt><dd>{{ a.actorName || a.actorEmail || a.actorUid }}@if (a.actorEmail && a.actorName) { <span class="muted"> · {{ a.actorEmail }}</span> }</dd>
        <dt>Ação</dt><dd><span class="badge">{{ a.action }}</span> {{ a.summary }}</dd>
        <dt>Módulo</dt><dd>{{ a.module || '—' }}</dd>
        <dt>Registro afetado</dt><dd>{{ entity(a.entityType) }}{{ a.entityId ? ' · ' + a.entityId : '' }}</dd>
        <dt>Origem</dt><dd>{{ origin(a.origin) }}{{ a.ip ? ' · IP ' + a.ip : '' }}</dd>
        @if (a.justification) { <dt>Justificativa</dt><dd class="just">{{ a.justification }}</dd> }
      </dl>
      @if (a.changes?.length) {
        <h4>Alterações</h4>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Campo</th><th>Antes</th><th>Depois</th></tr></thead>
            <tbody>
              @for (c of a.changes; track c.field) {
                <tr><td>{{ field(c.field) }}</td><td class="before">{{ c.before || '—' }}</td><td class="after">{{ c.after || '—' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
      @if (detailKeys().length) {
        <h4>Detalhes</h4>
        <dl class="kv">@for (k of detailKeys(); track k) { <dt>{{ k }}</dt><dd>{{ a.details[k] }}</dd> }</dl>
      }
      <p class="mono muted small">hash {{ a.hash }}<br />anterior {{ a.prevHash }}</p>
      @if (a.userAgent) { <p class="muted small">{{ a.userAgent }}</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Fechar</button></mat-dialog-actions>
  `,
  styles: `
    .kv { display: grid; grid-template-columns: 150px minmax(0, 1fr); gap: 6px 12px; margin: 0 0 12px; font-size: 14px; }
    .kv dt { color: var(--app-text-muted); }
    .kv dd { margin: 0; word-break: break-word; }
    .just { white-space: pre-wrap; }
    h4 { margin: 12px 0 6px; }
    .before { color: var(--app-danger); text-decoration: line-through; text-decoration-color: rgba(180, 35, 24, .35); word-break: break-word; }
    .after { color: var(--app-success); word-break: break-word; }
    .small { font-size: 11px; word-break: break-all; }
    @media (max-width: 600px) { .kv { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class AuditDetailComponent {
  readonly a = inject<AuditLog>(MAT_DIALOG_DATA);
  detailKeys(): string[] {
    return Object.keys(this.a.details ?? {});
  }
  entity(t: string): string {
    return ENTITY_LABELS[t] ?? t;
  }
  origin(o?: string | null): string {
    return o ? ORIGIN_LABELS[o] ?? o : '—';
  }
  field(f: string): string {
    return FIELD_LABELS[f] ?? f;
  }
}

/** Trilha de auditoria imutável (somente leitura) com filtros, antes/depois, exportação e verificação da cadeia de hash. */
@Component({
  selector: 'app-audit-log',
  imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatMenuModule, MatSelectModule, MatProgressBarModule,
    MatTooltipModule, MatDialogModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Segurança e LGPD" title="Auditoria" subtitle="Registro imutável de quem acessou, alterou ou excluiu informações, com os valores anteriores e posteriores.">
        <button mat-stroked-button [matMenuTriggerFor]="exp"><mat-icon>download</mat-icon>Exportar</button>
        <mat-menu #exp="matMenu">
          <button mat-menu-item (click)="export('pdf')"><mat-icon>picture_as_pdf</mat-icon>PDF</button>
          <button mat-menu-item (click)="export('xlsx')"><mat-icon>table_view</mat-icon>Excel</button>
        </mat-menu>
        <button mat-stroked-button (click)="verify()" [disabled]="verifying()"><mat-icon>verified</mat-icon>{{ verifying() ? 'Verificando…' : 'Verificar integridade' }}</button>
      </app-page-header>

      @if (verifyResult(); as v) {
        <div class="verify card" [class.ok]="v.valid" [class.bad]="!v.valid">
          <mat-icon>{{ v.valid ? 'verified_user' : 'gpp_bad' }}</mat-icon>
          <div>
            <strong>{{ v.valid ? 'Cadeia íntegra' : 'Inconsistência detectada' }}</strong>
            <div>{{ v.message }} — {{ v.checked }} registros verificados{{ v.brokenAtSeq ? ' · registro nº ' + v.brokenAtSeq : '' }}.</div>
          </div>
        </div>
      }

      <div class="card">
        <form class="filters" [formGroup]="filters" (ngSubmit)="load(false)">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Módulo</mat-label>
            <mat-select formControlName="module" (selectionChange)="load(false)">
              <mat-option value="">Todos</mat-option>
              @for (m of modules; track m) { <mat-option [value]="m">{{ m }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Usuário</mat-label>
            <mat-select formControlName="actorUid" (selectionChange)="load(false)">
              <mat-option value="">Todos</mat-option>
              <mat-option value="system">Sistema (rotinas)</mat-option>
              @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Ação (código)</mat-label>
            <input matInput formControlName="action" placeholder="ex.: CLIENT_UPDATED" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="date">
            <mat-label>De</mat-label>
            <input matInput type="date" formControlName="from" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="date">
            <mat-label>Até</mat-label>
            <input matInput type="date" formControlName="to" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-label>ID do registro</mat-label>
            <input matInput formControlName="entityId" />
          </mat-form-field>
          <button mat-flat-button type="submit"><mat-icon>filter_alt</mat-icon>Filtrar</button>
          <button mat-button type="button" (click)="clear()">Limpar</button>
        </form>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && items().length === 0) {
          <app-empty-state icon="history" title="Nenhum registro encontrado" />
        } @else {
          <div class="table-wrap">
            <table class="data stack">
              <thead><tr><th>#</th><th>Data</th><th>Usuário</th><th>Módulo</th><th>Ação</th><th>Descrição</th><th>Origem</th><th></th></tr></thead>
              <tbody>
                @for (a of items(); track a.id) {
                  <tr class="clickable" (click)="open(a)">
                    <td class="mono muted" data-label="#">{{ a.seq }}</td>
                    <td class="nowrap" data-label="Data">{{ a.at | brDate: true }}</td>
                    <td data-label="Usuário">{{ a.actorName || a.actorEmail || a.actorUid }}</td>
                    <td data-label="Módulo">{{ a.module || '—' }}</td>
                    <td data-label="Ação"><span class="badge">{{ a.action }}</span></td>
                    <td data-label="Descrição">{{ a.summary }}
                      @if (a.changes?.length) { <div class="muted small">{{ a.changes!.length }} campo(s) alterado(s)</div> }
                      @if (a.justification) { <div class="small just" [matTooltip]="a.justification"><mat-icon class="mi">comment</mat-icon>{{ a.justification }}</div> }
                    </td>
                    <td data-label="Origem" class="muted">{{ originLabel(a.origin) }}{{ a.ip ? ' · ' + a.ip : '' }}</td>
                    <td><mat-icon class="muted">chevron_right</mat-icon></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 12px; padding: 16px; align-items: center; border-bottom: 1px solid var(--app-border); }
    .filters mat-form-field { width: 200px; }
    .filters .date { width: 160px; }
    .filters .grow { flex: 1 1 200px; }
    .verify { display: flex; gap: 12px; align-items: center; padding: 14px 18px; margin-bottom: 16px; }
    .verify.ok { background: var(--app-success-bg); color: var(--app-success); border-color: transparent; }
    .verify.bad { background: var(--app-danger-bg); color: var(--app-danger); border-color: transparent; }
    .small { font-size: 12px; }
    .just { color: var(--app-text-muted); max-width: 360px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mi { font-size: 14px; width: 14px; height: 14px; vertical-align: -2px; margin-right: 4px; }
    .nowrap { white-space: nowrap; }
    .clickable { cursor: pointer; }
    .more { display: flex; justify-content: center; padding: 16px; }
    @media (max-width: 700px) { .filters mat-form-field, .filters .date { width: 100%; } }
  `,
})
export class AuditLogComponent {
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  readonly modules = MODULES;
  readonly staff = toSignal(this.api.get<EmployeeOption[]>('/employees/options', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly items = signal<AuditLog[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly verifying = signal(false);
  readonly verifyResult = signal<AuditVerifyResult | null>(null);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  readonly filters = inject(FormBuilder).nonNullable.group({
    module: [this.params.get('modulo') ?? ''],
    actorUid: [this.params.get('ator') ?? ''],
    action: [''],
    from: [''],
    to: [''],
    entityId: [this.params.get('registro') ?? ''],
  });

  constructor() {
    this.load(false);
  }

  originLabel(o?: string | null): string {
    return o ? ORIGIN_LABELS[o] ?? o : '—';
  }

  private query(): Record<string, string | null> {
    const f = this.filters.getRawValue();
    return { module: f.module || null, actorUid: f.actorUid || null, action: f.action.trim() || null, from: f.from || null, to: f.to || null,
      entityId: f.entityId.trim() || null };
  }

  clear(): void {
    this.filters.reset();
    this.load(false);
  }

  load(more: boolean): void {
    this.loading.set(true);
    this.api.get<CursorPage<AuditLog>>('/audit', { ...this.query(), cursor: more ? this.cursor() : null, size: 50 }).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  open(a: AuditLog): void {
    this.dialog.open(AuditDetailComponent, { data: a, width: '820px', maxWidth: '96vw' });
  }

  export(format: 'pdf' | 'xlsx'): void {
    this.api.getBlob('/audit/export', { ...this.query(), format }).subscribe((r) => saveBlob(r, `auditoria.${format}`));
  }

  verify(): void {
    this.verifying.set(true);
    this.api.get<AuditVerifyResult>('/audit/verify').subscribe({
      next: (r) => {
        this.verifyResult.set(r);
        this.verifying.set(false);
        this.load(false);
      },
      error: () => this.verifying.set(false),
    });
  }
}
