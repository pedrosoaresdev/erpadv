import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { Subject, debounceTime } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { ContractFormDialogComponent } from './contract-form-dialog';
import { CONTRACT_STATUS_LABELS, CONTRACT_STATUS_TONE, Contract, ContractStatus, ContractsService } from './contracts.service';
import { TemplatesDialogComponent } from './templates-dialog';

type Tab = '' | ContractStatus | 'VENCENDO';

export function feeSummary(c: Contract): string {
  const brl = (v: number | null) => (v ?? 0) / 100;
  const fmt = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
  const extra = c.successPercentBp ? ` + ${c.successPercentBp / 100}% êxito` : '';
  switch (c.feeModel) {
    case 'FIXO': return fmt.format(brl(c.amountCents)) + extra;
    case 'PARCELADO': return `${fmt.format(brl(c.amountCents))} em ${c.installments}x` + extra;
    case 'MENSAL': return `${fmt.format(brl(c.monthlyCents))}/mês × ${c.months}` + extra;
    case 'ETAPAS': return `${c.stages.length} etapa(s) · ${fmt.format(c.stages.reduce((a, s) => a + s.amountCents, 0) / 100)}` + extra;
    default: return `${(c.successPercentBp ?? 0) / 100}% de êxito`;
  }
}

@Component({
  selector: 'app-contracts-list',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule, MatProgressBarModule,
    PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Contratos e honorários" title="Contratos" subtitle="Contratos de honorários, partido mensal e procurações — com minuta gerada, assinatura e cobrança integrada ao financeiro.">
        <button mat-stroked-button (click)="templates()"><mat-icon>article</mat-icon>Modelos</button>
        @if (auth.has('CONTRACTS_WRITE')) { <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon>Novo contrato</button> }
      </app-page-header>
      <div class="card">
        <div class="toolbar">
          <mat-button-toggle-group [value]="tab()" (change)="setTab($event.value)" hideSingleSelectionIndicator>
            <mat-button-toggle value="">Todos</mat-button-toggle>
            <mat-button-toggle value="RASCUNHO">Rascunhos</mat-button-toggle>
            <mat-button-toggle value="ATIVO">Ativos</mat-button-toggle>
            <mat-button-toggle value="VENCENDO">Terminando em 30 dias</mat-button-toggle>
            <mat-button-toggle value="ENCERRADO">Encerrados</mat-button-toggle>
            <mat-button-toggle value="CANCELADO">Cancelados</mat-button-toggle>
          </mat-button-toggle-group>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon><mat-label>Nº (CT-…), título ou cliente</mat-label>
            <input matInput [ngModel]="search()" (ngModelChange)="search$.next($event)" />
          </mat-form-field>
        </div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && items().length === 0) {
          <app-empty-state icon="handshake" title="Nenhum contrato" text="Cadastre o contrato, gere a minuta a partir de um modelo e ative-o para gerar as cobranças." />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Contrato</th><th>Cliente</th><th>Honorários</th><th>Vigência</th><th>Situação</th></tr></thead>
              <tbody>
                @for (c of items(); track c.id) {
                  <tr class="clickable" (click)="open(c)" tabindex="0" (keydown.enter)="open(c)">
                    <td><div class="mono strong">{{ c.number }}</div><div class="small">{{ c.title }}</div></td>
                    <td>{{ c.clientName }}@if (c.caseNumber) { <div class="small mono">{{ c.caseNumber }}</div> }</td>
                    <td><div>{{ fee(c) }}</div><div class="small">{{ c.feeModelLabel }}</div></td>
                    <td>{{ c.startDate | brDate }}{{ c.endDate ? ' a ' + (c.endDate | brDate) : '' }}
                      @if (c.status === 'ATIVO' && c.daysToEnd !== null && c.daysToEnd <= 30) { <div class="small warn">termina em {{ c.daysToEnd }} dia(s)</div> }
                    </td>
                    <td>
                      <span class="badge" [class]="tone[c.status]">{{ statusLabel[c.status] }}</span>
                      @if (c.signedDocumentId) { <span class="badge info">assinado</span> }
                    </td>
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
    .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 14px 16px; border-bottom: 1px solid var(--app-border); }
    .grow { flex: 1 1 260px; }
    .strong { font-weight: 600; }
    .small { font-size: 12px; color: var(--app-text-muted); }
    .warn { color: var(--app-warning); font-weight: 600; }
    td .badge + .badge { margin-left: 6px; }
    .more { display: flex; justify-content: center; padding: 16px; }
  `,
})
export class ContractsListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(ContractsService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly statusLabel = CONTRACT_STATUS_LABELS;
  readonly tone = CONTRACT_STATUS_TONE;
  readonly fee = feeSummary;
  readonly tab = signal<Tab>('');
  readonly search = signal('');
  readonly search$ = new Subject<string>();
  readonly items = signal<Contract[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);

  constructor() {
    this.search$.pipe(debounceTime(350), takeUntilDestroyed()).subscribe((v) => { this.search.set(v); this.load(false); });
    this.load(false);
  }

  setTab(t: Tab): void {
    this.tab.set(t);
    this.load(false);
  }

  load(more: boolean): void {
    const t = this.tab();
    this.loading.set(true);
    this.service.list({
      status: t === 'VENCENDO' ? '' : t, expiring: t === 'VENCENDO', search: t === 'VENCENDO' ? undefined : this.search().trim() || undefined,
    }, more ? this.cursor() : null).subscribe({
      next: (p) => { this.items.set(more ? [...this.items(), ...p.items] : p.items); this.cursor.set(p.nextCursor); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  open(c: Contract): void {
    void this.router.navigate(['/contratos', c.id]);
  }

  create(): void {
    this.dialog.open(ContractFormDialogComponent, { data: null, width: '920px', maxWidth: '96vw' }).afterClosed().subscribe((c?: Contract) => {
      if (c) { this.toast.success(`Contrato ${c.number} criado como rascunho.`); this.open(c); }
    });
  }

  templates(): void {
    this.dialog.open(TemplatesDialogComponent, { width: '1100px', maxWidth: '96vw' });
  }
}
