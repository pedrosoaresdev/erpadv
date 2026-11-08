import { Component, effect, inject, input, signal } from '@angular/core';
import { FormControl, FormsModule, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { AuditLog } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { LoadingComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { parseMoneyToCents } from '../../shared/validators/br-validators';
import { DocumentsService } from '../documents/documents.service';
import { DocumentViewerDialogComponent } from '../documents/document-dialogs';
import { saveBlob } from '../finance/finance.service';
import { TransactionsTableComponent } from '../finance/transactions-table';
import { ContractFormDialogComponent } from './contract-form-dialog';
import { feeSummary } from './contracts-list';
import { ContractRenewDialogComponent } from './renew-dialog';
import { CONTRACT_STATUS_LABELS, CONTRACT_STATUS_TONE, Contract, ContractTemplate, ContractsService } from './contracts.service';

@Component({
  selector: 'app-contract-detail',
  imports: [RouterLink, FormsModule, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule,
    MatSelectModule, MatTabsModule, MatTooltipModule, LoadingComponent, BrDatePipe, CentsPipe, TransactionsTableComponent],
  template: `
    <div class="page">
      <a routerLink="/contratos" class="back"><mat-icon>arrow_back</mat-icon>Contratos</a>
      @if (!contract()) { <app-loading /> } @else {
        @let c = contract()!;
        <section class="hero card">
          <div class="hero-main">
            <span class="eyebrow">{{ c.typeLabel }}</span>
            <h1><span class="mono">{{ c.number }}</span> · {{ c.title }}</h1>
            <div class="meta">
              <a [routerLink]="['/clientes', c.clientId]">{{ c.clientName }}</a>
              @if (c.caseId) { <span>·</span><a [routerLink]="['/processos', c.caseId]" class="mono">{{ c.caseNumber }}</a> }
              <span class="badge" [class]="tone[c.status]">{{ statusLabel[c.status] }}</span>
              @if (c.signedDocumentId) { <span class="badge info"><mat-icon inline>verified</mat-icon>assinado</span> }
            </div>
          </div>
          @if (auth.has('CONTRACTS_WRITE')) {
            <div class="hero-actions">
              @if (c.status === 'RASCUNHO' || c.status === 'ATIVO') { <button mat-stroked-button (click)="edit()"><mat-icon>edit</mat-icon>Editar</button> }
              @if (c.status === 'RASCUNHO') { <button mat-flat-button (click)="activate()"><mat-icon>play_circle</mat-icon>Ativar e gerar cobranças</button> }
              <button mat-icon-button [matMenuTriggerFor]="more" aria-label="Mais ações"><mat-icon>more_vert</mat-icon></button>
              <mat-menu #more="matMenu" xPosition="before">
                <button mat-menu-item (click)="signedPicker.click()"><mat-icon>upload_file</mat-icon>{{ c.signedDocumentId ? 'Substituir contrato assinado' : 'Anexar contrato assinado' }}</button>
                @if (c.signedDocumentId) { <button mat-menu-item (click)="openSigned()"><mat-icon>visibility</mat-icon>Ver contrato assinado</button> }
                @if (c.status === 'ATIVO') { <button mat-menu-item (click)="renew(c)"><mat-icon>autorenew</mat-icon>Renovar contrato</button> }
                @if (c.status === 'ATIVO') { <button mat-menu-item (click)="close()"><mat-icon>task_alt</mat-icon>Encerrar contrato</button> }
                @if (c.status === 'RASCUNHO' || c.status === 'ATIVO') { <button mat-menu-item class="danger" (click)="cancel()"><mat-icon>block</mat-icon>Cancelar contrato</button> }
              </mat-menu>
              <input #signedPicker type="file" accept="application/pdf,image/png,image/jpeg" hidden (change)="uploadSigned($event)" />
            </div>
          }
        </section>

        <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false" (selectedTabChange)="onTab($event.tab.textLabel)">
          <mat-tab label="Resumo e honorários">
            <div class="grid2">
              <section class="card card-pad">
                <div class="card-title"><mat-icon>info</mat-icon>Dados</div>
                <dl>
                  <dt>Objeto</dt><dd class="pre">{{ c.object }}</dd>
                  <dt>Responsável</dt><dd>{{ c.responsibleName }}</dd>
                  <dt>Vigência</dt><dd>{{ c.startDate | brDate }}{{ c.endDate ? ' a ' + (c.endDate | brDate) : ' (indeterminada)' }}</dd>
                  @if (c.renewals?.length) {
                    <dt>Renovações</dt>
                    <dd>@for (r of c.renewals; track $index) { <div class="small">{{ r.renewedOn | brDate }}: até {{ r.newEndDate | brDate }}{{ r.monthsAdded ? ' · +' + r.monthsAdded + ' mês(es)' : '' }}{{ r.note ? ' · ' + r.note : '' }} · {{ r.byName }}</div> }</dd>
                  }
                  @if (c.activatedAt) { <dt>Ativado em</dt><dd>{{ c.activatedAt | brDate: true }}</dd> }
                  @if (c.cancelReason) { <dt>Cancelamento</dt><dd>{{ c.cancelReason }}</dd> }
                </dl>
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>payments</mat-icon>Honorários — {{ c.feeModelLabel }}</div>
                <p class="fee">{{ fee(c) }}</p>
                @if (c.feeModel === 'ETAPAS') {
                  <ul class="stages">
                    @for (s of c.stages; track s.id) {
                      <li>
                        <div><strong>{{ s.description }}</strong><div class="muted small">{{ s.amountCents | cents }}{{ s.dueDate ? ' · previsão ' + (s.dueDate | brDate) : '' }}</div></div>
                        @if (s.billed) { <span class="badge success">faturada</span> }
                        @else if (c.status === 'ATIVO' && auth.has('CONTRACTS_WRITE')) { <button mat-stroked-button (click)="billStage(s.id)">Faturar etapa</button> }
                        @else { <span class="badge">pendente</span> }
                      </li>
                    }
                  </ul>
                }
                @if (c.successPercentBp) {
                  <div class="success">
                    <div><strong>Êxito: {{ c.successPercentBp / 100 }}%</strong><div class="muted small">sobre {{ c.successBase || 'o proveito econômico' }}</div></div>
                    @if (c.successBilled) { <span class="badge success">faturado</span> }
                    @else if (c.status === 'ATIVO' && auth.has('CONTRACTS_WRITE')) {
                      <div class="success-form">
                        <mat-form-field subscriptSizing="dynamic"><mat-label>Proveito obtido (R$)</mat-label><input matInput [formControl]="obtained" inputmode="decimal" /></mat-form-field>
                        <button mat-flat-button (click)="registerSuccess()">Faturar êxito</button>
                      </div>
                    }
                  </div>
                }
                @if (c.status === 'RASCUNHO') { <p class="muted small">As cobranças serão geradas no financeiro ao ativar o contrato.</p> }
              </section>
            </div>
          </mat-tab>

          <mat-tab label="Minuta">
            <div class="card card-pad tabc">
              <div class="gen">
                <mat-form-field subscriptSizing="dynamic" class="tpl">
                  <mat-label>Modelo</mat-label>
                  <mat-select [(ngModel)]="templateId">@for (t of templates(); track t.id) { <mat-option [value]="t.id">{{ t.name }}</mat-option> }</mat-select>
                </mat-form-field>
                @if (auth.has('CONTRACTS_WRITE')) { <button mat-stroked-button (click)="generate()" [disabled]="!templateId"><mat-icon>auto_fix_high</mat-icon>Gerar minuta</button> }
                <span class="spacer"></span>
                <button mat-stroked-button (click)="export('pdf')" [disabled]="!body.value"><mat-icon>picture_as_pdf</mat-icon>PDF</button>
                <button mat-stroked-button (click)="export('docx')" [disabled]="!body.value"><mat-icon>description</mat-icon>DOCX</button>
                @if (auth.has('CONTRACTS_WRITE')) { <button mat-stroked-button (click)="toDocuments()" [disabled]="!body.value" matTooltip="Salvar PDF no repositório de documentos do cliente"><mat-icon>drive_folder_upload</mat-icon>Arquivar</button> }
              </div>
              @if (missing().length) {
                <div class="notice"><mat-icon>warning_amber</mat-icon>Campos sem dados (revise antes de assinar): {{ missing().join(', ') }}</div>
              }
              <mat-form-field class="full">
                <mat-label>Texto do contrato</mat-label>
                <textarea matInput rows="22" [formControl]="body" class="mono" [readonly]="!auth.has('CONTRACTS_WRITE')"></textarea>
                <mat-hint>Revise o texto: a minuta é uma sugestão e exige revisão do advogado responsável.</mat-hint>
              </mat-form-field>
              @if (auth.has('CONTRACTS_WRITE')) { <div class="save"><button mat-flat-button (click)="saveBody()" [disabled]="!body.dirty"><mat-icon>save</mat-icon>Salvar texto</button></div> }
            </div>
          </mat-tab>

          @if (auth.has('FINANCE_READ')) {
            <mat-tab label="Cobranças">
              <div class="tabc"><app-transactions-table direction="RECEITA" [contractId]="c.id" [refreshKey]="refresh()" /></div>
            </mat-tab>
          }

          <mat-tab label="Histórico">
            <div class="card tabc">
              <ul class="history">
                @for (h of history(); track h.id) {
                  <li><span class="dot"></span><div><strong>{{ h.summary }}</strong><div class="muted small">{{ h.at | brDate: true }} · {{ h.actorEmail || 'sistema' }}</div></div></li>
                } @empty { <li class="muted">Sem registros.</li> }
              </ul>
            </div>
          </mat-tab>
        </mat-tab-group>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text-muted); text-decoration: none; margin-bottom: 14px; }
    .hero { display: flex; gap: 16px; padding: 22px 24px; flex-wrap: wrap; margin-bottom: 16px; border-top: 3px solid var(--app-gold); }
    .hero-main { flex: 1 1 360px; }
    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 1.1px; text-transform: uppercase; color: var(--app-gold); }
    h1 { font-size: 22px; margin: 4px 0 8px; }
    .meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .hero-actions { display: flex; gap: 8px; align-items: center; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 16px; }
    @media (max-width: 900px) { .grid2 { grid-template-columns: 1fr; } }
    dl { display: grid; grid-template-columns: 120px 1fr; gap: 10px; margin: 0; }
    dt { color: var(--app-text-muted); font-size: 13px; }
    dd { margin: 0; }
    .pre { white-space: pre-wrap; }
    .fee { font-size: 18px; font-weight: 600; margin: 0 0 12px; }
    .stages { list-style: none; padding: 0; margin: 0; }
    .stages li, .success { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .success-form { display: flex; gap: 8px; align-items: center; }
    .small { font-size: 12px; }
    .tabc { margin-top: 16px; }
    .gen { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
    .tpl { width: 320px; }
    .full { width: 100%; }
    textarea.mono { font-family: ui-monospace, Consolas, monospace; font-size: 13px; line-height: 1.5; }
    .notice { display: flex; gap: 8px; align-items: center; padding: 10px 14px; border-radius: 10px; background: var(--app-warning-bg); color: var(--app-warning); margin-bottom: 12px; }
    .save { display: flex; justify-content: flex-end; }
    .history { list-style: none; margin: 0; padding: 16px 20px; }
    .history li { display: flex; gap: 12px; padding: 8px 0; }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--app-gold); margin-top: 6px; flex: 0 0 10px; }
    .danger { color: var(--app-danger); }
  `,
})
export class ContractDetailComponent {
  readonly id = input.required<string>();
  readonly auth = inject(AuthService);
  private readonly service = inject(ContractsService);
  private readonly documents = inject(DocumentsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly statusLabel = CONTRACT_STATUS_LABELS;
  readonly tone = CONTRACT_STATUS_TONE;
  readonly fee = feeSummary;
  readonly contract = signal<Contract | null>(null);
  readonly templates = signal<ContractTemplate[]>([]);
  readonly history = signal<AuditLog[]>([]);
  readonly missing = signal<string[]>([]);
  readonly refresh = signal(0);
  readonly body = new FormControl('', { nonNullable: true });
  readonly obtained = new FormControl('', { nonNullable: true });
  templateId = '';

  constructor() {
    effect(() => {
      const id = this.id();
      this.service.get(id).subscribe({
        next: (c) => this.set(c),
        error: () => void this.router.navigate(['/contratos']),
      });
    });
    this.service.templates().subscribe((t) => {
      this.templates.set(t.filter((x) => x.active));
      this.templateId ||= this.contract()?.templateId ?? t[0]?.id ?? '';
    });
  }

  private set(c: Contract): void {
    this.contract.set(c);
    this.body.reset(c.body ?? '');
    this.missing.set([...new Set([...(c.body ?? '').matchAll(/\[([a-z]+\.[A-Za-z]+)\]/g)].map((m) => m[1]))]);
    if (c.templateId) this.templateId = c.templateId;
  }

  onTab(label: string): void {
    if (label === 'Histórico') this.service.history(this.id()).subscribe((p) => this.history.set(p.items));
  }

  edit(): void {
    this.dialog.open(ContractFormDialogComponent, { data: this.contract(), width: '920px', maxWidth: '96vw' }).afterClosed()
      .subscribe((c?: Contract) => { if (c) { this.set(c); this.toast.success('Contrato atualizado.'); } });
  }

  activate(): void {
    this.confirm('Ativar contrato', 'As cobranças dos honorários (fixos, parcelas ou mensalidades) serão lançadas no financeiro como contas a receber.', 'Ativar')
      .subscribe((ok) => { if (ok) this.service.action(this.id(), 'activate').subscribe((c) => { this.set(c); this.refresh.update((v) => v + 1); this.toast.success('Contrato ativado e cobranças geradas.'); }); });
  }

  renew(c: Contract): void {
    this.dialog.open(ContractRenewDialogComponent, { data: c, width: '560px', maxWidth: '96vw' }).afterClosed().subscribe((r?: Contract) => {
      if (r) { this.set(r); this.refresh.update((v) => v + 1); this.toast.success('Contrato renovado.'); }
    });
  }

  close(): void {
    this.confirm('Encerrar contrato', 'O contrato será encerrado. Cobranças em aberto continuam devidas no financeiro.', 'Encerrar')
      .subscribe((ok) => { if (ok) this.service.action(this.id(), 'close').subscribe((c) => { this.set(c); this.toast.success('Contrato encerrado.'); }); });
  }

  cancel(): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Cancelar contrato', message: 'As cobranças em aberto deste contrato serão canceladas no financeiro.', confirmLabel: 'Cancelar contrato', danger: true, reasonLabel: 'Motivo', reasonRequired: true },
      width: '480px',
    }).afterClosed().subscribe((reason) => {
      if (typeof reason === 'string' && reason) this.service.cancel(this.id(), reason).subscribe((c) => { this.set(c); this.refresh.update((v) => v + 1); this.toast.success('Contrato cancelado.'); });
    });
  }

  billStage(stageId: string): void {
    this.service.billStage(this.id(), stageId).subscribe((c) => { this.set(c); this.refresh.update((v) => v + 1); this.toast.success('Etapa faturada no financeiro.'); });
  }

  registerSuccess(): void {
    const cents = parseMoneyToCents(this.obtained.value);
    if (!cents || cents <= 0) { this.toast.error('Informe o proveito econômico obtido.'); return; }
    const due = new Date(); due.setDate(due.getDate() + 15);
    const dueIso = `${due.getFullYear()}-${String(due.getMonth() + 1).padStart(2, '0')}-${String(due.getDate()).padStart(2, '0')}`;
    this.service.success(this.id(), cents, dueIso, null).subscribe((c) => { this.set(c); this.refresh.update((v) => v + 1); this.toast.success('Honorários de êxito lançados no financeiro.'); });
  }

  generate(): void {
    const run = () => this.service.generate(this.id(), this.templateId).subscribe((r) => {
      this.body.reset(r.body);
      this.missing.set(r.missingFields);
      this.toast.success(r.missingFields.length ? 'Minuta gerada — há campos a completar.' : 'Minuta gerada.');
    });
    if (this.body.value) {
      this.confirm('Substituir texto', 'O texto atual da minuta será substituído pelo modelo selecionado.', 'Gerar').subscribe((ok) => ok && run());
    } else {
      run();
    }
  }

  saveBody(): void {
    this.service.saveBody(this.id(), this.body.value).subscribe((c) => { this.set(c); this.toast.success('Texto salvo.'); });
  }

  export(format: 'pdf' | 'docx'): void {
    const go = () => this.service.export(this.id(), format).subscribe((r) => saveBlob(r, `${this.contract()?.number}.${format}`));
    if (this.body.dirty && this.auth.has('CONTRACTS_WRITE')) {
      this.service.saveBody(this.id(), this.body.value).subscribe((c) => { this.set(c); go(); });
    } else {
      go();
    }
  }

  toDocuments(): void {
    this.service.exportToDocuments(this.id(), 'pdf').subscribe(() => this.toast.success('Minuta arquivada no repositório de documentos do cliente.'));
  }

  uploadSigned(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.service.uploadSigned(this.id(), file).subscribe((c) => { this.set(c); this.toast.success('Contrato assinado anexado.'); });
  }

  openSigned(): void {
    const id = this.contract()?.signedDocumentId;
    if (id) this.documents.get(id).subscribe((d) => this.dialog.open(DocumentViewerDialogComponent, { data: d, width: '1100px', maxWidth: '96vw' }));
  }

  private confirm(title: string, message: string, confirmLabel: string) {
    return this.dialog.open(ConfirmDialogComponent, { data: { title, message, confirmLabel }, width: '480px' }).afterClosed();
  }
}

