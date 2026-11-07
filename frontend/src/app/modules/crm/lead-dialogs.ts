import { Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ClientType, EmployeeOption, LegalArea } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { AREA_LABELS } from '../../shared/labels';
import { BrDatePipe, CentsPipe, RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { centsToInput, cpfCnpjValidator, formatCpfCnpj, formatPhone, parseMoneyToCents } from '../../shared/validators/br-validators';
import { ACTIVITY_LABELS, CrmService, Lead, LeadActivity, LeadActivityType, LeadSource, LeadStage, SOURCE_LABELS, STAGES } from './crm.service';

// ======================================================================= cadastro / edição
@Component({
  selector: 'app-lead-form-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>person_search</mat-icon>{{ data?.lead ? 'Editar oportunidade' : 'Nova oportunidade' }}</h2>
    <mat-dialog-content>
      <form [formGroup]="form" class="form-grid" id="leadForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Nome do contato</mat-label>
          <input matInput formControlName="name" />
          @if (form.controls.name.invalid) { <mat-error>Obrigatório</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type">
            <mat-option value="PF">Pessoa física</mat-option>
            <mat-option value="PJ">Pessoa jurídica</mat-option>
          </mat-select>
        </mat-form-field>
        @if (form.controls.type.value === 'PJ') {
          <mat-form-field appearance="outline"><mat-label>Empresa</mat-label><input matInput formControlName="company" /></mat-form-field>
        }
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Telefone / WhatsApp</mat-label>
          <input matInput formControlName="phone" (input)="maskPhone($event)" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>E-mail</mat-label>
          <input matInput type="email" formControlName="email" />
          @if (form.controls.email.invalid) { <mat-error>E-mail inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Origem</mat-label>
          <mat-select formControlName="source">
            @for (s of sources; track s) { <mat-option [value]="s">{{ sourceLabels[s] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Detalhe da origem</mat-label>
          <input matInput formControlName="sourceDetail" placeholder="Ex.: indicado por Dr. Paulo" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Área</mat-label>
          <mat-select formControlName="legalArea">
            <mat-option [value]="null">—</mat-option>
            @for (a of areas; track a) { <mat-option [value]="a">{{ areaLabels[a] }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Assunto / demanda</mat-label>
          <input matInput formControlName="subject" placeholder="Ex.: rescisão indireta, horas extras" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Honorários estimados (R$)</mat-label>
          <input matInput formControlName="value" inputmode="decimal" placeholder="0,00" />
        </mat-form-field>
        @if (!data?.lead) {
          <mat-form-field appearance="outline" class="c6">
            <mat-label>Etapa inicial</mat-label>
            <mat-select formControlName="stage">
              @for (s of openStages; track s.key) { <mat-option [value]="s.key">{{ s.label }}</mat-option> }
            </mat-select>
          </mat-form-field>
        }
        <mat-form-field appearance="outline" [class]="data?.lead ? 'c12' : 'c6'">
          <mat-label>Responsável</mat-label>
          <mat-select formControlName="ownerUid">
            @for (s of staff(); track s.uid) { <mat-option [value]="s.uid">{{ s.displayName }}</mat-option> }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>Próxima ação</mat-label>
          <input matInput formControlName="nextAction" placeholder="Ex.: enviar proposta revisada" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Data da próxima ação</mat-label>
          <input matInput type="date" formControlName="nextActionDate" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Etiquetas (separadas por vírgula)</mat-label>
          <input matInput formControlName="tags" placeholder="Ex.: urgente, empresarial" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Observações</mat-label>
          <textarea matInput rows="3" formControlName="notes"></textarea>
          <mat-hint>Não registre CPF/CNPJ aqui: o documento é coletado apenas na conversão em cliente (LGPD).</mat-hint>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="leadForm" [disabled]="saving()">Salvar</button>
    </mat-dialog-actions>
  `,
  styles: `mat-dialog-content { max-height: 76vh; } .c12 { grid-column: span 12; }`,
})
export class LeadFormDialogComponent {
  readonly data = inject<{ lead?: Lead; stage?: LeadStage } | null>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LeadFormDialogComponent>);
  private readonly service = inject(CrmService);
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  readonly saving = signal(false);
  readonly staff = toSignal(this.api.get<EmployeeOption[]>('/employees/options'), { initialValue: [] });
  readonly sources = Object.keys(SOURCE_LABELS) as LeadSource[];
  readonly sourceLabels = SOURCE_LABELS;
  readonly areas = Object.keys(AREA_LABELS) as LegalArea[];
  readonly areaLabels = AREA_LABELS;
  readonly openStages = STAGES.filter((s) => s.open);
  private readonly l = this.data?.lead;
  readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.l?.name ?? '', [Validators.required, Validators.maxLength(150)]],
    type: [(this.l?.type ?? 'PF') as ClientType],
    company: [this.l?.company ?? ''],
    phone: [this.l?.phone ?? ''],
    email: [this.l?.email ?? '', Validators.email],
    source: [(this.l?.source ?? 'INDICACAO') as LeadSource],
    sourceDetail: [this.l?.sourceDetail ?? ''],
    legalArea: [(this.l?.legalArea ?? null) as LegalArea | null],
    subject: [this.l?.subject ?? ''],
    value: [this.l && this.l.estimatedValueCents ? centsToInput(this.l.estimatedValueCents) : ''],
    stage: [(this.data?.stage ?? 'NOVO') as LeadStage],
    ownerUid: [this.l?.ownerUid ?? this.auth.me()?.uid ?? ''],
    nextAction: [this.l?.nextAction ?? ''],
    nextActionDate: [this.l?.nextActionDate ?? ''],
    tags: [(this.l?.tags ?? []).join(', ')],
    notes: [this.l?.notes ?? ''],
  });

  maskPhone(event: Event): void {
    this.form.controls.phone.setValue(formatPhone((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = {
      name: v.name.trim(), type: v.type, company: v.type === 'PJ' ? v.company || null : null, phone: v.phone || null,
      email: v.email || null, source: v.source, sourceDetail: v.sourceDetail || null, legalArea: v.legalArea,
      subject: v.subject || null, stage: this.l ? null : v.stage, estimatedValueCents: parseMoneyToCents(v.value) ?? 0,
      ownerUid: v.ownerUid || null, nextAction: v.nextAction || null, nextActionDate: v.nextActionDate || null,
      notes: v.notes || null, tags: v.tags.split(',').map((t) => t.trim()).filter(Boolean).slice(0, 10),
    };
    this.saving.set(true);
    const request$ = this.l ? this.service.update(this.l.id, body) : this.service.create(body);
    request$.subscribe({ next: (lead) => this.ref.close(lead), error: () => this.saving.set(false) });
  }
}

// ======================================================================= conversão em cliente
@Component({
  selector: 'app-lead-convert-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title class="dialog-title"><mat-icon>how_to_reg</mat-icon>Converter em cliente</h2>
    <mat-dialog-content>
      <p class="muted small">Cria o cadastro do cliente (CPF/CNPJ criptografado) e marca a oportunidade como <strong>ganha</strong>.
        Os demais dados podem ser completados depois na ficha do cliente.</p>
      <form [formGroup]="form" class="form-grid" id="convForm" (ngSubmit)="save()">
        <mat-form-field appearance="outline" class="c4">
          <mat-label>Tipo</mat-label>
          <mat-select formControlName="type" (selectionChange)="form.controls.document.updateValueAndValidity()">
            <mat-option value="PF">Pessoa física</mat-option>
            <mat-option value="PJ">Pessoa jurídica</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline" class="c8">
          <mat-label>{{ form.controls.type.value === 'PJ' ? 'Razão social' : 'Nome completo' }}</mat-label>
          <input matInput formControlName="name" />
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>{{ form.controls.type.value === 'PJ' ? 'CNPJ' : 'CPF' }}</mat-label>
          <input matInput formControlName="document" (input)="maskDoc($event)" />
          @if (form.controls.document.invalid) { <mat-error>Documento inválido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline" class="c6">
          <mat-label>Telefone</mat-label>
          <input matInput formControlName="phone" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>E-mail</mat-label>
          <input matInput formControlName="email" />
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close type="button">Cancelar</button>
      <button mat-flat-button type="submit" form="convForm" [disabled]="saving()">Converter</button>
    </mat-dialog-actions>
  `,
  styles: `.small { font-size: 13px; }`,
})
export class LeadConvertDialogComponent {
  readonly lead = inject<Lead>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LeadConvertDialogComponent>);
  private readonly service = inject(CrmService);
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    type: [this.lead.type],
    name: [this.lead.type === 'PJ' && this.lead.company ? this.lead.company : this.lead.name, [Validators.required, Validators.maxLength(200)]],
    document: ['', Validators.required],
    phone: [this.lead.phone ?? ''],
    email: [this.lead.email ?? '', Validators.email],
  });

  constructor() {
    this.form.controls.document.addValidators(cpfCnpjValidator(() => this.form.controls.type.value));
  }

  maskDoc(event: Event): void {
    this.form.controls.document.setValue(formatCpfCnpj((event.target as HTMLInputElement).value), { emitEvent: false });
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service.convert(this.lead.id, {
      type: v.type, name: v.name.trim(), document: v.document, phone: v.phone || null, email: v.email || null,
      notes: this.lead.subject ? `Captado pelo CRM: ${this.lead.subject}` : 'Captado pelo CRM.',
    }).subscribe({ next: (r) => this.ref.close(r), error: () => this.saving.set(false) });
  }
}

// ======================================================================= detalhe e interações
@Component({
  selector: 'app-lead-detail-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatButtonModule, MatIconModule,
    MatTooltipModule, CentsPipe, BrDatePipe, RelativeTimePipe],
  template: `
    @if (lead(); as l) {
      <h2 mat-dialog-title class="dialog-title">
        <mat-icon>person_search</mat-icon>{{ l.name }}
        <span class="temp" [class]="l.temperature" [matTooltip]="'Pontuação ' + l.score + '/100'">{{ tempLabel[l.temperature] }} · {{ l.score }}</span>
      </h2>
      <mat-dialog-content>
        <div class="stages">
          @for (s of stages; track s.key) {
            <button type="button" class="st" [class.on]="s.key === l.stage" [style.--sc]="s.color"
              [disabled]="!!l.convertedClientId" (click)="move(l, s.key)">{{ s.label }}</button>
          }
        </div>
        <div class="grid2">
          <div>
            <dl>
              <dt>Contato</dt><dd>{{ l.phone || '—' }} · {{ l.email || '—' }}</dd>
              @if (l.company) { <dt>Empresa</dt><dd>{{ l.company }}</dd> }
              <dt>Demanda</dt><dd>{{ l.subject || '—' }} @if (l.legalArea) { <span class="badge">{{ areaLabels[l.legalArea] }}</span> }</dd>
              <dt>Origem</dt><dd>{{ l.sourceLabel }}{{ l.sourceDetail ? ' — ' + l.sourceDetail : '' }}</dd>
              <dt>Honorários estimados</dt><dd>{{ l.estimatedValueCents | cents }} <span class="muted">({{ l.probability }}% de chance)</span></dd>
              <dt>Responsável</dt><dd>{{ l.ownerName }}</dd>
              <dt>Próxima ação</dt>
              <dd [class.late]="l.nextActionOverdue">{{ l.nextAction || '—' }} @if (l.nextActionDate) { · {{ l.nextActionDate | brDate }} }</dd>
              <dt>Na etapa há</dt><dd>{{ l.daysInStage }} dia(s)</dd>
              @if (l.lostReason) { <dt>Motivo da perda</dt><dd class="late">{{ l.lostReason }}</dd> }
              @if (l.tags.length) { <dt>Etiquetas</dt><dd>@for (t of l.tags; track t) { <span class="badge">{{ t }}</span> }</dd> }
            </dl>
            @if (l.notes) { <p class="notes">{{ l.notes }}</p> }
          </div>
          <div>
            <div class="form-section-title">Registrar interação</div>
            <form [formGroup]="form" class="form-grid" (ngSubmit)="register(l)">
              <mat-form-field appearance="outline" class="c4">
                <mat-label>Tipo</mat-label>
                <mat-select formControlName="type">
                  @for (t of activityTypes; track t) { <mat-option [value]="t">{{ activityLabels[t].label }}</mat-option> }
                </mat-select>
              </mat-form-field>
              <mat-form-field appearance="outline" class="c8">
                <mat-label>O que aconteceu?</mat-label>
                <input matInput formControlName="text" />
              </mat-form-field>
              <mat-form-field appearance="outline" class="c8">
                <mat-label>Nova próxima ação (opcional)</mat-label>
                <input matInput formControlName="nextAction" />
              </mat-form-field>
              <mat-form-field appearance="outline" class="c4">
                <mat-label>Data</mat-label>
                <input matInput type="date" formControlName="nextActionDate" />
              </mat-form-field>
              <div class="row"><span class="spacer"></span><button mat-flat-button type="submit" [disabled]="form.invalid || sending()">Registrar</button></div>
            </form>
            <div class="timeline">
              @for (a of activities(); track a.id) {
                <div class="ev"><mat-icon>{{ activityLabels[a.type].icon }}</mat-icon>
                  <div><div>{{ a.text }}</div><div class="muted small">{{ a.actorName }} · {{ a.at | relative }}</div></div></div>
              }
            </div>
          </div>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions>
        @if (!l.convertedClientId) {
          <button mat-button class="danger" type="button" (click)="remove(l)"><mat-icon>delete</mat-icon>Excluir</button>
        }
        <span class="spacer"></span>
        <button mat-stroked-button type="button" (click)="edit(l)"><mat-icon>edit</mat-icon>Editar</button>
        @if (l.convertedClientId) {
          <button mat-flat-button type="button" (click)="openClient(l)"><mat-icon>person</mat-icon>Abrir cliente</button>
        } @else if (auth.has('CLIENTS_WRITE') && l.stage !== 'PERDIDO') {
          <button mat-flat-button type="button" (click)="convert(l)"><mat-icon>how_to_reg</mat-icon>Converter em cliente</button>
        }
      </mat-dialog-actions>
    }
  `,
  styles: `
    mat-dialog-content { max-height: 76vh; min-width: min(900px, 90vw); }
    .temp { margin-left: auto; font-size: 12px; font-weight: 700; padding: 3px 10px; border-radius: 999px; }
    .temp.QUENTE { background: var(--app-danger-bg); color: var(--app-danger); }
    .temp.MORNO { background: var(--app-warning-bg); color: var(--app-warning); }
    .temp.FRIO { background: var(--app-info-bg); color: var(--app-info); }
    .stages { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 16px; }
    .st { flex: 1 1 110px; border: 1px solid var(--app-border); background: var(--app-surface-2); color: var(--app-text); padding: 8px 6px; font: inherit; font-size: 12px; cursor: pointer;
      clip-path: polygon(0 0, calc(100% - 8px) 0, 100% 50%, calc(100% - 8px) 100%, 0 100%, 8px 50%); }
    .st.on { background: var(--sc); color: #fff; border-color: var(--sc); font-weight: 700; }
    .st:disabled { cursor: default; }
    .grid2 { display: grid; grid-template-columns: 1fr 1.1fr; gap: 24px; }
    @media (max-width: 860px) { .grid2 { grid-template-columns: 1fr; } }
    dl { display: grid; grid-template-columns: 150px 1fr; gap: 8px 12px; margin: 0; font-size: 14px; }
    dt { color: var(--app-text-muted); font-size: 13px; }
    dd { margin: 0; }
    .late { color: var(--app-danger); }
    .notes { white-space: pre-wrap; background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 10px; padding: 10px; font-size: 13px; }
    .timeline { display: flex; flex-direction: column; gap: 10px; margin-top: 8px; max-height: 34vh; overflow: auto; }
    .ev { display: flex; gap: 10px; font-size: 13px; }
    .ev mat-icon { color: var(--app-gold); font-size: 20px; width: 20px; height: 20px; }
    .small { font-size: 12px; }
    .danger { color: var(--app-danger); }
    mat-dialog-actions { display: flex; gap: 8px; }
  `,
})
export class LeadDetailDialogComponent {
  private readonly id = inject<string>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<LeadDetailDialogComponent>);
  private readonly service = inject(CrmService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly auth = inject(AuthService);
  readonly stages = STAGES;
  readonly areaLabels = AREA_LABELS;
  readonly activityLabels = ACTIVITY_LABELS;
  readonly activityTypes: LeadActivityType[] = ['LIGACAO', 'WHATSAPP', 'EMAIL', 'REUNIAO', 'PROPOSTA', 'NOTA'];
  readonly tempLabel = { QUENTE: 'Quente', MORNO: 'Morno', FRIO: 'Frio' };
  readonly lead = signal<Lead | null>(null);
  readonly activities = signal<LeadActivity[]>([]);
  readonly sending = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    type: ['LIGACAO' as LeadActivityType],
    text: ['', [Validators.required, Validators.maxLength(2000)]],
    nextAction: [''],
    nextActionDate: [''],
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.service.get(this.id).subscribe((d) => { this.lead.set(d.lead); this.activities.set(d.activities); });
  }

  move(l: Lead, stage: LeadStage): void {
    if (stage === l.stage) return;
    if (stage === 'PERDIDO') {
      this.dialog.open(ConfirmDialogComponent, {
        data: { title: 'Marcar como perdida', message: `Informe o motivo da perda de ${l.name}.`, confirmLabel: 'Marcar perdida', danger: true,
          reasonLabel: 'Motivo (ex.: preço, escolheu outro escritório, sem viabilidade)', reasonRequired: true },
        width: '480px',
      }).afterClosed().subscribe((reason?: string) => {
        if (typeof reason === 'string' && reason) this.service.stage(l.id, stage, reason).subscribe(() => this.load());
      });
      return;
    }
    if (stage === 'GANHO' && this.auth.has('CLIENTS_WRITE')) {
      this.convert(l);
      return;
    }
    this.service.stage(l.id, stage).subscribe(() => this.load());
  }

  register(l: Lead): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    this.sending.set(true);
    this.service.activity(l.id, { type: v.type, text: v.text.trim(), nextAction: v.nextAction || null, nextActionDate: v.nextActionDate || null })
      .subscribe({
        next: (d) => {
          this.lead.set(d.lead);
          this.activities.set(d.activities);
          this.form.reset({ type: v.type, text: '', nextAction: '', nextActionDate: '' });
          this.sending.set(false);
        },
        error: () => this.sending.set(false),
      });
  }

  edit(l: Lead): void {
    this.dialog.open(LeadFormDialogComponent, { data: { lead: l }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe((saved?: Lead) => { if (saved) this.load(); });
  }

  convert(l: Lead): void {
    this.dialog.open(LeadConvertDialogComponent, { data: l, width: '600px', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: { lead: Lead; clientId: string }) => {
        if (r) {
          this.toast.success(`${l.name} agora é cliente do escritório.`);
          this.load();
        }
      });
  }

  openClient(l: Lead): void {
    this.ref.close();
    void this.router.navigate(['/clientes', l.convertedClientId]);
  }

  remove(l: Lead): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir oportunidade', message: `Os dados e o histórico de ${l.name} serão excluídos definitivamente (direito de eliminação — LGPD).`,
        confirmLabel: 'Excluir', danger: true },
      width: '480px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.delete(l.id).subscribe(() => { this.toast.success('Oportunidade excluída.'); this.ref.close(); });
    });
  }
}
