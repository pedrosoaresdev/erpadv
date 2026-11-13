import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router } from '@angular/router';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import {
  PLATFORM_TABS, PlatformBillingSettingsComponent, PlatformInvoicesComponent, PlatformMonitoringComponent, PlatformPlansComponent,
  PlatformSupportComponent, SubscriptionDialogComponent, platformTabIndex,
} from './platform-billing';
import { OfficeDetailDialogComponent, OfficeFormDialogComponent } from './platform-dialogs';
import { OfficeSummary, OfficeUser, PLAN_LABELS, PlatformOverview, PlatformService } from './platform.service';

/** Painel master do desenvolvedor: todos os escritórios (tenants) da plataforma. */
@Component({
  selector: 'app-platform-page',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatProgressBarModule,
    PageHeaderComponent, StatCardComponent, EmptyStateComponent, BrDatePipe, MatTabsModule, PlatformInvoicesComponent, PlatformPlansComponent,
    PlatformSupportComponent, PlatformMonitoringComponent, PlatformBillingSettingsComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Plataforma" title="Painel da plataforma" subtitle="Escritórios, assinaturas, faturas, planos, suporte e monitoramento.">
        <button mat-flat-button (click)="create()"><mat-icon>domain_add</mat-icon>Novo escritório</button>
      </app-page-header>

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="onTab($event)">
      <mat-tab label="Escritórios">
      @if (data(); as d) {
        <div class="stats">
          <app-stat-card label="Escritórios" [value]="d.stats.offices" icon="domain" [accent]="true" />
          <app-stat-card label="Ativos" [value]="d.stats.activeOffices" icon="check_circle" />
          <app-stat-card label="Suspensos" [value]="d.stats.suspendedOffices" icon="pause_circle"
            [tone]="d.stats.suspendedOffices ? 'warning' : ''" [hint]="d.stats.suspendedOffices ? 'acesso da equipe bloqueado' : ''" />
          <app-stat-card label="Usuários" [value]="d.stats.users" icon="group" [hint]="d.stats.activeUsers + ' ativos'" />
        </div>
      }

      <div class="card">
        <div class="toolbar">
          <div class="search">
            <mat-icon>search</mat-icon>
            <input type="search" placeholder="Buscar por nome, cidade, CNPJ ou e-mail" [ngModel]="term()" (ngModelChange)="term.set($event)" aria-label="Buscar escritório" />
          </div>
          <mat-button-toggle-group [ngModel]="statusFilter()" (ngModelChange)="statusFilter.set($event)" aria-label="Situação">
            <mat-button-toggle value="">Todos</mat-button-toggle>
            <mat-button-toggle value="ACTIVE">Ativos</mat-button-toggle>
            <mat-button-toggle value="SUSPENDED">Suspensos</mat-button-toggle>
          </mat-button-toggle-group>
        </div>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && filtered().length === 0) {
          <app-empty-state icon="domain" title="Nenhum escritório encontrado" text="Crie o primeiro escritório e cadastre o advogado administrador." />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Escritório</th><th>Plano</th><th>Usuários</th><th>Local</th><th>Criado em</th><th>Situação</th><th></th></tr></thead>
              <tbody>
                @for (o of filtered(); track o.id) {
                  <tr class="clickable" (click)="open(o)">
                    <td><div class="name">{{ o.name }}</div><div class="muted small">{{ o.cnpj || o.email || 'sem CNPJ' }}</div></td>
                    <td><span class="badge gold">{{ planLabels[o.plan] || o.plan }}</span></td>
                    <td>
                      <div class="seats">
                        <span class="mono">{{ o.seats }}{{ o.maxUsers ? ' / ' + o.maxUsers : '' }}</span>
                        @if (o.maxUsers) { <span class="bar"><span [style.width.%]="seatPct(o)" [class.full]="o.seats >= o.maxUsers"></span></span> }
                      </div>
                    </td>
                    <td class="muted">{{ o.city ? o.city + (o.state ? '/' + o.state : '') : '—' }}</td>
                    <td class="muted">{{ o.createdAt | brDate }}</td>
                    <td>
                      @if (o.status === 'SUSPENDED') { <span class="badge danger" [title]="o.suspendedReason || ''">Suspenso</span> }
                      @else { <span class="badge success">Ativo</span> }
                    </td>
                    <td class="actions" (click)="$event.stopPropagation()">
                      <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações do escritório"><mat-icon>more_vert</mat-icon></button>
                      <mat-menu #menu="matMenu" xPosition="before">
                        <button mat-menu-item (click)="open(o)"><mat-icon>visibility</mat-icon>Detalhes e equipe</button>
                        <button mat-menu-item (click)="edit(o)"><mat-icon>edit</mat-icon>Editar dados e plano</button>
                        <button mat-menu-item (click)="subscription(o)"><mat-icon>workspace_premium</mat-icon>Assinatura e cobrança</button>
                        <button mat-menu-item (click)="issue(o)"><mat-icon>receipt</mat-icon>Emitir fatura agora</button>
                        @if (o.status === 'SUSPENDED') {
                          <button mat-menu-item (click)="reactivate(o)"><mat-icon>play_circle</mat-icon>Reativar</button>
                        } @else {
                          <button mat-menu-item class="danger" (click)="suspend(o)"><mat-icon>pause_circle</mat-icon>Suspender acesso</button>
                        }
                      </mat-menu>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
      </mat-tab>
      <mat-tab label="Faturas"><ng-template matTabContent><div class="tab"><app-platform-invoices /></div></ng-template></mat-tab>
      <mat-tab label="Planos"><ng-template matTabContent><div class="tab"><app-platform-plans /></div></ng-template></mat-tab>
      <mat-tab label="Suporte"><ng-template matTabContent><div class="tab"><app-platform-support /></div></ng-template></mat-tab>
      <mat-tab label="Monitoramento"><ng-template matTabContent><div class="tab"><app-platform-monitoring /></div></ng-template></mat-tab>
      <mat-tab label="Dados de cobrança"><ng-template matTabContent><div class="tab"><app-platform-billing-settings /></div></ng-template></mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .tab { padding-top: 16px; }
    .stats { margin-top: 16px; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }
    .toolbar { display: flex; gap: 12px; align-items: center; padding: 14px 16px; flex-wrap: wrap; border-bottom: 1px solid var(--app-border); }
    .search { flex: 1 1 280px; display: flex; align-items: center; gap: 8px; background: var(--app-surface-2); border: 1px solid var(--app-border); border-radius: 10px; padding: 0 12px; height: 40px; }
    .search input { border: 0; outline: 0; background: transparent; color: var(--app-text); flex: 1; font: inherit; }
    .name { font-weight: 600; }
    .small { font-size: 12px; }
    .clickable { cursor: pointer; }
    .seats { display: flex; flex-direction: column; gap: 4px; min-width: 90px; }
    .bar { height: 5px; border-radius: 4px; background: var(--app-border); overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--app-gold); }
    .bar span.full { background: var(--app-danger); }
    .actions { text-align: right; width: 56px; }
    .danger { color: var(--app-danger); }
  `,
})
export class PlatformPageComponent {
  private readonly service = inject(PlatformService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  readonly planLabels = PLAN_LABELS as Record<string, string>;
  readonly data = signal<PlatformOverview | null>(null);
  readonly loading = signal(false);
  readonly term = signal('');
  readonly statusFilter = signal('');
  private readonly router = inject(Router);
  readonly tab = signal(platformTabIndex(inject(ActivatedRoute).snapshot.queryParamMap.get('aba')));

  onTab(i: number): void {
    this.tab.set(i);
    void this.router.navigate([], { queryParams: { aba: PLATFORM_TABS[i] }, replaceUrl: true });
  }

  subscription(o: OfficeSummary): void {
    this.dialog.open(SubscriptionDialogComponent, { data: { officeId: o.id, officeName: o.name }, width: '820px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => {
        if (r) { this.toast.success('Assinatura atualizada.'); this.load(); }
      });
  }

  issue(o: OfficeSummary): void {
    this.service.issueInvoice(o.id).subscribe((i) => this.toast.success(`Fatura ${i.number} emitida para ${o.name}.`));
  }

  readonly filtered = computed(() => {
    const t = this.term().trim().toLowerCase();
    const status = this.statusFilter();
    return (this.data()?.offices ?? []).filter((o) =>
      (!status || o.status === status) &&
      (!t || [o.name, o.city, o.cnpj, o.email].some((v) => (v ?? '').toLowerCase().includes(t))));
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service.overview().subscribe({
      next: (d) => { this.data.set(d); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  seatPct(o: OfficeSummary): number {
    return o.maxUsers ? Math.min(100, (o.seats / o.maxUsers) * 100) : 0;
  }

  create(): void {
    this.dialog.open(OfficeFormDialogComponent, { data: {}, width: '860px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((r?: { office: OfficeSummary; admin: OfficeUser }) => {
        if (r) {
          this.toast.success(`Escritório ${r.office.name} criado. ${r.admin.displayName} já pode entrar com o e-mail e a senha definidos.`);
          this.load();
        }
      });
  }

  edit(o: OfficeSummary): void {
    this.dialog.open(OfficeFormDialogComponent, { data: { office: o }, width: '760px', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: OfficeSummary) => {
        if (r) {
          this.toast.success('Escritório atualizado.');
          this.load();
        }
      });
  }

  open(o: OfficeSummary): void {
    this.dialog.open(OfficeDetailDialogComponent, { data: o.id, maxWidth: '96vw' }).afterClosed().subscribe(() => this.load());
  }

  suspend(o: OfficeSummary): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Suspender escritório', message: `Toda a equipe de ${o.name} perderá o acesso até a reativação. Os dados são preservados.`,
        confirmLabel: 'Suspender', danger: true, reasonLabel: 'Motivo (registrado na auditoria do escritório)', reasonRequired: true },
      width: '480px',
    }).afterClosed().subscribe((reason?: string) => {
      if (typeof reason === 'string' && reason) {
        this.service.suspend(o.id, reason).subscribe(() => { this.toast.success('Escritório suspenso.'); this.load(); });
      }
    });
  }

  reactivate(o: OfficeSummary): void {
    this.service.reactivate(o.id).subscribe(() => { this.toast.success('Escritório reativado.'); this.load(); });
  }
}
