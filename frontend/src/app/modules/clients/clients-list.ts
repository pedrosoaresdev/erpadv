import { Component, computed, inject, signal } from '@angular/core';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { TableState } from '../../shared/components/table-state';
import { saveBlob } from '../finance/finance.service';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router } from '@angular/router';
import { debounceTime } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Client, ClientSummary } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { CLIENT_STATUS_LABELS } from '../../shared/labels';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { ClientFormDialogComponent } from './client-form-dialog';
import { ClientFilter, ClientsService } from './clients.service';

@Component({
  selector: 'app-clients-list',
  imports: [
    ReactiveFormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatProgressBarModule, MatCheckboxModule, MatMenuModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="CRM jurídico" title="Clientes" subtitle="Pessoas físicas e jurídicas atendidas pelo escritório.">
        @if (auth.has('REPORTS_VIEW')) {
          <button mat-stroked-button [matMenuTriggerFor]="exportMenu"><mat-icon>download</mat-icon>Exportar</button>
          <mat-menu #exportMenu="matMenu" xPosition="before">
            <button mat-menu-item (click)="export('xlsx', false)"><mat-icon>table_view</mat-icon>Excel (filtros atuais)</button>
            <button mat-menu-item (click)="export('pdf', false)"><mat-icon>picture_as_pdf</mat-icon>PDF (filtros atuais)</button>
          </mat-menu>
        }
        @if (auth.has('CLIENTS_WRITE')) {
          <button mat-flat-button (click)="create()"><mat-icon>person_add</mat-icon>Novo cliente</button>
        }
      </app-page-header>

      <div class="card">
        <form class="filters" [formGroup]="filters">
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon>
            <mat-label>Nome, CPF/CNPJ, telefone ou e-mail</mat-label>
            <input matInput formControlName="search" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Situação</mat-label>
            <mat-select formControlName="status">
              <mat-option value="">Todas</mat-option>
              <mat-option value="ACTIVE">Ativos</mat-option>
              <mat-option value="INACTIVE">Inativos</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Tipo</mat-label>
            <mat-select formControlName="type">
              <mat-option value="">Todos</mat-option>
              <mat-option value="PF">Pessoa física</mat-option>
              <mat-option value="PJ">Pessoa jurídica</mat-option>
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Financeiro</mat-label>
            <mat-select formControlName="defaulting">
              <mat-option [value]="null">Todos</mat-option>
              <mat-option [value]="true">Inadimplentes</mat-option>
              <mat-option [value]="false">Adimplentes</mat-option>
            </mat-select>
          </mat-form-field>
          <button type="button" class="chip" [class.on]="staleMode()" (click)="toggleStale()"
            matTooltip="Clientes ativos sem atendimento ou movimentação há 90 dias ou mais"><mat-icon>hourglass_empty</mat-icon>Sem movimentação 90+ dias</button>
          <span class="spacer"></span>
          <button mat-icon-button type="button" [matMenuTriggerFor]="colsMenu" aria-label="Colunas visíveis" matTooltip="Colunas"><mat-icon>view_column</mat-icon></button>
          <mat-menu #colsMenu="matMenu" xPosition="before">
            @for (col of table.columns; track col.key) {
              @if (!col.fixed) {
                <button mat-menu-item (click)="$event.stopPropagation(); table.toggleColumn(col.key)">
                  <mat-icon>{{ table.visible(col.key) ? 'check_box' : 'check_box_outline_blank' }}</mat-icon>{{ col.label }}
                </button>
              }
            }
          </mat-menu>
        </form>
        @if (table.selectedCount()) {
          <div class="bulk" role="toolbar" aria-label="Ações em lote">
            <strong>{{ table.selectedCount() }} selecionado(s)</strong>
            @if (auth.has('REPORTS_VIEW')) {
              <button mat-button (click)="export('xlsx', true)"><mat-icon>table_view</mat-icon>Excel</button>
              <button mat-button (click)="export('pdf', true)"><mat-icon>picture_as_pdf</mat-icon>PDF</button>
            }
            @if (auth.has('CLIENTS_INACTIVATE')) {
              <button mat-button (click)="archiveSelected()"><mat-icon>archive</mat-icon>Arquivar</button>
            }
            <span class="spacer"></span>
            <button mat-button (click)="table.clear()">Limpar seleção</button>
          </div>
        }
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @if (!loading() && items().length === 0) {
          <app-empty-state icon="groups" title="Nenhum cliente encontrado"
            [text]="hasFilters() ? 'Ajuste os filtros da pesquisa.' : 'Cadastre o primeiro cliente do escritório.'" />
        } @else {
          <div class="table-wrap">
            <table class="data stack">
              <thead>
                <tr>
                  <th class="sel"><mat-checkbox [checked]="table.allSelected()" [indeterminate]="table.someSelected()" (change)="table.toggleAll()" aria-label="Selecionar todos" /></th>
                  <th [attr.aria-sort]="table.ariaSort('name')"><button class="sort" (click)="table.sortBy('name')">Cliente <mat-icon>{{ table.sortIcon('name') }}</mat-icon></button></th>
                  @if (table.visible('document')) { <th>Documento</th> }
                  @if (table.visible('contact')) { <th [attr.aria-sort]="table.ariaSort('phone')"><button class="sort" (click)="table.sortBy('phone')">Contato <mat-icon>{{ table.sortIcon('phone') }}</mat-icon></button></th> }
                  @if (table.visible('status')) { <th [attr.aria-sort]="table.ariaSort('status')"><button class="sort" (click)="table.sortBy('status')">Situação <mat-icon>{{ table.sortIcon('status') }}</mat-icon></button></th> }
                  @if (table.visible('createdAt')) { <th [attr.aria-sort]="table.ariaSort('createdAt')"><button class="sort" (click)="table.sortBy('createdAt')">Cadastro <mat-icon>{{ table.sortIcon('createdAt') }}</mat-icon></button></th> }
                </tr>
              </thead>
              <tbody>
                @for (c of table.sorted(); track c.id) {
                  <tr class="clickable" [class.picked]="table.isSelected(c.id)" (click)="open(c)" tabindex="0" (keydown.enter)="open(c)">
                    <td class="sel" (click)="$event.stopPropagation()"><mat-checkbox [checked]="table.isSelected(c.id)" (change)="table.toggle(c.id)" [attr.aria-label]="'Selecionar ' + c.name" /></td>
                    <td>
                      <div class="who">
                        <span class="av" [class.pj]="c.type === 'PJ'"><mat-icon>{{ c.type === 'PJ' ? 'apartment' : 'person' }}</mat-icon></span>
                        <div><div class="name">{{ c.name }}</div><div class="muted small">{{ c.type === 'PJ' ? 'Pessoa jurídica' : 'Pessoa física' }}</div></div>
                      </div>
                    </td>
                    @if (table.visible('document')) { <td class="mono" data-label="Documento">{{ c.documentMasked }}</td> }
                    @if (table.visible('contact')) { <td data-label="Contato"><div>{{ c.phone || '—' }}</div><div class="muted small">{{ c.email }}</div></td> }
                    @if (table.visible('status')) {
                      <td data-label="Situação">
                        <span class="badge" [class.success]="c.status === 'ACTIVE'">{{ statusLabel[c.status] }}</span>
                        @if (c.defaulting) { <span class="badge danger">Inadimplente</span> }
                      </td>
                    }
                    @if (table.visible('createdAt')) { <td class="muted" data-label="Cadastro">{{ c.createdAt | brDate }}</td> }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (nextCursor() && !staleMode()) {
            <div class="more"><button mat-stroked-button (click)="loadMore()" [disabled]="loading()">Carregar mais</button></div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 12px; padding: 16px; border-bottom: 1px solid var(--app-border); }
    .filters mat-form-field { width: 180px; }
    .filters .grow { flex: 1 1 320px; width: auto; }
    .who { display: flex; align-items: center; gap: 12px; }
    .av { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; background: var(--app-info-bg); color: var(--app-info); }
    .av.pj { background: var(--app-gold-soft); color: #9b7a3f; }
    .av mat-icon { font-size: 20px; width: 20px; height: 20px; }
    .name { font-weight: 600; }
    .small { font-size: 12px; }
    td .badge + .badge { margin-left: 6px; }
    .more { display: flex; justify-content: center; padding: 16px; border-top: 1px solid var(--app-border); }
    .filters { align-items: center; }
    .chip { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--app-border); background: var(--app-surface); color: var(--app-text);
      border-radius: 999px; padding: 7px 12px; cursor: pointer; font: inherit; font-size: 13px; }
    .chip mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .chip.on { background: var(--app-gold-soft); border-color: var(--app-gold); }
    .bulk { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 16px; background: var(--app-gold-soft); border-bottom: 1px solid var(--app-border); }
    .sel { width: 44px; }
    .sort { border: 0; background: none; color: inherit; font: inherit; text-transform: inherit; letter-spacing: inherit; cursor: pointer;
      display: inline-flex; align-items: center; gap: 2px; padding: 0; }
    .sort mat-icon { font-size: 14px; width: 14px; height: 14px; opacity: .6; }
    tr.picked { background: var(--app-gold-soft); }
  `,
})
export class ClientsListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(ClientsService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly statusLabel = CLIENT_STATUS_LABELS;

  readonly items = signal<ClientSummary[]>([]);
  readonly staleMode = signal(false);
  readonly table = new TableState<ClientSummary>(this.items, [
    { key: 'name', label: 'Cliente', fixed: true },
    { key: 'document', label: 'Documento' },
    { key: 'contact', label: 'Contato' },
    { key: 'status', label: 'Situação' },
    { key: 'createdAt', label: 'Cadastro' },
  ], (c, key) => key === 'name' ? c.name : key === 'phone' ? c.phone : key === 'status' ? c.status
    : key === 'createdAt' ? (c.createdAt ? new Date(c.createdAt).getTime() : null) : null, 'erpadv.cols.clients');
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);

  readonly filters = inject(FormBuilder).group({
    search: [''],
    status: ['ACTIVE' as ClientFilter['status']],
    type: ['' as ClientFilter['type']],
    defaulting: [null as boolean | null],
  });

  constructor() {
    this.filters.valueChanges.pipe(debounceTime(350), takeUntilDestroyed()).subscribe(() => this.reload());
    this.reload();
  }

  hasFilters(): boolean {
    const f = this.filters.value;
    return !!(f.search || f.type || f.defaulting !== null);
  }

  reload(): void {
    this.table.clear();
    if (this.staleMode()) {
      this.loading.set(true);
      this.service.stale(90).subscribe({
        next: (list) => { this.items.set(list); this.nextCursor.set(null); this.loading.set(false); },
        error: () => this.loading.set(false),
      });
      return;
    }
    this.fetch(null);
  }

  toggleStale(): void {
    this.staleMode.set(!this.staleMode());
    this.reload();
  }

  private currentFilter(): ClientFilter {
    const f = this.filters.getRawValue();
    return { search: f.search?.trim() ?? '', status: f.status ?? '', type: f.type ?? '', defaulting: f.defaulting };
  }

  export(format: 'pdf' | 'xlsx', selection: boolean): void {
    this.service.export(this.currentFilter(), selection ? this.table.selectedIds() : [], format).subscribe({
      next: (r) => { saveBlob(r, `clientes.${format}`); this.toast.success('Exportação gerada.'); },
      error: () => this.toast.error('Não foi possível exportar.'),
    });
  }

  archiveSelected(): void {
    const ids = this.table.selectedIds();
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Arquivar clientes', message: `${ids.length} cliente(s) serão arquivados (inativados). O histórico é preservado e pode ser restaurado.`,
        confirmLabel: 'Arquivar', reasonLabel: 'Motivo (registrado na auditoria)', reasonRequired: true },
      width: '480px',
    }).afterClosed().subscribe((reason?: string) => {
      if (typeof reason !== 'string' || !reason) return;
      const targets = this.items().filter((c) => ids.includes(c.id) && c.status === 'ACTIVE');
      if (!targets.length) { this.toast.success('Os selecionados já estão arquivados.'); return; }
      forkJoin(targets.map((c) => this.service.inactivate(c.id, reason))).subscribe({
        next: () => { this.toast.success(`${targets.length} cliente(s) arquivado(s).`); this.reload(); },
        error: () => this.reload(),
      });
    });
  }

  loadMore(): void {
    this.fetch(this.nextCursor());
  }

  private fetch(cursor: string | null): void {
    this.loading.set(true);
    const f = this.filters.getRawValue();
    this.service
      .list({ search: f.search?.trim() ?? '', status: f.status ?? '', type: f.type ?? '', defaulting: f.defaulting }, cursor)
      .subscribe({
        next: (page) => {
          this.items.set(cursor ? [...this.items(), ...page.items] : page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  open(c: ClientSummary): void {
    void this.router.navigate(['/clientes', c.id]);
  }

  create(): void {
    this.dialog
      .open(ClientFormDialogComponent, { data: null, width: '860px', maxWidth: '96vw', autoFocus: 'first-tabbable' })
      .afterClosed()
      .subscribe((client?: Client) => {
        if (client) {
          this.toast.success('Cliente cadastrado.');
          void this.router.navigate(['/clientes', client.id]);
        }
      });
  }
}
