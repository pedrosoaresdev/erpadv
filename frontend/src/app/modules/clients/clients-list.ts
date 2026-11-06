import { Component, inject, signal } from '@angular/core';
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
    MatProgressBarModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="CRM jurídico" title="Clientes" subtitle="Pessoas físicas e jurídicas atendidas pelo escritório.">
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
        </form>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @if (!loading() && items().length === 0) {
          <app-empty-state icon="groups" title="Nenhum cliente encontrado"
            [text]="hasFilters() ? 'Ajuste os filtros da pesquisa.' : 'Cadastre o primeiro cliente do escritório.'" />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead>
                <tr><th>Cliente</th><th>Documento</th><th>Contato</th><th>Situação</th><th>Cadastro</th></tr>
              </thead>
              <tbody>
                @for (c of items(); track c.id) {
                  <tr class="clickable" (click)="open(c)" tabindex="0" (keydown.enter)="open(c)">
                    <td>
                      <div class="who">
                        <span class="av" [class.pj]="c.type === 'PJ'"><mat-icon>{{ c.type === 'PJ' ? 'apartment' : 'person' }}</mat-icon></span>
                        <div><div class="name">{{ c.name }}</div><div class="muted small">{{ c.type === 'PJ' ? 'Pessoa jurídica' : 'Pessoa física' }}</div></div>
                      </div>
                    </td>
                    <td class="mono">{{ c.documentMasked }}</td>
                    <td><div>{{ c.phone || '—' }}</div><div class="muted small">{{ c.email }}</div></td>
                    <td>
                      <span class="badge" [class.success]="c.status === 'ACTIVE'">{{ statusLabel[c.status] }}</span>
                      @if (c.defaulting) { <span class="badge danger">Inadimplente</span> }
                    </td>
                    <td class="muted">{{ c.createdAt | brDate }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (nextCursor()) {
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
    this.fetch(null);
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
