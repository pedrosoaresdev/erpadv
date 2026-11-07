import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { debounceTime } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { CaseStatus, LegalArea, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { AREA_LABELS, CASE_STATUS_LABELS, CASE_STATUS_TONE, enumOptions } from '../../shared/labels';
import { CentsPipe } from '../../shared/pipes/format.pipes';
import { CaseFormDialogComponent } from './case-form-dialog';
import { MatMenuModule } from '@angular/material/menu';
import { forkJoin } from 'rxjs';
import { TableState } from '../../shared/components/table-state';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { saveBlob } from '../finance/finance.service';
import { CasesService } from './cases.service';

@Component({
  selector: 'app-cases-list',
  imports: [
    ReactiveFormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatProgressBarModule, MatTooltipModule, MatMenuModule, PageHeaderComponent, EmptyStateComponent, CentsPipe,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Contencioso" title="Processos" subtitle="Acompanhamento processual do escritório.">
        @if (auth.has('REPORTS_VIEW')) {
          <button mat-stroked-button [matMenuTriggerFor]="exportMenu"><mat-icon>download</mat-icon>Exportar</button>
          <mat-menu #exportMenu="matMenu" xPosition="before">
            <button mat-menu-item (click)="export('xlsx', false)"><mat-icon>table_view</mat-icon>Excel (filtros atuais)</button>
            <button mat-menu-item (click)="export('pdf', false)"><mat-icon>picture_as_pdf</mat-icon>PDF (filtros atuais)</button>
          </mat-menu>
        }
        @if (auth.has('CASES_WRITE')) {
          <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon>Novo processo</button>
        }
      </app-page-header>

      <div class="card">
        <form class="filters" [formGroup]="filters">
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon>
            <mat-label>Nº CNJ, cliente, parte contrária ou assunto</mat-label>
            <input matInput formControlName="search" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Situação</mat-label>
            <mat-select formControlName="status">
              <mat-option value="">Todas</mat-option>
              @for (o of statuses; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Área</mat-label>
            <mat-select formControlName="area">
              <mat-option value="">Todas</mat-option>
              @for (o of areas; track o.value) {
                @if (o.value !== 'CRIMINAL' || auth.has('CRIMINAL_READ')) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Advogado</mat-label>
            <mat-select formControlName="responsibleUid">
              <mat-option value="">Todos</mat-option>
              @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="small">
            <mat-label>Tribunal</mat-label>
            <input matInput formControlName="court" placeholder="TJSP" />
          </mat-form-field>
          <mat-checkbox formControlName="includeArchived">Incluir arquivados</mat-checkbox>
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
            @if (auth.has('CASES_ARCHIVE')) {
              <button mat-button (click)="archiveSelected()"><mat-icon>archive</mat-icon>Arquivar</button>
            }
            <span class="spacer"></span>
            <button mat-button (click)="table.clear()">Limpar seleção</button>
          </div>
        }
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @if (!loading() && items().length === 0) {
          <app-empty-state icon="gavel" title="Nenhum processo encontrado" text="Ajuste os filtros ou cadastre um novo processo." />
        } @else {
          <div class="table-wrap">
            <table class="data stack">
              <thead>
                <tr>
                  <th class="sel"><mat-checkbox [checked]="table.allSelected()" [indeterminate]="table.someSelected()" (change)="table.toggleAll()" aria-label="Selecionar todos" /></th>
                  <th [attr.aria-sort]="table.ariaSort('cnj')"><button class="sort" (click)="table.sortBy('cnj')">Processo <mat-icon>{{ table.sortIcon('cnj') }}</mat-icon></button></th>
                  @if (table.visible('client')) { <th [attr.aria-sort]="table.ariaSort('client')"><button class="sort" (click)="table.sortBy('client')">Cliente <mat-icon>{{ table.sortIcon('client') }}</mat-icon></button></th> }
                  @if (table.visible('court')) { <th [attr.aria-sort]="table.ariaSort('court')"><button class="sort" (click)="table.sortBy('court')">Juízo <mat-icon>{{ table.sortIcon('court') }}</mat-icon></button></th> }
                  @if (table.visible('area')) { <th>Área</th> }
                  @if (table.visible('responsible')) { <th [attr.aria-sort]="table.ariaSort('responsible')"><button class="sort" (click)="table.sortBy('responsible')">Responsável <mat-icon>{{ table.sortIcon('responsible') }}</mat-icon></button></th> }
                  @if (table.visible('value')) { <th [attr.aria-sort]="table.ariaSort('value')"><button class="sort" (click)="table.sortBy('value')">Valor da causa <mat-icon>{{ table.sortIcon('value') }}</mat-icon></button></th> }
                  @if (table.visible('phase')) { <th>Fase</th> }
                  @if (table.visible('status')) { <th [attr.aria-sort]="table.ariaSort('status')"><button class="sort" (click)="table.sortBy('status')">Situação <mat-icon>{{ table.sortIcon('status') }}</mat-icon></button></th> }
                </tr>
              </thead>
              <tbody>
                @for (p of table.sorted(); track p.id) {
                  <tr class="clickable" [class.picked]="table.isSelected(p.id)" (click)="open(p)" tabindex="0" (keydown.enter)="open(p)">
                    <td class="sel" (click)="$event.stopPropagation()"><mat-checkbox [checked]="table.isSelected(p.id)" (change)="table.toggle(p.id)" [attr.aria-label]="'Selecionar ' + (p.cnjNumber || p.clientName)" /></td>
                    <td>
                      <div class="mono strong">{{ p.cnjNumber || 'Não distribuído' }}</div>
                      <div class="muted small">{{ p.subject || p.proceduralClass || '—' }}</div>
                    </td>
                    @if (table.visible('client')) { <td data-label="Cliente"><div>{{ p.clientName }}</div><div class="muted small">x {{ p.opposingParty || '—' }}</div></td> }
                    @if (table.visible('court')) { <td data-label="Juízo"><div>{{ p.court || '—' }}</div><div class="muted small">{{ p.courtDivision }}{{ p.district ? ' · ' + p.district : '' }}</div></td> }
                    @if (table.visible('area')) {
                      <td data-label="Área">
                        {{ p.areaLabel }}
                        @if (p.restricted) { <mat-icon class="lock" matTooltip="Acesso restrito">lock</mat-icon> }
                      </td>
                    }
                    @if (table.visible('responsible')) { <td data-label="Responsável">{{ p.responsibleName }}</td> }
                    @if (table.visible('value')) { <td class="mono" data-label="Valor da causa">{{ p.claimValueCents | cents }}</td> }
                    @if (table.visible('phase')) { <td data-label="Fase">{{ p.phaseLabel || '—' }}</td> }
                    @if (table.visible('status')) { <td data-label="Situação"><span class="badge" [class]="tone[p.status]">{{ p.statusLabel }}</span></td> }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (nextCursor()) {
            <div class="more"><button mat-stroked-button (click)="fetch(nextCursor())" [disabled]="loading()">Carregar mais</button></div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 12px; padding: 16px; border-bottom: 1px solid var(--app-border); align-items: center; }
    .filters mat-form-field { width: 190px; }
    .filters mat-form-field.small { width: 120px; }
    .filters .grow { flex: 1 1 300px; width: auto; }
    .strong { font-weight: 600; }
    .small { font-size: 12px; }
    .lock { font-size: 16px; width: 16px; height: 16px; vertical-align: middle; color: var(--app-gold); }
    .more { display: flex; justify-content: center; padding: 16px; border-top: 1px solid var(--app-border); }
    .bulk { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 16px; background: var(--app-gold-soft); border-bottom: 1px solid var(--app-border); }
    .sel { width: 44px; }
    .sort { border: 0; background: none; color: inherit; font: inherit; text-transform: inherit; letter-spacing: inherit; cursor: pointer;
      display: inline-flex; align-items: center; gap: 2px; padding: 0; }
    .sort mat-icon { font-size: 14px; width: 14px; height: 14px; opacity: .6; }
    tr.picked { background: var(--app-gold-soft); }
  `,
})
export class CasesListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(CasesService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  readonly areas = enumOptions(AREA_LABELS);
  readonly statuses = enumOptions(CASE_STATUS_LABELS);
  readonly tone = CASE_STATUS_TONE;
  readonly lawyers = toSignal(this.service.lawyers(), { initialValue: [] });
  readonly items = signal<LegalCase[]>([]);
  readonly table = new TableState<LegalCase>(this.items, [
    { key: 'cnj', label: 'Processo', fixed: true },
    { key: 'client', label: 'Cliente' },
    { key: 'court', label: 'Juízo' },
    { key: 'area', label: 'Área' },
    { key: 'responsible', label: 'Responsável' },
    { key: 'value', label: 'Valor da causa' },
    { key: 'phase', label: 'Fase' },
    { key: 'status', label: 'Situação' },
  ], (p, key) => ({ cnj: p.cnjNumber, client: p.clientName, court: p.court, responsible: p.responsibleName,
    value: p.claimValueCents, status: p.statusLabel } as Record<string, string | number | null>)[key] ?? null, 'erpadv.cols.cases');
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);

  readonly filters = inject(FormBuilder).nonNullable.group({
    search: [''],
    status: ['' as CaseStatus | ''],
    area: ['' as LegalArea | ''],
    responsibleUid: [''],
    court: [''],
    includeArchived: [false],
  });

  constructor() {
    this.filters.valueChanges.pipe(debounceTime(350), takeUntilDestroyed()).subscribe(() => this.fetch(null));
    this.fetch(null);
  }

  private currentFilter() {
    const f = this.filters.getRawValue();
    return { ...f, search: f.search.trim(), court: f.court.trim().toUpperCase() };
  }

  export(format: 'pdf' | 'xlsx', selection: boolean): void {
    this.service.export(this.currentFilter(), selection ? this.table.selectedIds() : [], format).subscribe({
      next: (r) => { saveBlob(r, `processos.${format}`); this.toast.success('Exportação gerada.'); },
      error: () => this.toast.error('Não foi possível exportar.'),
    });
  }

  archiveSelected(): void {
    const targets = this.items().filter((p) => this.table.isSelected(p.id) && !p.archived);
    if (!targets.length) { this.toast.success('Os selecionados já estão arquivados.'); return; }
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Arquivar processos', message: `${targets.length} processo(s) ficarão somente leitura até serem desarquivados.`, confirmLabel: 'Arquivar' },
      width: '460px',
    }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      forkJoin(targets.map((p) => this.service.archive(p.id))).subscribe({
        next: () => { this.toast.success(`${targets.length} processo(s) arquivado(s).`); this.table.clear(); this.fetch(null); },
        error: () => this.fetch(null),
      });
    });
  }

  fetch(cursor: string | null): void {
    if (!cursor) this.table.clear();
    this.loading.set(true);
    const f = this.filters.getRawValue();
    this.service
      .list({ ...f, search: f.search.trim(), court: f.court.trim().toUpperCase() }, cursor)
      .subscribe({
        next: (page) => {
          this.items.set(cursor ? [...this.items(), ...page.items] : page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  open(p: LegalCase): void {
    void this.router.navigate(['/processos', p.id]);
  }

  create(): void {
    this.dialog
      .open(CaseFormDialogComponent, { data: {}, width: '900px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((created?: LegalCase) => {
        if (created) {
          this.toast.success('Processo cadastrado.');
          void this.router.navigate(['/processos', created.id]);
        }
      });
  }
}
