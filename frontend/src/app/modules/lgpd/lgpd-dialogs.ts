import { Component, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../../core/api/api.service';
import { ToastService } from '../../core/services/toast.service';
import { ClientPickerComponent, PickedClient } from '../../shared/components/client-picker';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { RichTextEditorComponent } from '../editor/rich-text-editor';
import { saveBlob } from '../finance/finance.service';
import {
  Consent, DataRequest, DataShare, Incident, IncidentRisk, IncidentStatus, LgpdOptions, PrivacyPolicy, ProcessingActivity, REQUEST_STATUS,
  RequestStatus, splitList,
} from './lgpd.models';

const today = () => new Date().toISOString().slice(0, 10);
const COMMON = [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
  MatProgressBarModule, MatSlideToggleModule, MatCheckboxModule];
const DIALOG_STYLES = `
  .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
  .c2 { grid-column: span 2; }
  mat-form-field { width: 100%; }
  .hist { list-style: none; padding: 0; margin: 8px 0; font-size: 13px; }
  .hist li { padding: 6px 0; border-bottom: 1px solid var(--app-border); }
  .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin: 8px 0; }
  .warn { color: var(--app-warning, #b26a00); }
  .small { font-size: 12px; }
  h4 { margin: 12px 0 4px; }
  @media (max-width: 640px) { .grid { grid-template-columns: minmax(0, 1fr); } .c2 { grid-column: span 1; } }
`;

// ======================================================================= solicitação (nova)
@Component({
  selector: 'app-lgpd-request-dialog',
  imports: [...COMMON, ClientPickerComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>assignment_ind</mat-icon>Nova solicitação de titular</h2>
    <mat-dialog-content>
      <div class="grid">
        <mat-form-field class="c2"><mat-label>Direito solicitado (art. 18)</mat-label>
          <mat-select [(ngModel)]="m.type" required>@for (t of data.options.requestTypes; track t.value) { <mat-option [value]="t.value">{{ t.label }}</mat-option> }</mat-select>
        </mat-form-field>
        <mat-form-field><mat-label>Nome do titular</mat-label><input matInput [(ngModel)]="m.requesterName" required maxlength="200" /></mat-form-field>
        <mat-form-field><mat-label>E-mail</mat-label><input matInput type="email" [(ngModel)]="m.requesterEmail" maxlength="150" /></mat-form-field>
        <mat-form-field><mat-label>Telefone</mat-label><input matInput [(ngModel)]="m.requesterPhone" maxlength="30" /></mat-form-field>
        <mat-form-field><mat-label>Canal</mat-label>
          <mat-select [(ngModel)]="m.channel">@for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
        <div class="c2"><app-client-picker label="Cliente relacionado (opcional)" (picked)="client = $event" /></div>
        <mat-form-field><mat-label>Recebida em</mat-label><input matInput type="date" [(ngModel)]="m.receivedDate" [max]="max" /></mat-form-field>
        <mat-slide-toggle [(ngModel)]="m.identityVerified">Identidade do titular confirmada</mat-slide-toggle>
        <mat-form-field class="c2"><mat-label>Descrição do pedido</mat-label><textarea matInput rows="3" [(ngModel)]="m.description" maxlength="4000"></textarea></mat-form-field>
      </div>
      <p class="muted small">Prazo de resposta: 15 dias a partir do recebimento (art. 19, II). Confirme a identidade antes de entregar dados.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy() || !m.type || !m.requesterName.trim()">Registrar</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class RequestDialogComponent {
  readonly data = inject<{ options: LgpdOptions }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<RequestDialogComponent>);
  readonly busy = signal(false);
  readonly channels = ['E-mail', 'Presencial', 'Telefone', 'WhatsApp', 'Carta', 'Formulário do site'];
  readonly max = today();
  client: PickedClient | null = null;
  m = { type: '', requesterName: '', requesterEmail: '', requesterPhone: '', channel: 'E-mail', receivedDate: today(), identityVerified: false, description: '' };

  save(): void {
    this.busy.set(true);
    this.api.post<DataRequest>('/lgpd/requests', { ...this.m, clientId: this.client?.id ?? null }).subscribe({
      next: (r) => this.ref.close(r),
      error: () => this.busy.set(false),
    });
  }
}

// ======================================================================= solicitação (andamento)
@Component({
  selector: 'app-lgpd-request-detail',
  imports: [...COMMON, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>assignment_ind</mat-icon>{{ r().protocol }}</h2>
    <mat-dialog-content>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      <p><strong>{{ typeLabel(r().type) }}</strong> · {{ r().requesterName }}{{ r().requesterEmail ? ' · ' + r().requesterEmail : '' }}</p>
      <p class="muted small">Recebida em {{ r().receivedDate | brDate }} por {{ r().channel || '—' }} · prazo <strong [class.warn]="overdue()">{{ r().dueDate | brDate }}</strong>
        {{ r().clientName ? ' · cliente ' + r().clientName : '' }}</p>
      @if (r().description) { <p>{{ r().description }}</p> }
      @if (r().clientId && open()) {
        <div class="row">
          <button mat-stroked-button (click)="exportData('pdf')"><mat-icon>picture_as_pdf</mat-icon>Relatório dos dados (PDF)</button>
          <button mat-stroked-button (click)="exportData('xlsx')"><mat-icon>table_view</mat-icon>Excel</button>
          @if (r().type === 'ANONIMIZACAO_BLOQUEIO_ELIMINACAO' || r().type === 'ELIMINACAO_CONSENTIMENTO') {
            <button mat-stroked-button (click)="anonymize()"><mat-icon>person_off</mat-icon>Anonimizar cliente</button>
          }
        </div>
      }
      @if (open()) {
        <h4>Andamento</h4>
        <div class="grid">
          <mat-form-field><mat-label>Situação</mat-label>
            <mat-select [(ngModel)]="status">@for (s of statuses; track s) { <mat-option [value]="s">{{ statusLabel[s] }}</mat-option> }</mat-select></mat-form-field>
          <mat-slide-toggle [(ngModel)]="identity">Identidade confirmada</mat-slide-toggle>
          <mat-form-field class="c2"><mat-label>Resposta ao titular (ou fundamento da negativa)</mat-label>
            <textarea matInput rows="4" [(ngModel)]="response" maxlength="8000"></textarea></mat-form-field>
          <mat-form-field class="c2"><mat-label>Anotação interna</mat-label><input matInput [(ngModel)]="note" maxlength="2000" /></mat-form-field>
        </div>
      } @else {
        <h4>Resposta</h4><p>{{ r().response }}</p>
        <p class="muted small">{{ statusLabel[r().status] }} por {{ r().respondedByName }} em {{ r().respondedAt | brDate: true }}</p>
      }
      <h4>Histórico</h4>
      <ul class="hist">@for (h of r().history.slice().reverse(); track $index) { <li>{{ h.at | brDate: true }} · {{ h.byName }} — {{ h.text }}</li> }</ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="changed">Fechar</button>
      @if (open()) { <button mat-flat-button (click)="save()" [disabled]="busy()">Salvar andamento</button> }
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class RequestDetailComponent {
  readonly data = inject<{ request: DataRequest; options: LgpdOptions }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  readonly r = signal(this.data.request);
  readonly busy = signal(false);
  readonly statusLabel = REQUEST_STATUS;
  readonly statuses: RequestStatus[] = ['RECEBIDA', 'EM_ANALISE', 'ATENDIDA', 'NEGADA', 'CANCELADA'];
  status: RequestStatus = this.data.request.status;
  identity = this.data.request.identityVerified;
  response = this.data.request.response ?? '';
  note = '';
  changed = false;

  open(): boolean {
    return this.r().status === 'RECEBIDA' || this.r().status === 'EM_ANALISE';
  }

  overdue(): boolean {
    return this.open() && this.r().dueDate < today();
  }

  typeLabel(t: string): string {
    return this.data.options.requestTypes.find((o) => o.value === t)?.label ?? t;
  }

  save(): void {
    this.busy.set(true);
    this.api.put<DataRequest>(`/lgpd/requests/${this.r().id}`, { status: this.status, response: this.response || null,
      identityVerified: this.identity, note: this.note || null }).subscribe({
      next: (x) => { this.r.set(x); this.note = ''; this.changed = true; this.busy.set(false); this.toast.success('Andamento salvo.'); },
      error: () => this.busy.set(false),
    });
  }

  exportData(format: 'pdf' | 'xlsx'): void {
    this.api.getBlob(`/lgpd/clients/${this.r().clientId}/export`, { format }).subscribe((res) => saveBlob(res, `dados-titular.${format}`));
  }

  anonymize(): void {
    const data: ConfirmData = { title: 'Anonimizar cliente', danger: true, confirmLabel: 'Anonimizar',
      message: 'Remove de forma irreversível nome, documentos, contatos, endereço e atendimentos do cliente. Só é permitido sem processos, '
        + 'contratos ativos ou valores em aberto (guarda legal). Lançamentos quitados permanecem por obrigação fiscal, sem identificação.' };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.busy.set(true);
      this.api.post<{ label: string }>(`/lgpd/clients/${this.r().clientId}/anonymize?requestId=${encodeURIComponent(this.r().id)}`, {}).subscribe({
        next: (res) => { this.busy.set(false); this.changed = true; this.toast.success(`Cliente anonimizado: ${res.label}.`); },
        error: () => this.busy.set(false),
      });
    });
  }
}

// ======================================================================= consentimento
@Component({
  selector: 'app-lgpd-consent-dialog',
  imports: [...COMMON, ClientPickerComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>fact_check</mat-icon>Registrar consentimento</h2>
    <mat-dialog-content>
      <app-client-picker label="Titular (cliente)" (picked)="client = $event" />
      <div class="grid">
        <mat-form-field class="c2"><mat-label>Finalidade específica</mat-label><input matInput [(ngModel)]="m.purpose" maxlength="500"
          placeholder="ex.: envio de comunicados jurídicos por WhatsApp" /></mat-form-field>
        <mat-form-field><mat-label>Como foi obtido</mat-label>
          <mat-select [(ngModel)]="m.channel">@for (c of channels; track c) { <mat-option [value]="c">{{ c }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Data</mat-label><input matInput type="date" [(ngModel)]="m.grantedDate" [max]="max" /></mat-form-field>
        <mat-form-field><mat-label>Válido até (opcional)</mat-label><input matInput type="date" [(ngModel)]="m.expiresDate" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Evidência (onde está a prova)</mat-label><input matInput [(ngModel)]="m.evidence" maxlength="1000"
          placeholder="ex.: termo assinado guardado na central documental" /></mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy() || !client || !m.purpose.trim()">Registrar</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class ConsentDialogComponent {
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<ConsentDialogComponent>);
  readonly busy = signal(false);
  readonly channels = ['Termo assinado', 'Assinatura eletrônica', 'E-mail', 'Formulário do site', 'WhatsApp', 'Presencial'];
  readonly max = today();
  client: PickedClient | null = null;
  m = { purpose: '', channel: 'Termo assinado', grantedDate: today(), expiresDate: '', evidence: '' };

  save(): void {
    this.busy.set(true);
    this.api.post<Consent>('/lgpd/consents', { ...this.m, expiresDate: this.m.expiresDate || null, clientId: this.client!.id }).subscribe({
      next: (c) => this.ref.close(c),
      error: () => this.busy.set(false),
    });
  }
}

// ======================================================================= atividade de tratamento
@Component({
  selector: 'app-lgpd-activity-dialog',
  imports: [...COMMON],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>account_tree</mat-icon>{{ data.activity ? 'Atividade de tratamento' : 'Nova atividade de tratamento' }}</h2>
    <mat-dialog-content>
      @if (data.activity?.suggestion) { <p class="warn small">Sugestão do sistema: revise conforme a realidade do escritório e salve para confirmar.</p> }
      <div class="grid">
        <mat-form-field class="c2"><mat-label>Atividade</mat-label><input matInput [(ngModel)]="m.name" maxlength="200" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Finalidade</mat-label><textarea matInput rows="2" [(ngModel)]="m.purpose" maxlength="1000"></textarea></mat-form-field>
        <mat-form-field class="c2"><mat-label>Base legal</mat-label>
          <mat-select [(ngModel)]="m.legalBasis">@for (b of data.options.legalBases; track b.value) { <mat-option [value]="b.value">{{ b.label }}</mat-option> }</mat-select></mat-form-field>
        <mat-slide-toggle [(ngModel)]="m.sensitiveData">Envolve dados sensíveis (art. 5º, II)</mat-slide-toggle>
        <mat-slide-toggle [(ngModel)]="m.internationalTransfer">Transferência internacional</mat-slide-toggle>
        @if (m.sensitiveData) {
          <mat-form-field class="c2"><mat-label>Hipótese para dados sensíveis (art. 11)</mat-label><input matInput [(ngModel)]="m.sensitiveBasis" maxlength="500" /></mat-form-field>
        }
        <mat-form-field><mat-label>Categorias de dados (uma por linha)</mat-label><textarea matInput rows="3" [(ngModel)]="categories"></textarea></mat-form-field>
        <mat-form-field><mat-label>Titulares (uma por linha)</mat-label><textarea matInput rows="3" [(ngModel)]="subjects"></textarea></mat-form-field>
        <mat-form-field><mat-label>Compartilhado com (uma por linha)</mat-label><textarea matInput rows="2" [(ngModel)]="shared"></textarea></mat-form-field>
        <mat-form-field><mat-label>Prazo de retenção</mat-label><textarea matInput rows="2" [(ngModel)]="m.retention" maxlength="300"></textarea></mat-form-field>
        <mat-form-field class="c2"><mat-label>Medidas de segurança</mat-label><textarea matInput rows="2" [(ngModel)]="m.securityMeasures" maxlength="1000"></textarea></mat-form-field>
        <mat-form-field><mat-label>Responsável</mat-label><input matInput [(ngModel)]="m.responsibleName" maxlength="120" /></mat-form-field>
        <mat-slide-toggle [(ngModel)]="m.active">Atividade em vigor</mat-slide-toggle>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (data.activity) { <button mat-button class="danger-text" (click)="remove()"><mat-icon>delete</mat-icon>Remover</button> }
      <span class="spacer"></span>
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy() || !m.name.trim() || !m.purpose.trim() || !m.legalBasis">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES + `.danger-text { color: var(--app-danger); } .spacer { flex: 1; }`,
})
export class ActivityDialogComponent {
  readonly data = inject<{ activity: ProcessingActivity | null; options: LgpdOptions }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<ActivityDialogComponent>);
  private readonly dialog = inject(MatDialog);
  readonly busy = signal(false);
  private readonly a = this.data.activity;
  m = { name: this.a?.name ?? '', purpose: this.a?.purpose ?? '', legalBasis: this.a?.legalBasis ?? '', sensitiveData: this.a?.sensitiveData ?? false,
    sensitiveBasis: this.a?.sensitiveBasis ?? '', internationalTransfer: this.a?.internationalTransfer ?? false, retention: this.a?.retention ?? '',
    securityMeasures: this.a?.securityMeasures ?? '', responsibleName: this.a?.responsibleName ?? '', active: this.a?.active ?? true };
  categories = (this.a?.dataCategories ?? []).join('\n');
  subjects = (this.a?.dataSubjects ?? []).join('\n');
  shared = (this.a?.sharedWith ?? []).join('\n');

  save(): void {
    this.busy.set(true);
    const body = { ...this.m, dataCategories: splitList(this.categories), dataSubjects: splitList(this.subjects), sharedWith: splitList(this.shared) };
    const req = this.a ? this.api.put<ProcessingActivity>(`/lgpd/activities/${this.a.id}`, body) : this.api.post<ProcessingActivity>('/lgpd/activities', body);
    req.subscribe({ next: (x) => this.ref.close(x), error: () => this.busy.set(false) });
  }

  remove(): void {
    const data: ConfirmData = { title: 'Remover atividade', message: `Remover "${this.a!.name}" do registro de tratamento?`, danger: true, confirmLabel: 'Remover' };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (ok) this.api.delete<void>(`/lgpd/activities/${this.a!.id}`).subscribe(() => this.ref.close(true));
    });
  }
}

// ======================================================================= incidente
@Component({
  selector: 'app-lgpd-incident-dialog',
  imports: [...COMMON, BrDatePipe],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>report</mat-icon>{{ i ? i.title : 'Registrar incidente de segurança' }}</h2>
    <mat-dialog-content>
      @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
      @if (!i) {
        <div class="grid">
          <mat-form-field class="c2"><mat-label>Título</mat-label><input matInput [(ngModel)]="n.title" maxlength="200" /></mat-form-field>
          <mat-form-field class="c2"><mat-label>O que aconteceu</mat-label><textarea matInput rows="3" [(ngModel)]="n.description" maxlength="4000"></textarea></mat-form-field>
          <mat-form-field><mat-label>Descoberto em</mat-label><input matInput type="datetime-local" [(ngModel)]="n.discoveredAt" /></mat-form-field>
          <mat-form-field><mat-label>Ocorrido em (se souber)</mat-label><input matInput type="datetime-local" [(ngModel)]="n.occurredAt" /></mat-form-field>
          <mat-form-field><mat-label>Dados afetados (um por linha)</mat-label><textarea matInput rows="2" [(ngModel)]="categories"></textarea></mat-form-field>
          <mat-form-field><mat-label>Titulares afetados (estimativa)</mat-label><input matInput type="number" min="0" [(ngModel)]="n.affectedSubjects" /></mat-form-field>
          <mat-form-field><mat-label>Risco aos titulares</mat-label>
            <mat-select [(ngModel)]="n.risk">@for (r of risks; track r) { <mat-option [value]="r">{{ riskLabel[r] }}</mat-option> }</mat-select></mat-form-field>
          <mat-slide-toggle [(ngModel)]="n.sensitiveData">Envolve dados sensíveis</mat-slide-toggle>
          <mat-slide-toggle class="c2" [(ngModel)]="n.anpdNotificationRequired">Pode gerar risco ou dano relevante (comunicar ANPD e titulares)</mat-slide-toggle>
          <mat-form-field class="c2"><mat-label>Medidas de contenção adotadas</mat-label><textarea matInput rows="2" [(ngModel)]="n.containmentMeasures" maxlength="4000"></textarea></mat-form-field>
        </div>
        <p class="muted small">Quando houver risco ou dano relevante, a comunicação à ANPD e aos titulares deve ocorrer em até 3 dias úteis da ciência
          (Resolução CD/ANPD nº 15/2024). Risco alto marca a comunicação como obrigatória.</p>
      } @else {
        <p>{{ i.description }}</p>
        <p class="muted small">Descoberto em {{ i.discoveredAt | brDate: true }} · {{ i.affectedSubjects }} titular(es) · risco {{ riskLabel[i.risk] }}
          {{ i.sensitiveData ? '· dados sensíveis' : '' }}</p>
        @if (i.anpdNotificationRequired) {
          <p [class.warn]="!i.anpdNotifiedDate"><strong>Comunicação à ANPD:</strong>
            {{ i.anpdNotifiedDate ? 'feita em ' + (i.anpdNotifiedDate | brDate) + (i.anpdProtocol ? ' (protocolo ' + i.anpdProtocol + ')' : '') : 'pendente — prazo ' + (i.anpdDeadline | brDate) }}</p>
        }
        @if (i.status !== 'ENCERRADO') {
          <div class="grid">
            <mat-form-field><mat-label>Situação</mat-label>
              <mat-select [(ngModel)]="u.status">@for (s of statuses; track s) { <mat-option [value]="s">{{ statusLabel[s] }}</mat-option> }</mat-select></mat-form-field>
            <mat-form-field><mat-label>Risco</mat-label>
              <mat-select [(ngModel)]="u.risk">@for (r of risks; track r) { <mat-option [value]="r">{{ riskLabel[r] }}</mat-option> }</mat-select></mat-form-field>
            <mat-slide-toggle class="c2" [(ngModel)]="u.anpdNotificationRequired">Comunicação à ANPD necessária</mat-slide-toggle>
            @if (u.anpdNotificationRequired && !i.anpdNotifiedDate) {
              <mat-form-field><mat-label>Comunicado à ANPD em</mat-label><input matInput type="date" [(ngModel)]="u.anpdNotifiedDate" [max]="max" /></mat-form-field>
              <mat-form-field><mat-label>Protocolo ANPD</mat-label><input matInput [(ngModel)]="u.anpdProtocol" maxlength="120" /></mat-form-field>
            }
            @if (!i.subjectsNotifiedDate) {
              <mat-form-field><mat-label>Titulares comunicados em</mat-label><input matInput type="date" [(ngModel)]="u.subjectsNotifiedDate" [max]="max" /></mat-form-field>
            }
            <mat-form-field class="c2"><mat-label>Medidas de contenção e mitigação</mat-label><textarea matInput rows="2" [(ngModel)]="u.containmentMeasures"></textarea></mat-form-field>
            <mat-form-field class="c2"><mat-label>Anotação</mat-label><input matInput [(ngModel)]="u.note" maxlength="2000" /></mat-form-field>
          </div>
        }
        <h4>Linha do tempo</h4>
        <ul class="hist">@for (h of i.timeline.slice().reverse(); track $index) { <li>{{ h.at | brDate: true }} · {{ h.byName }} — {{ h.text }}</li> }</ul>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Fechar</button>
      @if (!i) { <button mat-flat-button (click)="create()" [disabled]="busy() || !n.title.trim() || !n.description.trim()">Registrar</button> }
      @else if (i.status !== 'ENCERRADO') { <button mat-flat-button (click)="update()" [disabled]="busy()">Salvar</button> }
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class IncidentDialogComponent {
  readonly data = inject<{ incident: Incident | null }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<IncidentDialogComponent>);
  readonly busy = signal(false);
  readonly i = this.data.incident;
  readonly max = today();
  readonly risks: IncidentRisk[] = ['BAIXO', 'MEDIO', 'ALTO'];
  readonly riskLabel: Record<IncidentRisk, string> = { BAIXO: 'Baixo', MEDIO: 'Médio', ALTO: 'Alto' };
  readonly statuses: IncidentStatus[] = ['ABERTO', 'EM_TRATAMENTO', 'ENCERRADO'];
  readonly statusLabel: Record<IncidentStatus, string> = { ABERTO: 'Aberto', EM_TRATAMENTO: 'Em tratamento', ENCERRADO: 'Encerrado' };
  categories = '';
  n = { title: '', description: '', discoveredAt: new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16), occurredAt: '',
    affectedSubjects: 0, risk: 'MEDIO' as IncidentRisk, sensitiveData: false, anpdNotificationRequired: false, containmentMeasures: '' };
  u = { status: this.i?.status ?? 'ABERTO', risk: this.i?.risk ?? 'MEDIO', anpdNotificationRequired: this.i?.anpdNotificationRequired ?? false,
    anpdNotifiedDate: '', anpdProtocol: '', subjectsNotifiedDate: '', containmentMeasures: this.i?.containmentMeasures ?? '', note: '' };

  create(): void {
    this.busy.set(true);
    this.api.post<Incident>('/lgpd/incidents', { ...this.n, discoveredAt: new Date(this.n.discoveredAt).toISOString(),
      occurredAt: this.n.occurredAt ? new Date(this.n.occurredAt).toISOString() : null, dataCategories: splitList(this.categories),
      affectedSubjects: Number(this.n.affectedSubjects) || 0 }).subscribe({ next: (x) => this.ref.close(x), error: () => this.busy.set(false) });
  }

  update(): void {
    this.busy.set(true);
    this.api.put<Incident>(`/lgpd/incidents/${this.i!.id}`, { ...this.u, anpdNotifiedDate: this.u.anpdNotifiedDate || null,
      subjectsNotifiedDate: this.u.subjectsNotifiedDate || null, anpdProtocol: this.u.anpdProtocol || null, note: this.u.note || null })
      .subscribe({ next: (x) => this.ref.close(x), error: () => this.busy.set(false) });
  }
}

// ======================================================================= compartilhamento
@Component({
  selector: 'app-lgpd-share-dialog',
  imports: [...COMMON, ClientPickerComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>share</mat-icon>Registrar compartilhamento</h2>
    <mat-dialog-content>
      <div class="grid">
        <mat-form-field><mat-label>Destinatário</mat-label><input matInput [(ngModel)]="m.recipient" maxlength="200" /></mat-form-field>
        <mat-form-field><mat-label>Tipo</mat-label>
          <mat-select [(ngModel)]="m.recipientType">@for (t of types; track t) { <mat-option [value]="t">{{ t }}</mat-option> }</mat-select></mat-form-field>
        <div class="c2"><app-client-picker label="Titular (cliente, opcional)" (picked)="client = $event" /></div>
        <mat-form-field class="c2"><mat-label>Dados compartilhados</mat-label><input matInput [(ngModel)]="m.dataDescription" maxlength="1000" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Finalidade</mat-label><input matInput [(ngModel)]="m.purpose" maxlength="1000" /></mat-form-field>
        <mat-form-field class="c2"><mat-label>Base legal</mat-label>
          <mat-select [(ngModel)]="m.legalBasis">@for (b of data.options.legalBases; track b.value) { <mat-option [value]="b.value">{{ b.label }}</mat-option> }</mat-select></mat-form-field>
        <mat-form-field><mat-label>Data</mat-label><input matInput type="date" [(ngModel)]="m.sharedDate" [max]="max" /></mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy() || !m.recipient.trim() || !m.dataDescription.trim() || !m.purpose.trim() || !m.legalBasis">Registrar</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES,
})
export class ShareDialogComponent {
  readonly data = inject<{ options: LgpdOptions }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<ShareDialogComponent>);
  readonly busy = signal(false);
  readonly max = today();
  readonly types = ['Tribunal / órgão público', 'Perito', 'Contabilidade', 'Fornecedor de tecnologia', 'Correspondente jurídico', 'Autoridade', 'Outro'];
  client: PickedClient | null = null;
  m = { recipient: '', recipientType: 'Tribunal / órgão público', dataDescription: '', purpose: '', legalBasis: 'EXERCICIO_DIREITOS', sharedDate: today() };

  save(): void {
    this.busy.set(true);
    this.api.post<DataShare>('/lgpd/shares', { ...this.m, clientId: this.client?.id ?? null }).subscribe({
      next: (x) => this.ref.close(x),
      error: () => this.busy.set(false),
    });
  }
}

// ======================================================================= política de privacidade
@Component({
  selector: 'app-lgpd-policy-dialog',
  imports: [...COMMON, RichTextEditorComponent],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>policy</mat-icon>Política de privacidade {{ data.policy ? 'v' + data.policy.version : '(nova versão)' }}</h2>
    <mat-dialog-content>
      <mat-form-field class="full"><mat-label>Título</mat-label><input matInput [(ngModel)]="title" maxlength="200" /></mat-form-field>
      <app-rich-text-editor class="pol" [compact]="true" label="Texto da política" />
      <p class="muted small">Revise o texto com o advogado responsável: ele é exibido publicamente aos clientes e titulares após a publicação.
        Versões publicadas ficam preservadas no histórico.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="save()" [disabled]="busy()">Salvar rascunho</button>
    </mat-dialog-actions>
  `,
  styles: DIALOG_STYLES + `.full { width: 100%; } .pol { display: block; min-height: 360px; }`,
})
export class PolicyDialogComponent {
  readonly data = inject<{ policy: PrivacyPolicy | null; template: { title: string; content: string } | null }>(MAT_DIALOG_DATA);
  private readonly api = inject(ApiService);
  private readonly ref = inject(MatDialogRef<PolicyDialogComponent>);
  private readonly editor = viewChild.required(RichTextEditorComponent);
  readonly busy = signal(false);
  title = this.data.policy?.title ?? this.data.template?.title ?? 'Política de privacidade';

  constructor() {
    queueMicrotask(() => this.editor().setHtml(this.data.policy?.content ?? this.data.template?.content ?? '<p><br></p>'));
  }

  save(): void {
    this.busy.set(true);
    const body = { title: this.title, content: this.editor().getHtml() };
    const req = this.data.policy ? this.api.put<PrivacyPolicy>(`/lgpd/policies/${this.data.policy.id}`, body)
      : this.api.post<PrivacyPolicy>('/lgpd/policies', body);
    req.subscribe({ next: (p) => this.ref.close(p), error: () => this.busy.set(false) });
  }
}
